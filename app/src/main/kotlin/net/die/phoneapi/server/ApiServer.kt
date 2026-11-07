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
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * HTTP API on an abstract local socket, reached from the host with `adb forward`.
 * [ServerController] owns its lifecycle and runs [start] and [stop] one at a time.
 */
class ApiServer(
    private val socketName: String,
    private val dispatcher: CoroutineDispatcher,
    /** Sets up the application, which [phoneApiModule] does. */
    private val module: Application.() -> Unit,
) {
    private var server: EmbeddedServer<AbstractHttpEngine, AbstractHttpEngine.Configuration>? = null

    /** Opens the listener, or does nothing when it is open. Throws when it cannot bind. */
    fun start() {
        if (server != null) return
        val starting =
            embeddedServer(
                AbstractHttpEngine,
                applicationEnvironment {},
                configure = {
                    socketName = this@ApiServer.socketName
                    dispatcher = this@ApiServer.dispatcher
                },
                module = module,
            )
        try {
            starting.start(wait = false)
        } catch (e: IOException) {
            // Ktor creates the application before the engine binds, and leaves it running if the
            // bind fails. Each failed start would add another, with its own session sweep.
            starting.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            throw e
        }
        server = starting
        Log.i(TAG, "Listening on abstract socket $socketName")
    }

    /**
     * Returns once the abstract name is free, and throws when it may still be bound. The
     * connections and the application end either way.
     */
    suspend fun stop() {
        val running = server ?: return
        server = null
        try {
            running.engine.closeListener()
        } finally {
            running.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
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
