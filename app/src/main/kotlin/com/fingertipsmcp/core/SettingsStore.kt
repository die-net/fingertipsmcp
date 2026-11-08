package com.fingertipsmcp.core

import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class Settings(
    /** Preferred local TCP port for `adb forward` onto the abstract socket. */
    val port: Int,
    val keepAwakeMs: Long = 60_000,
)

/**
 * Small JSON-file settings store in the app's private files directory. It reads and writes on the
 * caller's thread, which must not be the main thread.
 */
class SettingsStore(private val dir: File) {
    private val atomic = AtomicFile(File(dir, SETTINGS_FILE_NAME))
    private val state: MutableStateFlow<Settings> by lazy { MutableStateFlow(load()) }

    val settings: StateFlow<Settings> by lazy { state.asStateFlow() }

    val current: Settings
        get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(current)
        atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), next))
        state.value = next
    }

    private fun load(): Settings {
        val text = atomic.readUtf8()
        if (text != null) {
            try {
                return ApiJson.decodeFromString(serializer<Settings>(), text)
            } catch (e: IllegalArgumentException) {
                // SerializationException is one; decoding throws the base class for bad input too.
                // A new port means `fingertips` on the computer has to pair again, so say why.
                Log.w(TAG, "$SETTINGS_FILE_NAME did not parse, so it is made again", e)
            }
        }
        val random = SecureRandom()
        val created = Settings(port = EPHEMERAL_MIN + random.nextInt(EPHEMERAL_MAX - EPHEMERAL_MIN))
        dir.mkdirs()
        atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), created))
        return created
    }

    private companion object {
        const val TAG = "FingertipsSettings"
        const val SETTINGS_FILE_NAME = "settings.json"
        // Stay clear of well-known and commonly scanned ports.
        const val EPHEMERAL_MIN = 20_000
        const val EPHEMERAL_MAX = 60_000
    }
}

internal fun AtomicFile.readUtf8(): String? {
    if (!baseFile.exists()) return null
    return readFully().decodeToString()
}

internal fun AtomicFile.writeUtf8(text: String) = writeAll(text.toByteArray(Charsets.UTF_8))

@Suppress("MissingUseCall") // finishWrite and failWrite close the stream.
internal fun AtomicFile.writeAll(bytes: ByteArray) {
    val out = startWrite()
    try {
        out.write(bytes)
    } catch (e: IOException) {
        failWrite(out)
        throw e
    }
    finishWrite(out)
}
