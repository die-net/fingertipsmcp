package net.die.phoneapi.server

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.mcp.installPhoneMcp
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.server.routes.appRoutes
import net.die.phoneapi.server.routes.browserRoutes
import net.die.phoneapi.server.routes.deviceRoutes
import net.die.phoneapi.server.routes.eventRoutes
import net.die.phoneapi.server.routes.inputRoutes
import net.die.phoneapi.server.routes.openApiRoutes
import net.die.phoneapi.server.routes.sessionRoutes
import net.die.phoneapi.server.routes.streamRoutes
import net.die.phoneapi.server.routes.tokenRoutes
import net.die.phoneapi.server.routes.uiRoutes
import net.die.phoneapi.server.routes.waitRoutes

/** HTTP API on an abstract local socket, reached from the host with `adb forward`. */
class ApiServer(
    private val services: ServerServices,
    private val socketName: String,
) {
    private var server: EmbeddedServer<AbstractHttpEngine, AbstractHttpEngine.Configuration>? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val env = applicationEnvironment {}
        server =
            embeddedServer(
                    AbstractHttpEngine,
                    env,
                    configure = {
                        socketName = this@ApiServer.socketName
                        dispatcher = services.ioDispatcher
                    },
                    module = { phoneApiModule(services) },
                )
                .also { it.start(wait = false) }
        Log.i(TAG, "Listening on abstract socket $socketName")
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }
}

fun Application.phoneApiModule(
    services: ServerServices,
    sessions: McpSessions =
        McpSessions(
                services.tokens,
                scope = this,
                onEnd = {
                    services.lease.forget(Holder.Session(it))
                    services.tabs.end(Holder.Session(it))
                    services.foreground.forget(Holder.Session(it))
                },
                holdsScreen = { services.lease.holds(Holder.Session(it)) },
                tabCount = { services.tabs.count(Holder.Session(it)) },
                tookScreen = { services.foreground.count(Holder.Session(it)) },
            )
            .also { it.start() },
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
            Log.e(TAG, "Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", e.message))
        }
    }
    install(BearerAuth) { tokens = services.tokens }
    attributes.put(SessionsKey, sessions)
    services.tabs.sessionsFrom { sessions.isLive(it.id) }
    routing {
        deviceRoutes(services)
        openApiRoutes()
        tokenRoutes(services)
        eventRoutes(services)
        uiRoutes(services)
        inputRoutes(services)
        appRoutes(services)
        browserRoutes(services)
        waitRoutes(services)
        streamRoutes(services)
        sessionRoutes(services, sessions)
    }
    installPhoneMcp(services, sessions)
}

private fun Throwable.rootMessage(): String? = generateSequence(this) { it.cause }.last().message

private const val TAG = "PhoneApiServer"
