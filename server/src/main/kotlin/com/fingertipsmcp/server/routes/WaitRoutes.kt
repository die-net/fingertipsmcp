package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.server.token
import com.fingertipsmcp.server.waits
import com.fingertipsmcp.server.withScreen
import com.fingertipsmcp.wait.requireWaitAccess
import com.fingertipsmcp.wait.usesScreen
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /v1/wait` blocks until the conditions hold, and can return a fresh snapshot with the result
 * so one call replaces a wait followed by a snapshot. The route takes observe or browser, and
 * [requireWaitAccess] checks which of them the request's conditions need.
 */
fun Route.waitRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE, Scope.BROWSER) {
        post("/v1/wait") {
            val request = call.receive<WaitRequest>()
            val scopes = call.token.scopes
            requireWaitAccess(request, scopes)
            val screen = request.snapshot || (request.all + request.any).any(::usesScreen)
            call.respond(
                withScreen(call, services, screen) {
                    call.waits(services, it).wait(request, scopes)
                }
            )
        }
    }
}
