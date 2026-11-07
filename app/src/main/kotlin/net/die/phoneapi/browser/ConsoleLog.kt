package net.die.phoneapi.browser

import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.die.phoneapi.model.ConsoleEntry
import net.die.phoneapi.model.ConsoleResult

/**
 * Collects a page's console and browser log entries for [timeoutMs], skipping the replayed ones.
 * Enabling the domains replays Chrome's buffer before the reply, so the entries kept are those the
 * session read after Log.enable's reply, by their place in the session's events.
 */
internal suspend fun collectLogs(session: CdpSession, timeoutMs: Long): ConsoleResult {
    val entries = Collections.synchronizedList(mutableListOf<Pair<Long, ConsoleEntry>>())
    session
        .listen { event -> logEntry(event)?.let { entry -> entries += event.seq to entry } }
        .use { _ ->
            session.call("Runtime.enable")
            val replayed = session.callCounted("Log.enable").eventsBefore
            delay(timeoutMs)
            val kept = synchronized(entries) { entries.filter { it.first >= replayed } }
            return ConsoleResult(kept.map { it.second })
        }
}

private fun logEntry(event: CdpEvent): ConsoleEntry? =
    when (event.method) {
        "Runtime.consoleAPICalled" -> {
            val call = event.params.decodeCdp<ConsoleCall>()
            call?.let { consoleText(it.args) }?.let { ConsoleEntry(call.type ?: "log", it) }
        }
        "Log.entryAdded" -> {
            val entry = event.params.decodeCdp<LogAdded>()?.entry
            // console.log also arrives as Runtime.consoleAPICalled.
            entry
                ?.takeIf { it.source != "console-api" }
                ?.text
                ?.let { ConsoleEntry(entry.level ?: "info", it) }
        }
        else -> null
    }

private fun consoleText(args: List<ConsoleArg>): String? {
    val text = args.mapNotNull(::argText).joinToString(" ").trim()
    return text.ifEmpty { null }
}

private fun argText(arg: ConsoleArg): String? {
    val value = arg.value
    if (value is JsonPrimitive) return value.contentOrNull
    return arg.unserializableValue ?: arg.description
}

@Serializable private data class LogAdded(val entry: LogLine? = null)

@Serializable
private data class LogLine(
    val text: String? = null,
    val level: String? = null,
    val source: String? = null,
)

@Serializable
private data class ConsoleCall(
    val type: String? = null,
    val args: List<ConsoleArg> = emptyList(),
)

@Serializable
private data class ConsoleArg(
    val value: JsonElement? = null,
    val description: String? = null,
    val unserializableValue: String? = null,
)
