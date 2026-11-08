package com.fingertipsmcp.mcp

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.model.ApiError
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SessionArg
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.token
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException

/**
 * Stateful Streamable HTTP MCP at `/mcp`. `initialize` starts a session with an id FingertipsMCP
 * picks; later requests carry it in `Mcp-Session-Id` along with the token that started it. POSTs
 * are answered with JSON, GET is refused since FingertipsMCP sends nothing unprompted, and `DELETE`
 * ends the session. Tools and resources follow the caller's scopes at `initialize`, and the session
 * ends if the token later loses one of them. DNS rebinding protection stays off: the rest of the
 * API is a superset of these tools and does not check Host.
 */
internal fun Application.installPhoneMcp(services: ServerServices, sessions: McpSessions) {
    routing {
        route("/mcp") {
            post {
                val id = call.request.header(SESSION_HEADER)
                if (id == null) {
                    startSession(services, sessions, call)
                    return@post
                }
                // Another token's session answers as one that does not exist.
                val session = sessions.get(id)?.takeIf { it.tokenId == call.token.id }
                when {
                    session == null -> call.respond(HttpStatusCode.NotFound, noSession())
                    // Its tools were built for scopes the token no longer has, so it ends now,
                    // not at the next sweep, and the client initializes again.
                    !call.token.scopes.containsAll(session.scopes) -> {
                        sessions.end(session.id, "its token lost a scope")
                        call.respond(HttpStatusCode.NotFound, noSession())
                    }
                    session.transport != null -> {
                        session.touch(sessions.clock())
                        serve(session, call)
                    }
                    // A REST session takes an initialize, which gives it a transport.
                    session.rest -> attachSession(services, sessions, session, call)
                    // One from session_start has no transport: it is only a tool argument.
                    else -> call.respond(HttpStatusCode.NotFound, noSession())
                }
            }
            get {
                call.response.header(HttpHeaders.Allow, "POST, DELETE")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
            delete {
                val session =
                    call.request.header(SESSION_HEADER)?.let(sessions::get)?.takeIf {
                        it.tokenId == call.token.id && it.transport != null
                    }
                if (session == null) {
                    call.respond(HttpStatusCode.NotFound, noSession())
                } else {
                    // Ends it here, transport and all, before answering, so a client that
                    // deleted a session sees it gone, its screen let go, and its tabs closed. The
                    // transport's own DELETE would end it from a callback after answering.
                    sessions.end(session.id, "the client deleted it")
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}

private suspend fun startSession(
    services: ServerServices,
    sessions: McpSessions,
    call: ApplicationCall,
) {
    val token = call.token
    sessions.admit(token.id)
    val id = sessions.newId()
    val transport = newTransport()
    transport.setSessionIdGenerator { id }
    val control = sessions.control(id, token.id, token.name)
    val server = phoneServer(services, token.scopes, Holder.Session(id), control)
    val session =
        McpSession(
            id = id,
            tokenId = token.id,
            tokenName = token.name,
            transport = transport,
            server = server,
            startedAtMs = sessions.clock(),
            scopes = token.scopes,
        )
    transport.setOnSessionInitialized { sessions.add(session) }
    transport.setOnSessionClosed { sessions.closed(it) }
    server.createSession(transport)
    transport.handleRequest(null, call)
    // A POST without a session id that was not an initialize is refused by the transport, and
    // leaves nothing behind.
    if (transport.sessionId == null) server.close()
}

/**
 * Gives a REST [session] an MCP transport, from an `initialize` that carries its id, so one session
 * works on both. A request that is not an initialize is refused by the transport, as for a new
 * session, and leaves the session as it was.
 */
private suspend fun attachSession(
    services: ServerServices,
    sessions: McpSessions,
    session: McpSession,
    call: ApplicationCall,
) {
    val transport = newTransport()
    transport.setSessionIdGenerator { session.id }
    val control = sessions.control(session.id, session.tokenId, session.tokenName)
    val scopes = call.token.scopes
    val server = phoneServer(services, scopes, Holder.Session(session.id), control)
    transport.setOnSessionInitialized { session.attach(transport, server, scopes) }
    transport.setOnSessionClosed { sessions.closed(it) }
    server.createSession(transport)
    session.touch(sessions.clock())
    transport.handleRequest(null, call)
    if (transport.sessionId == null) server.close()
}

private fun newTransport() =
    StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
    )

/**
 * Hands [call] to the session's transport. A session that ends while the call waits, as on `DELETE`
 * or expiry, cancels it; the client then gets a 404 rather than an internal error.
 */
private suspend fun serve(session: McpSession, call: ApplicationCall) = coroutineScope {
    val transport = checkNotNull(session.transport)
    val handled = async { transport.handleRequest(null, call) }
    handled.join()
    // join throws when this call is canceled, as when its client goes away. Otherwise the
    // transport only cancels a call when the session closes.
    if (handled.isCancelled) {
        if (!call.response.isCommitted) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("session_not_found", "The MCP session ended"),
            )
        }
    } else {
        handled.await()
    }
}

private fun noSession() =
    ApiError("session_not_found", "No such MCP session. Start a new one with initialize.")

/** The MCP server for one session, with the tools and resources [scopes] allow. */
private fun phoneServer(
    shared: ServerServices,
    scopes: Set<Scope>,
    holder: Holder.Session,
    control: SessionControl,
): Server {
    val services = shared.forHolder(holder, control)
    val tools = visibleMcpTools(scopes, services.device.capabilities())
    val resources = mcpResources(scopes)
    return Server(
        Implementation(name = "fingertips-android", version = services.device.versionName()),
        ServerOptions(
            capabilities =
                ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources =
                        if (resources.isEmpty()) {
                            null
                        } else {
                            ServerCapabilities.Resources(listChanged = false, subscribe = false)
                        },
                )
        ),
        instructions = mcpInstructions(scopes),
    ) {
        tools.forEach { tool ->
            addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.schema,
                toolAnnotations = tool.annotations,
            ) { asked ->
                callTool(shared, services, scopes, tool, asked)
            }
        }
        resources.forEach { resource ->
            addResource(
                uri = resource.uri,
                name = resource.name,
                description = resource.description,
                mimeType = resource.mimeType,
            ) { _ ->
                ReadResourceResult(
                    contents =
                        listOf(
                            TextResourceContents(
                                text = resource.read(services),
                                uri = resource.uri,
                                mimeType = resource.mimeType,
                            )
                        )
                )
            }
        }
    }
}

