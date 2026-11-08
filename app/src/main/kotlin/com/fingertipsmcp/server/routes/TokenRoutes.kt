package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.CreateTokenRequest
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.UpdateTokenRequest
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.scoped
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

fun Route.tokenRoutes(services: ServerServices) {
    scoped(Scope.ADMIN) {
        get("/v1/tokens") { call.respond(services.tokens.list()) }
        post("/v1/tokens") {
            val req = call.receive<CreateTokenRequest>()
            requireScopes(req.scopes)
            call.respond(HttpStatusCode.Created, services.tokens.create(req.name, req.scopes))
        }
        patch("/v1/tokens/{id}") {
            val id = call.parameters["id"] ?: throw ApiException.badRequest("id required")
            val req = call.receive<UpdateTokenRequest>()
            requireScopes(req.scopes)
            val info =
                services.tokens.setScopes(id, req.scopes)
                    ?: throw ApiException.notFound("token $id")
            call.respond(info)
        }
        delete("/v1/tokens/{id}") {
            val id = call.parameters["id"] ?: throw ApiException.badRequest("id required")
            if (!services.tokens.revoke(id)) throw ApiException.notFound("token $id")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun requireScopes(scopes: Set<Scope>) {
    if (scopes.isEmpty()) throw ApiException.badRequest("At least one scope is required")
}
