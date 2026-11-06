package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.Doc

/*
 * Arguments an MCP tool takes beside a REST request body. A tool's arguments are one JSON object
 * made of several of these types and the request type, each decoding the fields it names, so the
 * request's fields and docs are written once, in the request type.
 */

/** The browser tab a tool acts on, which REST names in its path. */
@Serializable
public data class TargetArg(
    @Doc("Browser target id, from browser_targets or browser_open.") val target: String
)

/** The UI node a tool acts on, which REST names in its path. */
@Serializable
public data class NodeRefArg(
    @Doc("Node ref from ui_snapshot or ui_find, such as e12.") val ref: String
)

/** The app a tool acts on, which REST names in its path. */
@Serializable public data class PackageArg(@SerialName("package") val packageName: String)

/** The session a tool call is for, which REST names in the `Mcp-Session-Id` header. */
@Serializable
public data class SessionArg(
    @Doc(
        "Id from session_start, if this call is for a subagent or parallel task. Use the same id on every call for that task; leave it out for your own work."
    )
    val session: String? = null
)
