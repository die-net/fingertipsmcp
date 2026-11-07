package net.die.phoneapi.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped
import net.die.phoneapi.server.withScreen

/**
 * `GET /v1/apps?launchable=`, `POST /v1/apps/{pkg}/launch|stop|clear` and `POST /v1/intents`.
 * Listing defaults to apps with a launcher activity; pass `launchable=false` for every package.
 */
fun Route.appRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/apps") {
            val launchableOnly = call.request.queryParameters.flag("launchable", default = true)
            call.respond(services.apps.list(launchableOnly))
        }
    }
    scoped(Scope.CONTROL) {
        post("/v1/apps/{pkg}/launch") {
            val request = bodyOrDefault(call, LaunchRequest())
            call.respond(
                withScreen(call, services) { services.apps.launch(call.packageName(), request) }
            )
        }
        post("/v1/apps/{pkg}/stop") {
            call.respond(withScreen(call, services) { services.apps.stop(call.packageName()) })
        }
        post("/v1/apps/{pkg}/clear") {
            call.respond(withScreen(call, services) { services.apps.clear(call.packageName()) })
        }
        post("/v1/intents") {
            val request = call.receive<IntentRequest>()
            call.respond(
                withScreen(call, services, claimUrl = request.data) {
                    services.apps.intent(request)
                }
            )
        }
    }
}

private fun ApplicationCall.packageName(): String =
    parameters["pkg"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.badRequest("A package name is required")
