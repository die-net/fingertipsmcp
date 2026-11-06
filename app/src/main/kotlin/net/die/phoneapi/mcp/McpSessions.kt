package net.die.phoneapi.mcp

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.LeaseState
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SessionInfo
import net.die.phoneapi.server.TokenGateway

/**
 * One session: a holder of the screen and of tabs, and the token that started it. A session from
 * `initialize` has its own transport and server. One from `session_start` has neither: it is named
 * by the `session` argument on the calls of the session that started it, its [parentId]. One from
 * `POST /v1/sessions` is [rest]: REST calls name it in `Mcp-Session-Id`, and an `initialize`
 * carrying its id gives it a transport too.
 */
internal class McpSession(
    val id: String,
    val tokenId: String,
    val tokenName: String,
    transport: StreamableHttpServerTransport?,
    server: Server?,
    val startedAtMs: Long,
    val parentId: String? = null,
    val rest: Boolean = false,
    scopes: Set<Scope> = emptySet(),
) {
    @Volatile
    var transport: StreamableHttpServerTransport? = transport
        private set

    @Volatile
    var server: Server? = server
        private set

    /**
     * The scopes [server]'s tools were built for. Empty without a server, since REST checks the
     * token's scopes on every request.
     */
    @Volatile
    var scopes: Set<Scope> = scopes
        private set

    /** Gives a REST session the MCP transport an `initialize` made for it, with [scopes]' tools. */
    fun attach(transport: StreamableHttpServerTransport, server: Server, scopes: Set<Scope>) {
        this.transport = transport
        this.server = server
        this.scopes = scopes
    }

    @Volatile
    var lastActiveMs: Long = startedAtMs
        private set

    /** Records a request at [atMs], which restarts the idle timer. */
    fun touch(atMs: Long) {
        lastActiveMs = atMs
    }
}

/**
 * The MCP sessions on `/mcp`, keyed by the `Mcp-Session-Id` PhoneAPI picks. A session ends on
 * `DELETE`, after [idleMs] without a request, or when its token is revoked or loses a scope its
 * tools were built for, and [onEnd] then releases what it held. Logs name a session only by
 * [hashOf] its id, since `logcat_tail` lets any session read the log.
 */
