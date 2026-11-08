package com.fingertipsmcp.server

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.mcp.McpSessions
import com.fingertipsmcp.mcp.installPhoneMcp
import com.fingertipsmcp.model.ApiError
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.server.routes.appRoutes
import com.fingertipsmcp.server.routes.browserRoutes
import com.fingertipsmcp.server.routes.deviceRoutes
import com.fingertipsmcp.server.routes.eventRoutes
import com.fingertipsmcp.server.routes.inputRoutes
import com.fingertipsmcp.server.routes.openApiRoutes
import com.fingertipsmcp.server.routes.powerRoutes
import com.fingertipsmcp.server.routes.screenshotRoutes
import com.fingertipsmcp.server.routes.sessionRoutes
import com.fingertipsmcp.server.routes.streamRoutes
import com.fingertipsmcp.server.routes.tokenRoutes
import com.fingertipsmcp.server.routes.uiRoutes
import com.fingertipsmcp.server.routes.waitRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * The HTTP API and MCP for the services given. FingertipsMCP for Android serves it on an abstract
 * socket. FingertipsMCP for Chrome serves it on loopback TCP, which any web page can aim a request
 * at, so it sets [mcpDnsRebindingProtection], and `/mcp` takes only a loopback `Host`, and no
 * `Origin`.
 */
fun Application.fingertipsModule(
    services: ServerServices,
    sessions: McpSessions = mcpSessions(services, this),
    mcpDnsRebindingProtection: Boolean = false,
) {
    install(ContentNegotiation) { register(ContentType.Application.Json, PhoneJsonConverter) }
    install(WebSockets) { pingPeriod = 15.seconds }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), ApiError(e.error, e.message, e.state))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.rootMessage()))
        }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.message))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", e.message))
        }
    }
    install(BearerAuth) { tokens = services.tokens }
    attributes.put(SessionsKey, sessions)
    services.tabs.sessionsFrom { sessions.isLive(it.id) }
    routing {
        val phone = services.phone
        val streams = services.streams
        val screenshots = services.screenshots
        deviceRoutes(services)
        openApiRoutes(services)
        tokenRoutes(services)
        eventRoutes(services)
        if (phone != null) {
            powerRoutes(services, phone)
            uiRoutes(services, phone)
            inputRoutes(services, phone)
            appRoutes(services, phone)
        }
        if (screenshots != null) screenshotRoutes(services, screenshots)
        browserRoutes(services)
        waitRoutes(services)
        if (phone != null && streams != null) streamRoutes(services, phone, streams)
        sessionRoutes(services, sessions)
    }
    installPhoneMcp(services, sessions, mcpDnsRebindingProtection)
}

private fun Throwable.rootMessage(): String? = generateSequence(this) { it.cause }.last().message

private val log = LoggerFactory.getLogger("FingertipsServer")

/**
 * The MCP and REST sessions for [services], started: a session that ends lets go of the screen and
 * closes its tabs. One server, HTTP or stdio, shares one set.
 */
fun mcpSessions(services: ServerServices, scope: CoroutineScope): McpSessions =
    McpSessions(
            services.tokens,
            scope = scope,
            onEnd = {
                services.lease.forget(Holder.Session(it))
                services.tabs.end(Holder.Session(it))
                services.foreground.forget(Holder.Session(it))
            },
            holdsScreen = { services.lease.holds(Holder.Session(it)) },
            tabCount = { services.tabs.count(Holder.Session(it)) },
            tookScreen = { services.foreground.count(Holder.Session(it)) },
        )
        .also { it.start() }
