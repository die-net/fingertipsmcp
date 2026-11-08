package com.fingertipsmcp.mcp

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.LeaseState
import com.fingertipsmcp.model.DeviceInfo

/**
 * Sessions as one MCP session's tools see them. `session_start` makes a session under it, for a
 * subagent or a parallel task, with its own turn at the screen and its own tabs; a call names it
 * with the `session` argument. Such sessions end with `session_end`, by idling out, or with the
 * session that started them.
 */
interface SessionControl {
    /** Starts a session under this one and returns its id. */
    fun start(): String

    /** Ends a session this one started. False when there is no such session. */
    suspend fun end(id: String): Boolean

    /** The holder [id] names, if it is this session or a live one it started. */
    fun resolve(id: String): Holder.Session?

    /** Whether [holder] is this session or one it started, without counting as a use of it. */
    fun related(holder: Holder.Session): Boolean

    /**
     * Why [caller] may not wait for the screen [holder] has: one started the other with
     * `session_start`, so the wait would be the caller's own work queued behind itself. Null when
     * they are unrelated and the caller should wait as usual.
     */
    fun conflict(caller: Holder.Session, holder: Holder.Session): String?

    /** [info] with who has the screen in [state], as this session sees it. */
    fun withScreenHolder(info: DeviceInfo, state: LeaseState, caller: Holder.Session): DeviceInfo
}
