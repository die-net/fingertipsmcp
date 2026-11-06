package net.die.phoneapi.helper

import android.content.ClipData
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import net.die.phoneapi.helper.compat.AdbPort
import net.die.phoneapi.helper.compat.AudioTap
import net.die.phoneapi.helper.compat.InputInjector
import net.die.phoneapi.helper.compat.MirrorDisplay
import net.die.phoneapi.helper.compat.ShellClipboard
import net.die.phoneapi.helper.compat.TopTask
import net.die.phoneapi.helper.tree.TreeHost

/** [IHelper] implementation. Every privileged call drops the app's Binder identity first. */
internal open class HelperImpl : IHelper.Stub() {
    @Volatile private var appUid: Int = UNKNOWN_UID
    private val injector by lazy { InputInjector() }
    private val mirror = MirrorDisplay()
    private val audio = AudioTap()
    @Volatile private var treeClient: ITreeClient? = null
    private val tree by lazy { TreeHost { treeClient } }
    private val topTask by lazy { TopTask() }
    private val clipboard by lazy { ShellClipboard() }

    /**
     * Resolves [packageName]'s uid before any binder call is served. `cmd package` is used because
     * this process has no application Context.
     */
    fun pinPackage(packageName: String) {
        val uid = PackageUids.resolve(packageName)
        if (uid == null) {
            Log.e(TAG, "Could not resolve uid of $packageName")
            return
        }
        Log.i(TAG, "Pinned $packageName to uid $uid")
        appUid = uid
    }

    /** Uses the app's own uid when [pinPackage] could not resolve one. Never trusts the caller. */
    fun adoptRegisteredUid(uid: Int) {
        if (uid < Process.FIRST_APPLICATION_UID) return
        val pinned = appUid
        if (pinned == UNKNOWN_UID) {
            appUid = uid
        } else if (pinned != uid) {
            Log.w(TAG, "Registration uid $uid does not match pinned uid $pinned")
        }
    }

    override fun protocolVersion(): Int = Registration.PROTOCOL_VERSION

    override fun pid(): Int = Process.myPid()

    override fun injectMotionEvent(event: MotionEvent, mode: Int): Boolean =
        privileged("injectMotion") {
            try {
                injector.inject(event, mode)
            } finally {
                event.recycle()
            }
        }

    override fun injectKeyEvent(event: KeyEvent, mode: Int): Boolean =
        privileged("injectKey") { injector.inject(event, mode) }

    override fun touchscreenInfo(): TouchscreenInfo = privileged("touchscreen") { touchscreen() }

    @Suppress("MissingUseCall") // The app reads this descriptor; closing it here would break that.
    override fun openAbstractSocket(name: String): ParcelFileDescriptor =
        privileged("socket") { AbstractSockets.connect(name) }

    override fun listDevtoolsSockets(): String =
        privileged("sockets") { AbstractSockets.devtools() }

    override fun adbWirelessPort(): Int = privileged("adbPort") { AdbPort.read() }

    override fun exec(argv: Array<out String>, timeoutMs: Long, maxOutputBytes: Int): ShellResult =
        privileged("exec") {
            if (argv.isEmpty()) throw RemoteException("argv is empty")
            Commands.exec(argv.toList(), timeoutMs, maxOutputBytes)
        }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun logcat(args: Array<out String>): ParcelFileDescriptor =
        privileged("logcat") { Commands.pipe(listOf("logcat") + args) }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun screencap(): ParcelFileDescriptor =
        privileged("screencap") { Commands.pipe(listOf("screencap", "-p")) }

    override fun startMirror(surface: Surface, width: Int, height: Int, displayId: Int): Boolean =
        privileged("mirror") {
            mirror.start(surface, width, height, displayId)
            true
        }

    override fun stopMirror() {
        privileged("mirror-stop") { mirror.stop() }
    }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun startAudioCapture(sampleRate: Int, channels: Int): ParcelFileDescriptor? =
        privileged("audio") { audio.start(sampleRate, channels) }

    override fun stopAudioCapture() {
        privileged("audio-stop") { audio.stop() }
    }

