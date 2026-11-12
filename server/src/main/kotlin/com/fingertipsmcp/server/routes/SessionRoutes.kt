package com.fingertipsmcp.server.routes

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.mcp.McpSessions
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.ScreenReleaseRequest
import com.fingertipsmcp.model.SessionStarted
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.anyToken
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.server.session
import com.fingertipsmcp.server.token
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Sessions on REST: starting one, ending it, and letting its screen go. The admin routes list
 * sessions and end a stuck one by the hash of its id.
 */
fun Route.sessionRoutes(services: ServerServices, sessions: McpSessions) {
    // Any token can start, end, and release its own session, whatever its scopes.
    anyToken {
        post("/v1/sessions") {
            val id = sessions.startRest(call.token.id, call.token.name)
            call.respond(HttpStatusCode.Created, SessionStarted(id, McpSessions.IDLE_MS))
        }
        delete("/v1/sessions/current") {
            sessions.end(call.session().id, "ended by its client")
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/screen/release") {
            val request = bodyOrDefault(call, ScreenReleaseRequest())
            val message = releaseScreen(services, call.session(), request)
            call.respond(ActionResult(ok = true, message = message))
        }
    }
    scoped(Scope.ADMIN) {
        get("/v1/sessions") {
            services.tabs.forgetClosed()
            call.respond(sessions.list())
        }
        delete("/v1/sessions/{hash}") {
            val hash = call.parameters["hash"] ?: throw ApiException.badRequest("hash required")
            if (!sessions.endByHash(hash)) throw ApiException.notFound("session $hash")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Lets [holder]'s screen go, for `POST /v1/screen/release` and `screen_release`. Without cleanup,
 * the screen stays as it is, such as for a person in the viewer, and the session's tab on screen
 * outlives it.
 */
internal suspend fun releaseScreen(
    services: ServerServices,
    holder: Holder.Session,
    request: ScreenReleaseRequest,
): String {
    val held = services.lease.holds(holder)
    if (request.cleanup) {
        services.lease.release(holder)
        return if (held) {
            "Released the screen. ${services.product.releaseCleanup} Your tabs stay yours, and " +
                "your next call that needs the screen takes it again, waiting if someone else " +
                "has it."
        } else {
            "This session did not hold the screen."
        }
    }
    val kept =
        if (services.tabs.keepShowing(holder) != null) {
            "Your tab on screen stays open after your session ends, as an unowned tab a later " +
                "session can claim by using it. Your other tabs close as usual."
        } else {
            "Chrome is not in front, or you have no tab, so none is kept, and your tabs close " +
                "when your session ends as usual. For a person to finish in a page, bring its " +
                "tab on screen with show true first (on a phone, unlock it too), then release " +
                "again."
        }
    services.lease.releaseAsIs(holder)
    return if (held) {
        "Released the screen as it is, with no cleanup. $kept"
    } else {
        "This session did not hold the screen, so a person sees whatever is on it now. $kept"
    }
}