class McpSessions(
    private val tokens: TokenGateway,
    private val scope: CoroutineScope,
    private val onEnd: suspend (String) -> Unit = {},
    private val holdsScreen: (String) -> Boolean = { false },
    private val tabCount: (String) -> Int = { 0 },
    private val tookScreen: (String) -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    private val perToken: Int = PER_TOKEN,
    private val total: Int = TOTAL,
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val random = SecureRandom()

    /** Starts the sweep that ends idle sessions and those whose token was revoked or narrowed. */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(SWEEP_MS)
                sweep()
            }
        }
    }

    fun list(): List<SessionInfo> {
        val at = now()
        return sessions.values
            .sortedBy { it.startedAtMs }
            .map {
                SessionInfo(
                    hash = hashOf(it.id),
                    token = it.tokenName,
                    ageMs = at - it.startedAtMs,
                    idleMs = at - it.lastActiveMs,
                    holdsScreen = holdsScreen(it.id),
                    tabs = tabCount(it.id),
                    tookScreen = tookScreen(it.id),
                    parent = it.parentId?.let(::hashOf),
                )
            }
    }

    /** Ends the session whose id hashes to [hash]. False when there is none. */
    suspend fun endByHash(hash: String): Boolean {
        val id = sessions.keys.firstOrNull { hashOf(it) == hash } ?: return false
        end(id, "ended by an admin")
        return true
    }

    /**
     * Ends idle sessions, those whose token no longer exists, and those whose tools need a scope
     * the token no longer has. A token that gains a scope leaves its sessions running; the new
     * tools show up at the client's next `initialize`. A session's own request ends it at once on a
     * lost scope; this catches one that makes no request. A failure ending one session is logged
     * and the sweep goes on, so it cannot stop idle expiry for the others.
     */
    suspend fun sweep() {
        val at = now()
        val live = tokens.list().associate { it.id to it.scopes }
        for (session in sessions.values.toList()) {
            val scopes = live[session.tokenId]
            val why =
                when {
                    scopes == null -> "its token was revoked"
                    !scopes.containsAll(session.scopes) -> "its token lost a scope"
                    at - session.lastActiveMs > idleMs -> "it was idle"
                    else -> continue
                }
            try {
                end(session.id, why)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress(
                    "TooGenericExceptionCaught"
                ) // One session's cleanup must not stop the rest.
                e: Exception) {
                Log.w(TAG, "Ending MCP session ${hashOf(session.id)} failed", e)
            }
        }
    }

    /**
     * Ends [id], and the sessions it started: closes its transport, which cancels its calls, then
     * runs [onEnd]. Idempotent.
     */
    suspend fun end(id: String, why: String) {
        val session = sessions.remove(id) ?: return
        Log.i(TAG, "MCP session ${hashOf(id)} ended: $why")
        for (child in sessions.values.filter { it.parentId == id }) {
            end(child.id, "the session that started it ended")
        }
        try {
            session.transport?.close()
            session.server?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") // onEnd must run whatever closing throws.
            e: Exception) {
            Log.w(TAG, "Closing MCP session ${hashOf(id)} failed", e)
        }
        onEnd(id)
    }

    internal fun get(id: String): McpSession? = sessions[id]

    /** Starts a session for `POST /v1/sessions`, with no transport, and returns its id. */
    fun startRest(tokenId: String, tokenName: String): String {
        admit(tokenId)
        val session =
            McpSession(
                id = newId(),
                tokenId = tokenId,
                tokenName = tokenName,
                transport = null,
                server = null,
                startedAtMs = now(),
                rest = true,
            )
        add(session)
        return session.id
    }

    /**
     * The holder a REST call from [tokenId] names with [id], if that is a live session of its
     * token. Counts as a use of it.
     */
    fun forRest(id: String, tokenId: String): Holder.Session? {
        val session = sessions[id]?.takeIf { it.tokenId == tokenId } ?: return null
        session.touch(now())
        return Holder.Session(id)
    }

    /**
     * Why the session [callerId] may not wait for the screen [holderId] has, when one started the
     * other with `session_start`. Two sessions started by the same one may wait for each other.
     */
    fun conflict(callerId: String, holderId: String): String? =
        when {
            callerId == holderId -> null
            sessions[callerId]?.parentId == holderId ->
                "Your own session holds the screen. If this call is yours, leave session " +
                    "out. If it is for a subagent, call screen_release without session first."
            sessions[holderId]?.parentId == callerId ->
                "A session you started with session_start holds the screen. Wait for that " +
                    "task to finish, or end it with session_end, before using the screen " +
                    "yourself."
            else -> null
        }

    /**
     * [info] with who has the screen in [state], as [caller] sees it: itself, the session that
     * started it, one it started, another agent, a person in the viewer, or a script on REST.
     */
    fun withScreenHolder(info: DeviceInfo, state: LeaseState, caller: String?): DeviceInfo {
        val holder = state.holder ?: return info
        val kind =
            when (holder) {
                is Holder.Viewer -> "person"
                is Holder.Session ->
                    when {
                        holder.id == caller -> "you"
                        caller != null && sessions[caller]?.parentId == holder.id -> "parent"
                        caller != null && sessions[holder.id]?.parentId == caller -> "subagent"
                        isRest(holder.id) -> "script"
                        else -> "agent"
                    }
            }
        return info.copy(screenHolder = kind, screenHeldMs = now() - state.sinceMs)
    }

    /** Whether [id] is a session made with `POST /v1/sessions`. */
    fun isRest(id: String): Boolean = sessions[id]?.rest == true

    /** What session [id] can do with sessions, as its own calls see it. */
    fun controlFor(id: String): SessionControl? =
        sessions[id]?.let { control(it.id, it.tokenId, it.tokenName) }

    internal fun clock(): Long = now()

    /** Throws `too_many_sessions` when [tokenId] or the phone already has as many as allowed. */
    internal fun admit(tokenId: String) {
        val open = sessions.values
        if (open.size >= total || open.count { it.tokenId == tokenId } >= perToken) {
            throw ApiException(
                429,
                "too_many_sessions",
                "Too many MCP sessions are open: $perToken per token and $total in all. " +
                    "Close one with DELETE or session_end, or an admin can end one with " +
                    "DELETE /v1/sessions/{hash}.",
            )
        }
    }

    internal fun add(session: McpSession) {
        sessions[session.id] = session
        val under = session.parentId?.let { " under ${hashOf(it)}" }.orEmpty()
        Log.i(TAG, "MCP session ${hashOf(session.id)} started for token ${session.tokenName}$under")
    }

    /** What the session [parentId] can do with sessions, from its tools. */
    internal fun control(parentId: String, tokenId: String, tokenName: String): SessionControl =
        object : SessionControl {
            override fun start(): String {
                admit(tokenId)
                val child =
                    McpSession(
                        id = newId(),
                        tokenId = tokenId,
                        tokenName = tokenName,
                        transport = null,
                        server = null,
                        startedAtMs = now(),
                        parentId = parentId,
                    )
                add(child)
                return child.id
            }

            override suspend fun end(id: String): Boolean {
                if (sessions[id]?.parentId != parentId) return false
                end(id, "session_end")
                return true
            }

            override fun resolve(id: String): Holder.Session? {
                val session = sessions[id]?.takeIf(::ours) ?: return null
                session.touch(now())
                return Holder.Session(session.id)
            }

            override fun related(holder: Holder.Session): Boolean =
                sessions[holder.id]?.let(::ours) == true

            override fun conflict(caller: Holder.Session, holder: Holder.Session): String? =
                conflict(caller.id, holder.id)

            override fun withScreenHolder(
                info: DeviceInfo,
                state: LeaseState,
                caller: Holder.Session,
            ): DeviceInfo = this@McpSessions.withScreenHolder(info, state, caller.id)

            private fun ours(session: McpSession): Boolean =
                session.tokenId == tokenId &&
                    (session.id == parentId || session.parentId == parentId)
        }

    /** A transport closed itself, as on `DELETE`. */
    internal fun closed(id: String) {
        scope.launch { end(id, "the client closed it") }
    }

    internal fun newId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    companion object {
        const val IDLE_MS = 20 * 60 * 1000L
        const val PER_TOKEN = 16
        const val TOTAL = 32
        private const val SWEEP_MS = 30_000L
        private const val ID_BYTES = 16
        private const val HASH_CHARS = 12
        private const val TAG = "PhoneApiMcp"

        /** A short hash of a session id, safe to log and to show to admins. */
        fun hashOf(id: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray())
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
                .take(HASH_CHARS)
    }
}
