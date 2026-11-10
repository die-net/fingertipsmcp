package com.fingertipsmcp.chrome

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.Cleaner
import java.util.Objects
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Chrome on Windows, started with its DevTools pipe. Windows has no file descriptors 3 and 4 to
 * hand a child, so Chrome there takes the pipe as two inherited handles that
 * `--remote-debugging-io-pipes` names, its read handle first. Java's `ProcessBuilder` passes a
 * child only its standard handles, so this starts Chrome with `CreateProcessW`, through JNA, and a
 * handle list that lets Chrome inherit only the two pipe ends and its log file, not every handle
 * this process has made inheritable.
 *
 * It is a [Process], so [ChromeLauncher] runs it as it runs Chrome elsewhere. [destroy] closes the
 * pipe, which Chrome exits on as it does on SIGTERM, and [destroyForcibly] terminates it.
 */
internal class WindowsChromeProcess
private constructor(
    private val process: Pointer,
    private val pid: Long,
    toChrome: Pointer,
    fromChrome: Pointer,
) : Process() {
    private val input = PipeInput(PipeEnd(fromChrome))
    private val rawOutput = PipeOutput(PipeEnd(toChrome))
    private val output = BufferedOutputStream(rawOutput, PIPE_CHUNK)

    init {
        // Process has no close, so the process handle closes when nothing uses this any more.
        val handle = process
        handles.register(this) { kernel32.CloseHandle(handle) }
    }

    override fun getOutputStream(): OutputStream = output

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun pid(): Long = pid

    override fun isAlive(): Boolean = !exitedWithin(0)

    override fun waitFor(): Int {
        exitedWithin(INFINITE)
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val millis = unit.toMillis(timeout)
        if (millis <= 0) return !isAlive()
        // A DWORD of milliseconds. The longest finite wait is about 49 days, and one past it would
        // be INFINITE, so it is cut there. toInt keeps the DWORD's bits.
        return exitedWithin(minOf(millis, MAX_WAIT_MS).toInt())
    }

    override fun exitValue(): Int {
        if (isAlive()) throw IllegalThreadStateException("Chrome has not exited")
        val code = IntByReference()
        check(kernel32.GetExitCodeProcess(process, code)) { win32Failure("GetExitCodeProcess") }
        return code.value
    }

    /** Closes the DevTools pipe. Chrome exits when its end of the pipe closes. */
    override fun destroy() {
        rawOutput.close()
        input.close()
    }

    override fun destroyForcibly(): Process {
        if (!kernel32.TerminateProcess(process, KILLED) && isAlive()) {
            logger.warn("{}", win32Failure("TerminateProcess"))
        }
        return this
    }

    override fun supportsNormalTermination(): Boolean = true

    private fun exitedWithin(millis: Int): Boolean =
        when (val result = kernel32.WaitForSingleObject(process, millis)) {
            WAIT_OBJECT_0 -> true
            WAIT_TIMEOUT -> false
            else -> error("WaitForSingleObject returned $result")
        }

    companion object {
        /**
         * Starts [binary] with [arguments] and the switch that names its pipe, with Chrome's
         * standard output and error appended to [log], as on Linux and macOS.
         */
        fun start(binary: File, arguments: List<String>, log: File): Process {
            if (Native.POINTER_SIZE != Long.SIZE_BYTES) {
                throw IOException("FingertipsMCP for Chrome on Windows needs a 64-bit Java.")
            }
            val k =
                try {
                    kernel32
                } catch (e: LinkageError) {
                    throw IOException(
                        "Could not load JNA to start Chrome: ${e.message.orEmpty()}",
                        e,
                    )
                }
            // Chrome's ends, and the log, are closed here once Chrome has its own copies. This
            // process's ends are closed here only when the start fails.
            val forChrome = mutableListOf<Pointer>()
            val ours = mutableListOf<Pointer>()
            var started = false
            try {
                val (chromeReads, toChrome) =
                    k.pipe().also { (r, w) ->
                        forChrome += r
                        ours += w
                    }
                val (fromChrome, chromeWrites) =
                    k.pipe().also { (r, w) ->
                        ours += r
                        forChrome += w
                    }
                val logHandle = k.openForAppend(log).also { forChrome += it }
                forChrome.forEach { k.inheritable(it) }
                val command =
                    windowsCommandLine(
                        listOf(binary.path) +
                            arguments +
                            "--remote-debugging-io-pipes=${unsigned(chromeReads)},${unsigned(chromeWrites)}"
                    )
                val info =
                    k.createProcess(binary, command, inherited = forChrome) { startup ->
                        startup.dwFlags = STARTF_USESTDHANDLES
                        startup.hStdOutput = logHandle
                        startup.hStdError = logHandle
                    }
                k.CloseHandle(checkNotNull(info.hThread))
                return WindowsChromeProcess(
                        checkNotNull(info.hProcess),
                        info.dwProcessId.toLong(),
                        toChrome,
                        fromChrome,
                    )
                    .also { started = true }
            } finally {
                forChrome.forEach { k.CloseHandle(it) }
                if (!started) ours.forEach { k.CloseHandle(it) }
            }
        }

        private val handles: Cleaner = Cleaner.create()
        private val logger = LoggerFactory.getLogger("FingertipsChrome.Windows")
    }
}

