package com.fingertipsmcp.chrome

import com.fingertipsmcp.model.Scope

/** What the command line asked for. */
sealed interface Command {
    data class Serve(val options: ServeOptions) : Command

    data class TokenCreate(val name: String, val scopes: Set<Scope>, val writePairing: Boolean) :
        Command

    data object TokenList : Command

    data class TokenRevoke(val id: String) : Command

    data object Help : Command

    data object Version : Command
}

/**
 * The command line's serve options. A listen option left out is null, and `settings.json` fills it.
 */
data class ServeOptions(
    val host: String? = null,
    val port: Int? = null,
    val unixSocket: String? = null,
    val stdio: Boolean = false,
    val chrome: String? = null,
    val profile: String? = null,
    val headless: Boolean = false,
    val attach: String? = null,
    val chromeArgs: List<String> = emptyList(),
    /** Leave Chrome's automation marker on: `navigator.webdriver` true. */
    val showAutomation: Boolean = false,
)

/** Where `serve` listens: TCP at [Tcp.host] and [Tcp.port], or the Unix socket at [Unix.path]. */
sealed interface Listen {
    data class Tcp(val host: String, val port: Int) : Listen

    data class Unix(val path: String) : Listen
}

class UsageException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

val USAGE: String =
    listOf(
            "Usage:",
            "  fingertips-chrome [serve] [options]      Start Chrome and serve the HTTP API and MCP",
            "  fingertips-chrome --stdio [options]      Serve MCP on stdin and stdout instead of HTTP",
            "  fingertips-chrome token create [--name NAME] [--scopes observe,control,browser] [--no-pairing]",
            "  fingertips-chrome token list",
            "  fingertips-chrome token revoke ID",
            "  fingertips-chrome --version | --help",
            "",
            "Options:",
            "  --host ADDR          Listen address, default 127.0.0.1. Keep it on loopback.",
            "  --port N             TCP port. Default: one picked at random on first run.",
            "  --unix PATH          Listen on a Unix socket instead of TCP. Not on Windows.",
            "                       --host, --port, and --unix are kept in settings.json for the",
            "                       next run, and token create writes pairing.json from them.",
            "  --stdio              MCP over stdio for one client. No HTTP listener.",
            "  --chrome PATH        Browser to run. Default: Google Chrome where it is installed,",
            "                       or FINGERTIPS_CHROME. Chromium is used, with a warning, when",
            "                       Chrome is missing.",
            "  --profile DIR        Chrome user data directory, kept between runs.",
            "  --headless           Run Chrome with --headless=new. Easier to detect than a window.",
            "  --attach PORT|URL    Development: use a Chrome already started with",
            "                       --remote-debugging-port instead of launching one.",
            "  --chrome-arg ARG     Pass one more flag to Chrome. Repeatable.",
            "  --show-automation    Let navigator.webdriver be true, as Chrome sets it whenever",
            "                       DevTools is on its command line. By default fingertips-chrome passes",
            "                       --disable-blink-features=AutomationControlled to keep it false.",
            "",
            "Files live in \$XDG_CONFIG_HOME/fingertips-chrome (~/.config/fingertips-chrome), or in",
            "%LOCALAPPDATA%\\fingertips-chrome on Windows: settings.json, tokens.json, and pairing.json,",
            "which `fingertips-chrome token create` writes for clients.",
        )
        .joinToString("\n")

fun parseCommand(args: List<String>): Command {
    if (args.isEmpty()) return Command.Serve(ServeOptions())
    return when (args.first()) {
        "--help",
        "-h",
        "help" -> Command.Help
        "--version",
        "version" -> Command.Version
        "token" -> parseToken(args.drop(1))
        "serve" -> Command.Serve(parseServe(args.drop(1)))
        else -> Command.Serve(parseServe(args))
    }
}

private fun parseToken(args: List<String>): Command {
    val verb = args.firstOrNull() ?: throw UsageException("token needs create, list, or revoke")
    val rest = args.drop(1)
    return when (verb) {
        "create" -> parseCreate(rest)
        "list" -> {
            if (rest.isNotEmpty()) throw UsageException("token list takes no arguments")
            Command.TokenList
        }
        "revoke" -> {
            val id = rest.singleOrNull() ?: throw UsageException("token revoke needs one token id")
            Command.TokenRevoke(id)
        }
        else -> throw UsageException("Unknown token command '$verb'")
    }
}

