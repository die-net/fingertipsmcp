package com.fingertipsmcp.model

import com.fingertipsmcp.model.schema.Doc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/**
 * The page browser_read or browser_find works on: an open tab, or a URL to open one at. REST names
 * the tab in its path, and has no call that opens one to read it.
 */
@Serializable
public data class PageArg(
    @Doc("Browser target id, from browser_targets or browser_open. Pass this or url.")
    val target: String? = null,
    @Doc(
        "An http(s) URL to open in a new tab and use once it settles, instead of target. The result names the new target, so it can be used again and closed with browser_close, unless close is true."
    )
    val url: String? = null,
    @Doc(
        "Close the tab after this call, when this is the last look you need. Saves a browser_close call."
    )
    val close: Boolean = false,
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
