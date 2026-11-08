package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.fingertipsOpenApi
import com.fingertipsmcp.server.scoped
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /v1/openapi.json` — the OpenAPI document generated from the api-model types. */
fun Route.openApiRoutes() {
    scoped(Scope.OBSERVE) {
        get("/v1/openapi.json") { call.respond(fingertipsOpenApi()) }
    }
}
