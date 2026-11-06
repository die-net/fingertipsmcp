package net.die.phoneapi.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PinStatus
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SetPinRequest
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.server.SESSION_HEADER
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.SessionsKey
import net.die.phoneapi.server.scoped
import net.die.phoneapi.server.token
import net.die.phoneapi.server.withScreen

/**
 * `GET /v1/device`, `POST /v1/device/wake|unlock|lock|orientation`, and `PUT|DELETE
 * /v1/device/pin`.
 */
fun Route.deviceRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/device") {
            val sessions = call.application.attributes[SessionsKey]
            // Only the caller's own token's session counts; another token's id names no one.
            val caller =
                call.request.header(SESSION_HEADER)?.let { sessions.forRest(it, call.token.id) }?.id
            call.respond(
                sessions.withScreenHolder(
                    services.device.info(),
                    services.lease.state.value,
                    caller,
                )
            )
        }
    }
    // Browser touch input needs an awake, unlocked phone, so a browser token may get one.
    scoped(Scope.CONTROL, Scope.BROWSER) {
        post("/v1/device/wake") {
            call.respond(withScreen(call, services) { services.power.wake() })
        }
        post("/v1/device/unlock") {
            val request = bodyOrDefault(call, UnlockRequest())
            call.respond(withScreen(call, services) { services.power.unlock(request) })
        }
    }
    scoped(Scope.CONTROL) {
        post("/v1/device/lock") {
            call.respond(withScreen(call, services) { services.power.lock() })
        }
        post("/v1/device/orientation") {
            val request = call.receive<OrientationRequest>()
            call.respond(withScreen(call, services) { services.power.orientation(request) })
        }
    }
    scoped(Scope.ADMIN) {
        put("/v1/device/pin") {
            services.writePin(call.receive<SetPinRequest>().pin)
            call.respond(PinStatus(stored = true))
        }
        delete("/v1/device/pin") {
            services.writePin(null)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The JSON body of [call], or [default] when the client sent none, so endpoints whose options all
 * have defaults can be called with an empty `POST`.
 */
internal suspend inline fun <reified T : Any> bodyOrDefault(call: ApplicationCall, default: T): T {
    val declared = call.request.headers[HttpHeaders.ContentType]
    return if (declared == null || call.request.contentLength() == 0L) default else call.receive()
}
