package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.openApi
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.scoped
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/openapi.json`: the OpenAPI document for the groups this server has, from the api-model
 * types.
 */
fun Route.openApiRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/openapi.json") {
            call.respond(openApi(services.product.title, services.groups))
        }
    }
}
