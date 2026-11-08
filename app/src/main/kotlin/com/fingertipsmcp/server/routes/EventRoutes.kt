package com.fingertipsmcp.server.routes

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.Event
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.parseEventQuery
import com.fingertipsmcp.server.scoped
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.serializer

/**
 * `WS /v1/events?types=a,b` streams [Event]s as JSON text frames. `types` filters by exact type or
 * prefix ending in `.` (e.g. `ime.`).
 *
 * Logcat is not on the shared bus. It is streamed on this connection only when `types` contains
 * `logcat` exactly, so a client that omits `types` does not receive it. Optional `logcatTag`
 * (letters, digits, `.`, `_`, `-`) and `logcatLevel` (`V`, `D`, `I`, `W`, `E`, or `F`, default `I`)
 * narrow that stream. A bad value is rejected with 400 before the upgrade. If the helper is not
 * running, logcat frames are skipped and the other types keep flowing.
 */
fun Route.eventRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        install(RejectBadLogcatQuery)
        webSocket(EVENTS) {
            val params = call.request.queryParameters
            val query = parseEventQuery(params["types"], params["logcatTag"], params["logcatLevel"])
            val bus = services.events.filter { accepts(it, query.types) }
            val source = query.logcat?.let { merge(bus, services.logcat.lines(it)) } ?: bus
            source.collect { event ->
                send(Frame.Text(ApiJson.encodeToString(serializer<Event>(), event)))
            }
        }
    }
}

internal fun matchesType(type: String, filter: String): Boolean =
    if (filter.endsWith('.')) type.startsWith(filter) else type == filter

private fun accepts(event: Event, types: List<String>?): Boolean {
    if (event.type == EventTypes.LOGCAT) return false
    return types == null || types.any { matchesType(event.type, it) }
}

private const val EVENTS = "/v1/events"

private val RejectBadLogcatQuery =
    createRouteScopedPlugin("RejectBadLogcatQuery") {
        onCall { call ->
            if (call.isHandled) return@onCall
            if (call.request.path() != EVENTS) return@onCall
            val params = call.request.queryParameters
            parseEventQuery(params["types"], params["logcatTag"], params["logcatLevel"])
        }
    }