/**
 * A Windows command line that `CommandLineToArgvW`, which Chrome parses its own with, splits back
 * into [args]. An argument with a space, tab, newline, or quote, or an empty one, is quoted. Inside
 * quotes, backslashes are literal except before a quote or at the end, where they are doubled.
 */
internal fun windowsCommandLine(args: List<String>): String =
    args.joinToString(" ") { arg ->
        if (arg.isNotEmpty() && arg.none(QUOTED::contains)) {
            return@joinToString arg
        }
        buildString {
            append('"')
            var backslashes = 0
            for (c in arg) {
                when (c) {
                    '\\' -> backslashes++
                    '"' -> {
                        repeat(backslashes * 2 + 1) { append('\\') }
                        append('"')
                        backslashes = 0
                    }
                    else -> {
                        repeat(backslashes) { append('\\') }
                        append(c)
                        backslashes = 0
                    }
                }
            }
            repeat(backslashes * 2) { append('\\') }
            append('"')
        }
    }

/** Characters that make an argument need quotes. */
private val QUOTED = setOf(' ', '\t', '\n', '"')

/** A handle as `--remote-debugging-io-pipes` takes it: an unsigned integer. */
private fun unsigned(handle: Pointer): String = Pointer.nativeValue(handle).toULong().toString()

/** An anonymous pipe as its read and write handles, neither inheritable yet. */
private fun Kernel32.pipe(): Pair<Pointer, Pointer> {
    val read = PointerByReference()
    val write = PointerByReference()
    if (!CreatePipe(read, write, null, PIPE_CHUNK)) throw IOException(win32Failure("CreatePipe"))
    return read.value to write.value
}

/** [log] opened to append, shared so the server can read it while Chrome writes. */
private fun Kernel32.openForAppend(log: File): Pointer {
    val handle =
        CreateFileW(
            WString(log.absolutePath),
            FILE_APPEND_DATA,
            FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE,
            null,
            OPEN_ALWAYS,
            FILE_ATTRIBUTE_NORMAL,
            null,
        )
    if (handle == null || Pointer.nativeValue(handle) == INVALID_HANDLE_VALUE) {
        throw IOException(win32Failure("Opening ${log.path}"))
    }
    return handle
}

/**
 * Makes [handle] inheritable. The child [createProcess] starts gets only the handles it names. A
 * process this JVM started in the meantime with every handle inherited would get it too, and
 * fingertips-chrome starts no other process.
 */
private fun Kernel32.inheritable(handle: Pointer) {
    if (!SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
        throw IOException(win32Failure("SetHandleInformation"))
    }
}

/**
 * Runs [command] with [binary] as its program, passing it only the [inherited] handles, with
 * [startup] filling in the rest of its `STARTUPINFO`.
 */
private fun Kernel32.createProcess(
    binary: File,
    command: String,
    inherited: List<Pointer>,
    startup: (StartupInfoEx) -> Unit,
): ProcessInformation =
    withHandleList(inherited) { list ->
        val info = StartupInfoEx()
        info.cb = info.size()
        info.lpAttributeList = list
        startup(info)
        Memory((command.length + 1L) * Native.WCHAR_SIZE).use { line ->
            line.setWideString(0, command)
            val process = ProcessInformation()
            val created =
                CreateProcessW(
                    WString(binary.path),
                    line,
                    null,
                    null,
                    true,
                    EXTENDED_STARTUPINFO_PRESENT,
                    null,
                    null,
                    info,
                    process,
                )
            if (!created) throw IOException(win32Failure("Starting ${binary.path}"))
            process
        }
    }

/**
 * Runs [block] with a process attribute list that names [inherited] as the only handles a child
 * inherits. The list points into the handles' memory, so both stay open until [block] returns.
 */
