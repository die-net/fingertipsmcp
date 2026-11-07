package net.die.phoneapi.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.model.ApiException

/** Where `phoneApiModule` keeps the sessions, so REST calls can find theirs. */
internal val SessionsKey = AttributeKey<McpSessions>("PhoneApiSessions")

/** The header that names a session, on REST as on MCP. */
const val SESSION_HEADER = "Mcp-Session-Id"

const val NOTE_HEADER = "X-PhoneAPI-Note"

/**
 * The session this REST call names in `Mcp-Session-Id`. Fails with 428 `session_required` without
 * one, and with 404 `session_not_found` for one that has ended or belongs to another token, which
 * read the same so that an id says nothing about other clients.
 */
internal fun ApplicationCall.session(): Holder.Session {
    val id =
        request.header(SESSION_HEADER)
            ?: throw ApiException(
                428,
                "session_required",
                "Create a session with POST /v1/sessions and send its id in $SESSION_HEADER.",
            )
    return application.attributes[SessionsKey].forRest(id, token.id)
        ?: throw ApiException.sessionNotFound(
            "No such session. It may have ended or idled out; start one with POST /v1/sessions."
        )
}

/**
 * Runs a REST call as the session it names. When [needed], the call holds the screen, waiting for
 * it first, and the session keeps it afterwards, as an MCP session does; Chrome shows none of
 * another session's tabs meanwhile. A call that could make a page launch an app, [launching], is
 * watched for that while the session lacks the screen. A call that sends an intent for a URL,
 * [claimUrl], gives the caller the tab Chrome opens for it. A call that needs the screen only while
 * it runs, [giveBack], lets it go afterwards if the session did not have it before. Notes for the
 * session go in `X-PhoneAPI-Note` headers.
 */
internal suspend fun <T> withScreen(
    call: ApplicationCall,
    services: ServerServices,
    needed: Boolean = true,
    launching: Boolean = false,
    claimUrl: String? = null,
    giveBack: Boolean = false,
    block: suspend (Holder.Session) -> T,
): T {
    val holder = call.session()
    if (!needed) {
        val value =
            if (launching) services.foreground.watch(holder) { block(holder) } else block(holder)
        services.lease.takeNotes(holder).forEach { call.response.headers.append(NOTE_HEADER, it) }
        return value
    }
    val current = services.lease.state.value.holder as? Holder.Session
    if (current != null) {
        call.application.attributes[SessionsKey].conflict(holder.id, current.id)?.let {
            throw ApiException(409, "session_conflict", it)
        }
    }
    val screened =
        services.lease.withScreen(holder, giveBack) {
            services.tabs.screenCall(holder, claimUrl) { block(holder) }
        }
    screened.notes.forEach { call.response.headers.append(NOTE_HEADER, it) }
    return screened.value
}

/** The browser as [holder] sees it: its own tabs and unowned ones. */
internal fun ApplicationCall.browser(
    services: ServerServices,
    holder: Holder.Session,
): BrowserService = services.tabs.browserFor(holder) { related(holder, it) }

/** Waits as [holder] may run them: on its own tabs and unowned ones. */
internal fun ApplicationCall.waits(services: ServerServices, holder: Holder.Session): WaitService =
    services.tabs.waitsFor(holder, services.waits) { related(holder, it) }

private fun ApplicationCall.related(holder: Holder.Session, other: Holder.Session): Boolean =
    application.attributes[SessionsKey].controlFor(holder.id)?.related(other) == true
