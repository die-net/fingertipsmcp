package com.fingertipsmcp.model

import com.fingertipsmcp.model.schema.Doc
import kotlinx.serialization.Serializable

/** One MCP session, as `GET /v1/sessions` lists it. The session id itself is never shown. */
@Serializable
public data class SessionInfo(
    @Doc("A short hash of the session id, for DELETE /v1/sessions/{hash}.") val hash: String,
    @Doc("The name of the token that started the session.") val token: String,
    @Doc("Milliseconds since the session started.") val ageMs: Long,
    @Doc("Milliseconds since its last request.") val idleMs: Long,
    @Doc("Whether it holds the screen.") val holdsScreen: Boolean = false,
    @Doc("Browser tabs it owns, opened or claimed.") val tabs: Int = 0,
    @Doc(
        "Times a call from it brought an app forward while it did not hold the screen, failing with took_screen."
    )
    val tookScreen: Int = 0,
    @Doc("For a session made with session_start, the hash of the session that started it.")
    val parent: String? = null,
)

/** A session from `POST /v1/sessions`. */
@Serializable
public data class SessionStarted(
    @Doc(
        "Send this in the Mcp-Session-Id header on every REST call that acts on the phone or its tabs. It also names the session on MCP."
    )
    val id: String,
    @Doc("The session ends after this long without a request.") val idleMs: Long,
)

/** The body of `POST /v1/screen/release`, and the arguments of `screen_release`. */
@Serializable
public data class ScreenReleaseRequest(
    @Doc(
        "Clean up the screen for the next client: hide the keyboard and go home, and lock the phone if no one is waiting and it was locked before. false leaves the screen as it is instead, such as a captcha you cannot solve for a person to finish in the viewer, and keeps your tab that Chrome is showing after your session ends, as an unowned tab a later session can claim by using it."
    )
    val cleanup: Boolean = true
)