private fun <T> Kernel32.withHandleList(inherited: List<Pointer>, block: (Pointer) -> T): T {
    val size = LongByReference()
    // The first call only asks how large the list is, and fails saying so.
    if (!InitializeProcThreadAttributeList(null, 1, 0, size) && size.value == 0L) {
        throw IOException(win32Failure("InitializeProcThreadAttributeList"))
    }
    val step = Native.POINTER_SIZE.toLong()
    return Memory(step * inherited.size).use { handles ->
        inherited.forEachIndexed { i, h -> handles.setPointer(step * i, h) }
        Memory(size.value).use { list ->
            if (!InitializeProcThreadAttributeList(list, 1, 0, size)) {
                throw IOException(win32Failure("InitializeProcThreadAttributeList"))
            }
            try {
                val attribute = Pointer(PROC_THREAD_ATTRIBUTE_HANDLE_LIST)
                if (
                    !UpdateProcThreadAttribute(
                        list,
                        0,
                        attribute,
                        handles,
                        handles.size(),
                        null,
                        null,
                    )
                ) {
                    throw IOException(win32Failure("UpdateProcThreadAttribute"))
                }
                block(list)
            } finally {
                DeleteProcThreadAttributeList(list)
            }
        }
    }
}

/** One end of a pipe, whose blocking reads or writes [close] cuts short. */
private class PipeEnd(private val handle: Pointer) {
    private val lock = Any()
    private var busy = false
    private var closed = false
    private var released = false

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /**
     * Runs [io] on the handle, or returns null once the end is closed. [io] reads Windows' error
     * itself, since closing the handle after it would replace the error.
     */
    fun <T> use(io: (Pointer) -> T): T? {
        synchronized(lock) {
            if (closed) return null
            busy = true
        }
        try {
            return io(handle)
        } finally {
            synchronized(lock) {
                busy = false
                if (closed) release()
            }
        }
    }

    /**
     * Closes the handle, or, while a read or write blocks on it, cancels that, and [use] closes it
     * as it returns. A cancel that comes before the read starts misses it, and the read then ends
     * when Chrome exits or writes.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (busy) kernel32.CancelIoEx(handle, null) else release()
        }
    }

    private fun release() {
        if (released) return
        released = true
        kernel32.CloseHandle(handle)
    }
}

/** Chrome's output: what it writes to the pipe. */
private class PipeInput(private val end: PipeEnd) : InputStream() {
    private val buffer = Memory(PIPE_CHUNK.toLong())
    private val count = IntByReference()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        while (true) {
            val result =
                end.use {
                    if (kernel32.ReadFile(it, buffer, minOf(len, PIPE_CHUNK), count, null)) {
                        count.value
                    } else {
                        -Native.getLastError()
                    }
                } ?: return -1
            when {
                result > 0 -> {
                    buffer.read(0, b, off, result)
                    return result
                }
                // A zero-length write by Chrome: read again.
                result == 0 -> continue
                -result == ERROR_BROKEN_PIPE || end.isClosed -> return -1
                else ->
                    throw IOException("Reading the DevTools pipe failed (Windows error ${-result})")
            }
        }
    }

    override fun close() = end.close()
}

/** Chrome's input: what this process writes to the pipe. */
private class PipeOutput(private val end: PipeEnd) : OutputStream() {
    private val buffer = Memory(PIPE_CHUNK.toLong())
    private val count = IntByReference()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        Objects.checkFromIndexSize(off, len, b.size)
        var done = 0
        while (done < len) {
            val chunk = minOf(len - done, PIPE_CHUNK)
            buffer.write(0, b, off + done, chunk)
            val result =
                end.use {
                    if (kernel32.WriteFile(it, buffer, chunk, count, null)) {
                        count.value
                    } else {
                        -Native.getLastError()
                    }
                } ?: throw IOException("The DevTools pipe is closed")
            if (result < 0) {
                throw IOException("Writing the DevTools pipe failed (Windows error ${-result})")
            }
            done += result
        }
    }

    override fun close() = end.close()
}

private fun win32Failure(what: String): String =
    "$what failed (Windows error ${Native.getLastError()})"

/** kernel32, loaded on first use, which only happens on Windows. */
private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

/** The kernel32 functions [WindowsChromeProcess] calls, with Win32's names and argument order. */
// JNA binds each method to the kernel32 function of the same name, one method for each.
@Suppress("FunctionNaming", "FunctionNameMaxLength", "ComplexInterface")
internal interface Kernel32 : Library {
    fun CreatePipe(
        read: PointerByReference,
        write: PointerByReference,
        attributes: Pointer?,
        size: Int,
    ): Boolean

    fun SetHandleInformation(handle: Pointer, mask: Int, flags: Int): Boolean

    fun CreateFileW(
        name: WString,
        access: Int,
        share: Int,
        attributes: Pointer?,
        disposition: Int,
        flags: Int,
        template: Pointer?,
    ): Pointer?