    override fun setTreeClient(client: ITreeClient?) {
        privileged("tree-client") { treeClient = client }
    }

    override fun tree(op: String, payload: String): String =
        privileged("tree") { tree.call(op, payload) }

    override fun topPackage(): String? = privileged("topTask") { topTask.packageName() }

    override fun primaryClip(): ClipData? = privileged("clip") { clipboard.primaryClip() }

    override fun setPrimaryClip(clip: ClipData) {
        privileged("clip-set") { clipboard.setPrimaryClip(clip) }
    }

    override fun awaitClipChange(seen: Long, timeoutMs: Long): Long =
        privileged("clip-wait") { clipboard.awaitChange(seen, timeoutMs) }

    override fun clearPrimaryClip() {
        privileged("clip-clear") { clipboard.clearPrimaryClip() }
    }

    override fun primaryClipTimestamp(): Long =
        privileged("clip-time") { clipboard.primaryClipTimestamp() }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun readContent(uri: String): ParcelFileDescriptor =
        privileged("content") {
            require(uri.startsWith("content://")) { "not a content URI" }
            Commands.pipe(listOf("content", "read", "--uri", uri))
        }

    @Suppress("ExitOutsideMain") // The app asked this process to stop.
    override fun shutdown() {
        enforceCaller()
        runCatching { tree.shutdown() }
        Log.i(TAG, "shutdown")
        System.exit(0)
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.ROOT_UID) return
        val expected = appUid
        if (expected == UNKNOWN_UID || uid != expected) throw SecurityException("caller uid $uid")
    }

    @Suppress("TooGenericExceptionCaught") // Log binder failures, then rethrow them to the app.
    private inline fun <T> privileged(what: String, block: () -> T): T {
        enforceCaller()
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } catch (e: RuntimeException) {
            Log.e(TAG, what, e)
            throw e
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun touchscreen(): TouchscreenInfo {
        val touch = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val candidate = InputDevice.getDevice(id) ?: continue
            if (candidate.sources and InputDevice.SOURCE_TOUCHSCREEN != 0) touch += candidate
        }
        val device = touch.firstOrNull { !it.isVirtual } ?: touch.firstOrNull()
        return if (device == null) defaultTouchscreen() else deviceTouchscreen(device)
    }

    private fun defaultTouchscreen(): TouchscreenInfo =
        TouchscreenInfo().apply {
            deviceId = 0
            source = InputDevice.SOURCE_TOUCHSCREEN
            maxX = 0f
            maxY = 0f
            pressure = axis(0f, 1f)
            touchMajor = axis(0f, 1f)
            touchMinor = axis(0f, 1f)
            orientation = axis(0f, 0f)
            size = axis(0f, 1f)
        }

    private fun deviceTouchscreen(device: InputDevice): TouchscreenInfo =
        TouchscreenInfo().apply {
            deviceId = device.id
            source = InputDevice.SOURCE_TOUCHSCREEN
            maxX = axisMax(device, MotionEvent.AXIS_X)
            maxY = axisMax(device, MotionEvent.AXIS_Y)
            pressure = axisRange(device, MotionEvent.AXIS_PRESSURE)
            touchMajor = axisRange(device, MotionEvent.AXIS_TOUCH_MAJOR)
            touchMinor = axisRange(device, MotionEvent.AXIS_TOUCH_MINOR)
            orientation = axisRange(device, MotionEvent.AXIS_ORIENTATION)
            size = axisRange(device, MotionEvent.AXIS_SIZE)
        }

    private fun axisRange(device: InputDevice, axis: Int): AxisRange {
        val range = device.getMotionRange(axis)
        return axis(range?.min ?: 0f, range?.max ?: 1f)
    }

    private fun axis(min: Float, max: Float): AxisRange =
        AxisRange().apply {
            this.min = min
            this.max = max
        }

    private fun axisMax(device: InputDevice, axis: Int): Float =
        device.getMotionRange(axis)?.max ?: 0f

    fun apkStillThere(classpath: String): Boolean =
        classpath.split(':').filter { it.isNotEmpty() }.all { File(it).exists() }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val UNKNOWN_UID = -1
    }
}
