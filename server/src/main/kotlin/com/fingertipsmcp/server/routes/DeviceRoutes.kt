package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PinStatus
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SetPinRequest
import com.fingertipsmcp.model.UnlockRequest
import com.fingertipsmcp.server.PhoneServices
import com.fingertipsmcp.server.SESSION_HEADER
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.SessionsKey
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.server.token
import com.fingertipsmcp.server.withScreen
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

/** `GET /v1/device`. */
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
}

/**
 * `POST /v1/device/wake|unlock|lock|orientation`, and `PUT|DELETE /v1/device/pin`: the phone's
 * power, orientation, and stored PIN.
 */
fun Route.powerRoutes(services: ServerServices, phone: PhoneServices) {
    // Browser touch input needs an awake, unlocked phone, so a browser token may get one.
    scoped(Scope.CONTROL, Scope.BROWSER) {
        post("/v1/device/wake") {
            call.respond(withScreen(call, services) { phone.power.wake() })
        }
        post("/v1/device/unlock") {
            val request = bodyOrDefault(call, UnlockRequest())
            call.respond(withScreen(call, services) { phone.power.unlock(request) })
        }
    }
    scoped(Scope.CONTROL) {
        post("/v1/device/lock") {
            call.respond(withScreen(call, services) { phone.power.lock() })
        }
        post("/v1/device/orientation") {
            val request = call.receive<OrientationRequest>()
            call.respond(withScreen(call, services) { phone.power.orientation(request) })
        }
    }
    scoped(Scope.ADMIN) {
        put("/v1/device/pin") {
            phone.writePin(call.receive<SetPinRequest>().pin)
            call.respond(PinStatus(stored = true))
        }
        delete("/v1/device/pin") {
            phone.writePin(null)
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