private fun parseCreate(args: List<String>): Command.TokenCreate {
    var name = "fingertips-chrome"
    var scopes = DEFAULT_SCOPES
    var pairing = true
    val queue = ArrayDeque(args)
    while (queue.isNotEmpty()) {
        when (val arg = queue.removeFirst()) {
            "--name" -> name = queue.value(arg)
            "--scopes" -> scopes = parseScopes(queue.value(arg))
            "--no-pairing" -> pairing = false
            else -> throw UsageException("Unknown option '$arg'")
        }
    }
    if (name.isBlank()) throw UsageException("--name must not be blank")
    return Command.TokenCreate(name, scopes, pairing)
}

private fun parseServe(args: List<String>): ServeOptions {
    var options = ServeOptions()
    val queue = ArrayDeque(args)
    while (queue.isNotEmpty()) {
        val arg = queue.removeFirst()
        options =
            listenOption(options, arg, queue)
                ?: chromeOption(options, arg, queue)
                ?: throw UsageException("Unknown option '$arg'")
    }
    refuseIgnored(options)
    return options
}

/** Refuses options that another one given with them would make do nothing. */
private fun refuseIgnored(options: ServeOptions) {
    val tcp = listOfNotNull(options.host?.let { "--host" }, options.port?.let { "--port" })
    if (options.stdio) {
        val listen = tcp + listOfNotNull(options.unixSocket?.let { "--unix" })
        if (listen.isNotEmpty()) {
            throw UsageException(
                "--stdio does not listen. Leave out ${listen.joinToString(" and ")}."
            )
        }
    }
    if (options.unixSocket != null && tcp.isNotEmpty()) {
        throw UsageException("Pass ${tcp.joinToString(" and ")} or --unix, not both")
    }
    if (options.attach != null) {
        val launch =
            listOfNotNull(
                options.chrome?.let { "--chrome" },
                options.profile?.let { "--profile" },
                "--headless".takeIf { options.headless },
                "--chrome-arg".takeIf { options.chromeArgs.isNotEmpty() },
                "--show-automation".takeIf { options.showAutomation },
            )
        if (launch.isNotEmpty()) {
            throw UsageException(
                "--attach uses a Chrome that is already running. Leave out " +
                    "${launch.joinToString(", ")}."
            )
        }
    }
}

/** Where and how to serve, or null when [arg] is not one of those options. */
private fun listenOption(
    options: ServeOptions,
    arg: String,
    queue: ArrayDeque<String>,
): ServeOptions? =
    when (arg) {
        "--host" -> options.copy(host = queue.value(arg))
        "--port" -> options.copy(port = port(queue.value(arg)))
        "--unix" -> options.copy(unixSocket = queue.value(arg))
        "--stdio" -> options.copy(stdio = true)
        else -> null
    }

/** Which browser to run and how, or null when [arg] is not one of those options. */
private fun chromeOption(
    options: ServeOptions,
    arg: String,
    queue: ArrayDeque<String>,
): ServeOptions? =
    when (arg) {
        "--chrome" -> options.copy(chrome = queue.value(arg))
        "--profile" -> options.copy(profile = queue.value(arg))
        "--headless" -> options.copy(headless = true)
        "--attach" -> options.copy(attach = queue.value(arg))
        "--chrome-arg" -> options.copy(chromeArgs = options.chromeArgs + queue.value(arg))
        "--show-automation" -> options.copy(showAutomation = true)
        else -> null
    }

private fun ArrayDeque<String>.value(option: String): String =
    removeFirstOrNull() ?: throw UsageException("$option needs a value")

private fun port(value: String): Int =
    value.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        ?: throw UsageException("--port must be 1..$MAX_PORT")

internal fun parseScopes(value: String): Set<Scope> {
    val scopes =
        value
            .split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { name ->
                Scope.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: throw UsageException(
                        "Unknown scope '$name'. Scopes: " +
                            Scope.entries.joinToString(", ") { it.name.lowercase() }
                    )
            }
            .toSet()
    if (scopes.isEmpty()) throw UsageException("--scopes needs at least one scope")
    return scopes
}

/**
 * What a client needs by default: read, act, and drive the browser, but not manage tokens. A
 * `--stdio` client gets these too, since the MCP client that started the process is paired by
 * starting it.
 */
internal val DEFAULT_SCOPES = setOf(Scope.OBSERVE, Scope.CONTROL, Scope.BROWSER)

private const val MAX_PORT = 65_535
