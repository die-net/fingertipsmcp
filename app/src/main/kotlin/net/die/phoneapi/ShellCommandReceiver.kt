package net.die.phoneapi

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.PairingInfo
import net.die.phoneapi.model.Scope

/**
 * Host-side provisioning over ADB. Protected by `android.permission.DUMP`, which only shell and
 * root hold, so other apps cannot mint tokens.
 *
 * Prefer the host client: `phoneapi pair` for [ACTION_CREATE_TOKEN]. Set or clear the PIN with
 * `PUT|DELETE /v1/device/pin` (admin scope). Pair wireless debugging from the app UI
 * ([ACTION_PAIR_ADB] is what that flow sends).
 *
 * The result data is the pairing JSON for `CREATE_TOKEN`. Omitting `scopes` grants every scope.
 * `SET_PIN` with no `pin` clears it. A bad extra returns result code 1 and a short message; success
 * is result code 0. Neither PIN path returns the PIN.
 */
class ShellCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        // The application builds the graph before any receiver runs, so this is a guard only.
        val graph = PhoneApiApp.graphOrNull()
        if (graph == null) {
            pending.resultCode = 1
            pending.resultData = "PhoneAPI has not started yet"
            pending.finish()
            return
        }
        graph.scope.launch(graph.ioDispatcher) { deliver(graph, intent, pending) }
    }

    @Suppress("TooGenericExceptionCaught") // A bad extra must not crash the app process.
    private suspend fun deliver(graph: AppGraph, intent: Intent, pending: PendingResult) {
        try {
            handle(graph, intent, pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.resultCode = 1
            pending.resultData = e.message ?: "failed"
        } finally {
            pending.finish()
        }
    }

    private suspend fun handle(graph: AppGraph, intent: Intent, pending: PendingResult) {
        when (intent.action) {
            ACTION_CREATE_TOKEN -> createToken(graph, intent, pending)
            ACTION_SET_PIN -> setPin(graph, intent, pending)
            ACTION_PAIR_ADB -> pairAdb(graph, intent, pending)
        }
    }

    private fun createToken(graph: AppGraph, intent: Intent, pending: PendingResult) {
        val name = intent.getStringExtra("name") ?: "adb"
        when (val scopes = parseScopeList(intent.getStringExtra("scopes"))) {
            is Parse.Error -> fail(pending, scopes.message)
            is Parse.Ok -> {
                val created = graph.tokens.create(name, scopes.value)
                val pairing =
                    PairingInfo(
                        host = "127.0.0.1",
                        port = graph.settings.current.port,
                        token = created.token,
                        name = name,
                    )
                pending.resultCode = 0
                pending.resultData = ApiJson.encodeToString(PairingInfo.serializer(), pairing)
            }
        }
    }

    private fun setPin(graph: AppGraph, intent: Intent, pending: PendingResult) {
        graph.pins.write(intent.getStringExtra("pin"))
        pending.resultCode = 0
        pending.resultData = if (graph.pins.isSet) "stored" else "cleared"
    }

    private suspend fun pairAdb(graph: AppGraph, intent: Intent, pending: PendingResult) {
        val code =
            when (val parsed = parsePairingCode(intent.getStringExtra("code"))) {
                is Parse.Error -> return fail(pending, parsed.message)
                is Parse.Ok -> parsed.value
            }
        val port =
            when (val parsed = parsePairingPort(intent.getIntExtra("port", 0))) {
                is Parse.Error -> return fail(pending, parsed.message)
                is Parse.Ok -> parsed.value
            }
        pending.resultCode = 0
        pending.resultData = graph.helperSupervisor.pair(code, port).message
    }

    private fun fail(pending: PendingResult, message: String) {
        pending.resultCode = 1
        pending.resultData = message
    }

    companion object {
        const val ACTION_CREATE_TOKEN = "net.die.phoneapi.CREATE_TOKEN"
        const val ACTION_SET_PIN = "net.die.phoneapi.SET_PIN"
        const val ACTION_PAIR_ADB = "net.die.phoneapi.PAIR_ADB"

        private val ACTIONS = setOf(ACTION_CREATE_TOKEN, ACTION_SET_PIN, ACTION_PAIR_ADB)

        /** The host command that mints a token and prints the pairing JSON. */
        fun createTokenCommand(packageName: String): String =
            "PHONEAPI_PKG=$packageName phoneapi pair --name laptop"
    }
}

internal sealed interface Parse<out T> {
    data class Ok<T>(val value: T) : Parse<T>

    data class Error(val message: String) : Parse<Nothing>
}

/** Null [raw] grants every scope. Blank text and unknown names are errors. */
internal fun parseScopeList(raw: String?): Parse<Set<Scope>> {
    if (raw == null) return Parse.Ok(Scope.entries.toSet())
    val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return Parse.Error("scopes must name at least one scope")
    val scopes = names.map { name ->
        Scope.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Parse.Error("unknown scope $name")
    }
    return Parse.Ok(scopes.toSet())
}

internal fun parsePairingCode(raw: String?): Parse<String> {
    val code = raw?.trim().orEmpty()
    if (!PAIRING_CODE.matches(code)) return Parse.Error("code must be 6 digits")
    return Parse.Ok(code)
}

/** Zero means the port extra was omitted. */
internal fun parsePairingPort(port: Int): Parse<Int?> {
    if (port == 0) return Parse.Ok(null)
    if (port !in 1..65_535) return Parse.Error("port must be 1..65535")
    return Parse.Ok(port)
}

private val PAIRING_CODE = Regex("""\d{6}""")
