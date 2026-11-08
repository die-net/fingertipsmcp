package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.AtomicTextFile
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Where fingertips-chrome keeps its files. Settings, tokens, and the pairing file live under
 * `$XDG_CONFIG_HOME/fingertips-chrome` (`~/.config/fingertips-chrome`), on macOS too, as
 * fingertips's client does. The Chrome profile is data: `$XDG_DATA_HOME/fingertips-chrome`, or by
 * default `~/.local/share/fingertips-chrome` on Linux and `~/Library/Application
 * Support/fingertips-chrome` on macOS.
 */
class ChromePaths(
    env: Map<String, String> = System.getenv(),
    home: File = File(System.getProperty("user.home")),
    osName: String = System.getProperty("os.name").orEmpty(),
) {
    val config: File =
        env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { File(it, NAME) }
            ?: File(home, ".config/$NAME")

    val data: File =
        env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { File(it, NAME) }
            ?: if (isMac(osName)) {
                File(home, "Library/Application Support/$NAME")
            } else {
                File(home, ".local/share/$NAME")
            }

    val pairing: File = File(config, "pairing.json")

    val profile: File = File(data, "chrome-profile")

    val chromeLog: File = File(data, "chrome.log")

    /** Creates both directories, readable only by this user. */
    fun ensure() {
        listOf(config, data).forEach { dir ->
            dir.mkdirs()
            ownerOnly(dir, "rwx------")
        }
    }

    private companion object {
        const val NAME = "fingertips-chrome"
    }
}

internal fun isMac(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.lowercase().let { "mac" in it || "darwin" in it }

/** Restricts [file] to its owner where the file system has POSIX permissions. */
internal fun ownerOnly(file: File, permissions: String = "rw-------") {
    try {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    } catch (_: UnsupportedOperationException) {
        // Not a POSIX file system.
    }
}

@Serializable internal data class ChromeSettings(val port: Int)

/** `settings.json`: the TCP port, picked once at random so it stays the same across restarts. */
internal fun loadSettings(dir: File): ChromeSettings {
    val file = AtomicTextFile(File(dir, "settings.json"))
    val stored =
        file.read()?.let { text ->
            runCatching { ApiJson.decodeFromString(serializer<ChromeSettings>(), text) }.getOrNull()
        }
    if (stored != null) return stored
    val created = ChromeSettings(port = PORT_MIN + SecureRandom().nextInt(PORT_MAX - PORT_MIN))
    file.write(ApiJson.encodeToString(serializer<ChromeSettings>(), created))
    return created
}

// Clear of well-known and commonly scanned ports, as fingertips's are.
private const val PORT_MIN = 20_000
private const val PORT_MAX = 60_000
