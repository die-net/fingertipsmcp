package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.AtomicTextFile
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

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

/**
 * `settings.json`: where `serve` listens. The TCP port is picked once at random so it stays the
 * same across restarts. `--host`, `--port`, and `--unix` replace what is kept here, and `token
 * create` writes `pairing.json` from it.
 */
@Serializable
internal data class ChromeSettings(
    val port: Int,
    val host: String = DEFAULT_HOST,
    /** The Unix socket `serve` listens on instead of TCP, or null for TCP. */
    val unix: String? = null,
) {
    val listen: Listen
        get() = unix?.let(Listen::Unix) ?: Listen.Tcp(host, port)
}

internal fun loadSettings(dir: File): ChromeSettings {
    val file = settingsFile(dir)
    val text = file.read()
    if (text != null) {
        try {
            return ApiJson.decodeFromString(serializer<ChromeSettings>(), text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one; decoding throws the base class for bad input too.
            // A new port means clients have to pair again, so say why.
            logger.warn("settings.json did not parse, so it is made again with a new port", e)
        }
    }
    val created = ChromeSettings(port = PORT_MIN + SecureRandom().nextInt(PORT_MAX - PORT_MIN))
    saveSettings(dir, created)
    return created
}

/**
 * Where `serve` listens: what [options] give, with `settings.json` filling the rest. Given listen
 * options are kept in `settings.json`, so the next run and `token create` agree with this one.
 * `--host` or `--port` means TCP, and `--unix` a Unix socket.
 */
internal fun listenFor(options: ServeOptions, dir: File): Listen {
    val stored = loadSettings(dir)
    val wanted =
        when {
            options.unixSocket != null -> stored.copy(unix = options.unixSocket)
            options.host != null || options.port != null ->
                stored.copy(
                    host = options.host ?: stored.host,
                    port = options.port ?: stored.port,
                    unix = null,
                )
            else -> stored
        }
    if (wanted != stored) saveSettings(dir, wanted)
    return wanted.listen
}

/**
 * The address a client on this computer reaches [listen] at, for `pairing.json`. A server on every
 * address is reached on loopback. `pairing.json` has no way to name a Unix socket, so that is
 * refused.
 */
internal fun pairingAddress(listen: Listen): Pair<String, Int> =
    when (listen) {
        is Listen.Unix ->
            throw UsageException(
                "The server listens on the Unix socket ${listen.path}, which pairing.json " +
                    "cannot name. Pass --no-pairing to print the token instead."
            )
        is Listen.Tcp -> {
            val wildcard = runCatching { InetAddress.getByName(listen.host).isAnyLocalAddress }
            (if (wildcard.getOrDefault(false)) DEFAULT_HOST else listen.host) to listen.port
        }
    }

private fun settingsFile(dir: File) = AtomicTextFile(File(dir, "settings.json"))

private fun saveSettings(dir: File, settings: ChromeSettings) {
    settingsFile(dir).write(ApiJson.encodeToString(serializer<ChromeSettings>(), settings))
}

internal const val DEFAULT_HOST = "127.0.0.1"

private val logger = LoggerFactory.getLogger("FingertipsChrome")

// Clear of well-known and commonly scanned ports, as fingertips's are.
private const val PORT_MIN = 20_000
private const val PORT_MAX = 60_000