    fun InitializeProcThreadAttributeList(
        list: Pointer?,
        count: Int,
        flags: Int,
        size: LongByReference,
    ): Boolean

    fun UpdateProcThreadAttribute(
        list: Pointer,
        flags: Int,
        attribute: Pointer,
        value: Pointer,
        size: Long,
        previous: Pointer?,
        returnSize: Pointer?,
    ): Boolean

    fun DeleteProcThreadAttributeList(list: Pointer)

    fun CreateProcessW(
        application: WString,
        commandLine: Pointer,
        processAttributes: Pointer?,
        threadAttributes: Pointer?,
        inheritHandles: Boolean,
        flags: Int,
        environment: Pointer?,
        directory: WString?,
        startup: StartupInfoEx,
        info: ProcessInformation,
    ): Boolean

    fun ReadFile(
        file: Pointer,
        buffer: Pointer,
        size: Int,
        read: IntByReference,
        overlapped: Pointer?,
    ): Boolean

    fun WriteFile(
        file: Pointer,
        buffer: Pointer,
        size: Int,
        written: IntByReference,
        overlapped: Pointer?,
    ): Boolean

    fun CancelIoEx(file: Pointer, overlapped: Pointer?): Boolean

    fun CloseHandle(handle: Pointer): Boolean

    fun WaitForSingleObject(handle: Pointer, millis: Int): Int

    fun GetExitCodeProcess(process: Pointer, code: IntByReference): Boolean

    fun TerminateProcess(process: Pointer, code: Int): Boolean
}

/** Win32's `STARTUPINFOEXW`, field for field. JNA reads and writes the fields by name. */
@Structure.FieldOrder(
    "cb",
    "lpReserved",
    "lpDesktop",
    "lpTitle",
    "dwX",
    "dwY",
    "dwXSize",
    "dwYSize",
    "dwXCountChars",
    "dwYCountChars",
    "dwFillAttribute",
    "dwFlags",
    "wShowWindow",
    "cbReserved2",
    "lpReserved2",
    "hStdInput",
    "hStdOutput",
    "hStdError",
    "lpAttributeList",
)
internal class StartupInfoEx : Structure() {
    @JvmField var cb: Int = 0
    @JvmField var lpReserved: Pointer? = null
    @JvmField var lpDesktop: Pointer? = null
    @JvmField var lpTitle: Pointer? = null
    @JvmField var dwX: Int = 0
    @JvmField var dwY: Int = 0
    @JvmField var dwXSize: Int = 0
    @JvmField var dwYSize: Int = 0
    @JvmField var dwXCountChars: Int = 0
    @JvmField var dwYCountChars: Int = 0
    @JvmField var dwFillAttribute: Int = 0
    @JvmField var dwFlags: Int = 0
    @JvmField var wShowWindow: Short = 0
    @JvmField var cbReserved2: Short = 0
    @JvmField var lpReserved2: Pointer? = null
    @JvmField var hStdInput: Pointer? = null
    @JvmField var hStdOutput: Pointer? = null
    @JvmField var hStdError: Pointer? = null
    @JvmField var lpAttributeList: Pointer? = null
}

/** Win32's `PROCESS_INFORMATION`, which `CreateProcessW` fills in. */
@Structure.FieldOrder("hProcess", "hThread", "dwProcessId", "dwThreadId")
internal class ProcessInformation : Structure() {
    @JvmField var hProcess: Pointer? = null
    @JvmField var hThread: Pointer? = null
    @JvmField var dwProcessId: Int = 0
    @JvmField var dwThreadId: Int = 0
}

/** Bytes a pipe read or write moves at most, and the size each pipe is asked for. */
private const val PIPE_CHUNK = 64 * 1024
private const val BYTE_MASK = 0xFF
private const val KILLED = 1
private const val INFINITE = -1 // 0xFFFFFFFF as a DWORD.
private const val MAX_WAIT_MS = 0xFFFFFFFEL
private const val WAIT_OBJECT_0 = 0
private const val WAIT_TIMEOUT = 0x102
private const val ERROR_BROKEN_PIPE = 109
private const val INVALID_HANDLE_VALUE = -1L
private const val HANDLE_FLAG_INHERIT = 1
private const val FILE_APPEND_DATA = 4
private const val FILE_SHARE_READ = 1
private const val FILE_SHARE_WRITE = 2
private const val FILE_SHARE_DELETE = 4
private const val OPEN_ALWAYS = 4
private const val FILE_ATTRIBUTE_NORMAL = 0x80
private const val STARTF_USESTDHANDLES = 0x100
private const val EXTENDED_STARTUPINFO_PRESENT = 0x80000
private const val PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002L