private const val SESSION_HEADER = "Mcp-Session-Id"

/** The services as [holder] sees them: only its own tabs and unowned ones. */
private fun ServerServices.forHolder(holder: Holder.Session, control: SessionControl) =
    copy(
        browser = tabs.browserFor(holder, control::related),
        waits = tabs.waitsFor(holder, waits, control::related),
        holder = holder,
        sessions = control,
    )

/**
 * Runs a call to [tool] for the session it names, or the caller's own: holding the screen when it
 * needs it, keeping other sessions' tabs off Chrome's screen meanwhile, and otherwise watching for
 * it bringing an app forward.
 */
private suspend fun callTool(
    shared: ServerServices,
    own: ServerServices,
    scopes: Set<Scope>,
    tool: McpTool,
    asked: CallToolRequest,
): CallToolResult {
    val named =
        try {
            sessionArgument(tool, asked) ?: return tool.call(own, scopes, asked)
        } catch (e: SerializationException) {
            return badArguments(e)
        }
    val control = checkNotNull(own.sessions)
    val holder =
        if (named.isEmpty()) {
            checkNotNull(own.holder)
        } else {
            control.resolve(named) ?: return unknownSession()
        }
    val services = if (holder == own.holder) own else shared.forHolder(holder, control)
    // The tool's own types ignore the session argument, as they ignore each other's fields.
    val request = resolveInput(tool.name, asked, services.lease.holds(holder), scopes)
    val arguments = request.params.arguments
    val (screen, brief) = screenUse(tool.screen, arguments, services.browser)
    val current = services.lease.state.value.holder as? Holder.Session
    if (screen && current != null) {
        control.conflict(holder, current)?.let {
            return sessionConflict(it)
        }
    }
    return leased(services.lease, holder, screen, brief) {
        val call = suspend { tool.call(services, scopes, request) }
        when {
            screen -> services.tabs.screenCall(holder, call)
            tool.screen.launches(arguments) ->
                tookScreenError { services.foreground.watch(holder, call) }
            else -> call()
        }
    }
}

/**
 * The `session` argument of a call to [tool]: empty when there is none, so the call is the caller's
 * own, and null for the session tools, which handle sessions themselves.
 */
private fun sessionArgument(tool: McpTool, asked: CallToolRequest): String? {
    if (tool.name in SESSION_TOOLS) return null
    return asked.args<SessionArg>().session.orEmpty()
}

private fun badArguments(e: SerializationException) =
    CallToolResult(
        content = listOf(TextContent(text = "bad_request: ${e.message.orEmpty()}")),
        isError = true,
    )

private fun sessionConflict(message: String) =
    CallToolResult(
        content = listOf(TextContent(text = "session_conflict: $message")),
        isError = true,
    )

private fun unknownSession() =
    CallToolResult(
        content =
            listOf(
                TextContent(
                    text =
                        "session_not_found: No such session. It may have ended or idled out. Start " +
                            "one with session_start, or leave session out to use your own."
                )
            ),
        isError = true,
    )
