package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.AtomicTextFile
import com.fingertipsmcp.model.PairingInfo
import com.fingertipsmcp.server.TokenStore
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.serialization.serializer

fun main(args: Array<String>) {
    configureLogging()
    val command =
        try {
            parseCommand(args.toList())
        } catch (e: UsageException) {
            System.err.println("fingertips-chrome: ${e.message.orEmpty()}")
            System.err.println()
            System.err.println(USAGE)
            exitProcess(USAGE_EXIT)
        }
    val paths = ChromePaths()
    when (command) {
        Command.Help -> System.out.println(USAGE)
        Command.Version -> System.out.println("fingertips-chrome ${version()}")
        is Command.Serve ->
            try {
                serve(command.options, paths)
            } catch (e: UsageException) {
                System.err.println("fingertips-chrome: ${e.message.orEmpty()}")
                exitProcess(USAGE_EXIT)
            }
        is Command.TokenCreate ->
            try {
                createToken(command, paths, System.out)
            } catch (e: UsageException) {
                System.err.println("fingertips-chrome: ${e.message.orEmpty()}")
                exitProcess(1)
            }
        Command.TokenList -> listTokens(paths, System.out)
        is Command.TokenRevoke ->
            if (!revokeToken(command.id, paths)) {
                System.err.println("fingertips-chrome: no token ${command.id}")
                exitProcess(1)
            }
    }
}

private fun serve(options: ServeOptions, paths: ChromePaths) {
    paths.ensure()
    val app = ChromeApp(options, paths, version())
    if (options.stdio) {
        app.serveStdio()
    } else {
        app.serveHttp(listenFor(options, paths.config))
    }
}

/**
 * Mints a token and writes `pairing.json` for it, with the address in `settings.json` that `serve`
 * listens on. It is refused before minting when that address is a Unix socket.
 */
internal fun createToken(command: Command.TokenCreate, paths: ChromePaths, out: PrintStream) {
    paths.ensure()
    val address =
        if (command.writePairing) pairingAddress(loadSettings(paths.config).listen) else null
    val created = TokenStore(paths.config).create(command.name, command.scopes)
    out.println(
        "Created token ${created.info.id} (${command.name}) with scopes " +
            command.scopes.joinToString(",") { it.name.lowercase() }
    )
    if (address != null) {
        val pairing =
            PairingInfo(
                host = address.first,
                port = address.second,
                token = created.token,
                name = command.name,
            )
        AtomicTextFile(paths.pairing)
            .write(ApiJson.encodeToString(serializer<PairingInfo>(), pairing))
        ownerOnly(paths.pairing)
        out.println("Wrote ${paths.pairing}")
    } else {
        out.println(created.token)
    }
}

private fun listTokens(paths: ChromePaths, out: PrintStream) {
    TokenStore(paths.config).list().forEach { token ->
        val scopes = token.scopes.joinToString(",") { it.name.lowercase() }
        out.println("${token.id}\t${token.name}\t$scopes")
    }
}

private fun revokeToken(id: String, paths: ChromePaths): Boolean =
    TokenStore(paths.config).revoke(id)

private fun version(): String =
    ChromeApp::class.java.`package`?.implementationVersion ?: "0.0.0-dev"

/**
 * slf4j-simple writes to stderr, which keeps stdout free for `--stdio`. Only fingertips-chrome's
 * own loggers report at info; Ktor and the MCP SDK speak up only when something goes wrong. Each
 * setting can still be overridden with `-D`.
 */
private fun configureLogging() {
    val defaults =
        mapOf(
            "org.slf4j.simpleLogger.logFile" to "System.err",
            "org.slf4j.simpleLogger.showDateTime" to "true",
            "org.slf4j.simpleLogger.dateTimeFormat" to "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "org.slf4j.simpleLogger.showThreadName" to "false",
            "org.slf4j.simpleLogger.defaultLogLevel" to "warn",
            "org.slf4j.simpleLogger.log.FingertipsChrome" to "info",
            "org.slf4j.simpleLogger.log.FingertipsServer" to "info",
            "org.slf4j.simpleLogger.log.FingertipsStream" to "info",
            // The API's own JSON converter speaks McpJson for MCP messages, so this is noise.
            "org.slf4j.simpleLogger.log.io.modelcontextprotocol.kotlin.sdk.server.KtorServerHelpers" to
                "error",
            "kotlin-logging.logStartupMessage" to "false",
        )
    defaults.forEach { (key, value) ->
        if (System.getProperty(key) == null) System.setProperty(key, value)
    }
}

private const val USAGE_EXIT = 2
