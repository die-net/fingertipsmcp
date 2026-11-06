package net.die.phoneapi.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import java.net.URI
import java.net.URISyntaxException
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo

private val TokenKey = AttributeKey<TokenInfo>("phoneapi.token")

/** `GET /viewer`. The page and its WebSocket upgrades are the only query-token callers. */
internal const val VIEWER_PATH = "/viewer"

/**
 * Set when `/viewer` is opened with `access_token`, then sent on later requests so the token is not
 * kept in the address bar. HttpOnly, so the page cannot read it. The server takes it only where the
 * viewer page needs it, as [acceptsViewerCookie] says.
 */
internal const val VIEWER_COOKIE = "phoneapi"

class AuthConfig {
    lateinit var tokens: TokenGateway
}

/**
 * Every request needs a valid bearer token. `access_token` is accepted only on [VIEWER_PATH] and on
 * WebSocket upgrades, because a query parameter is copied into logs and history. That page sets
 * [VIEWER_COOKIE] and redirects so the browser can drop the query. Unauthenticated requests get an
 * empty 404, so the server reveals as little as possible about what it is.
 */
val BearerAuth =
    createApplicationPlugin("BearerAuth", ::AuthConfig) {
        val tokens = pluginConfig.tokens
        onCall { call ->
            if (call.isWebSocketUpgrade() && !call.sameOriginOrNone()) {
                call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                return@onCall
            }
            val info = call.presentedSecret()?.let(tokens::authenticate)
            if (info == null) {
                call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            } else {
                call.attributes.put(TokenKey, info)
            }
        }
    }

private const val BEARER_PREFIX_LENGTH = 7

/**
 * The secret this request presents. The query token wins over an older cookie on the viewer and on
 * sockets, so opening a new link replaces the cookie instead of keeping the previous one.
 */
private fun ApplicationCall.presentedSecret(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(BEARER_PREFIX_LENGTH)
        ?.trim()
        ?: decodedSecret(acceptsAccessTokenQuery()) { request.queryParameters["access_token"] }
        ?: decodedSecret(acceptsViewerCookie()) { request.cookies[VIEWER_COOKIE] }

/**
 * Runs [read], which percent-decodes, only where the secret is [accepted]. A value that can't be
 * decoded is no token. Letting the error out would answer a caller without a token with a body, and
 * its message, which quotes the value, would reach the log.
 */
private inline fun decodedSecret(accepted: Boolean, read: () -> String?): String? {
    if (!accepted) return null
    val secret =
        try {
            read()
        } catch (_: URLDecodeException) {
            null
        } catch (_: BadRequestException) {
            null
        }
    return secret?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun ApplicationCall.acceptsAccessTokenQuery(): Boolean {
    if (isWebSocketUpgrade()) return true
    return request.httpMethod == HttpMethod.Get && request.path() == VIEWER_PATH
}

/**
 * The browser sends [VIEWER_COOKIE] on every request to this host, including requests a page on
 * another `127.0.0.1` port starts, so it stands in for the token only on what the viewer page uses:
 * the page itself, the device status it reads, and its sockets, which check `Origin`.
 */
internal fun ApplicationCall.acceptsViewerCookie(): Boolean {
    if (isWebSocketUpgrade()) return true
    return request.httpMethod == HttpMethod.Get && request.path() in VIEWER_COOKIE_PATHS
}

private val VIEWER_COOKIE_PATHS = setOf(VIEWER_PATH, "/v1/device")

private fun ApplicationCall.isWebSocketUpgrade(): Boolean =
    request.headers[HttpHeaders.Upgrade]?.contains("websocket", ignoreCase = true) == true

/**
 * A WebSocket upgrade from a browser names its page in `Origin`. The viewer cookie is
 * `SameSite=Strict`, but a page on `127.0.0.1` at any port is the same site, so another local web
 * server's page could otherwise open the viewer's sockets with it. Only the API's own address may.
 * Clients outside a browser send no `Origin`.
 */
private fun ApplicationCall.sameOriginOrNone(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    val host = request.headers[HttpHeaders.Host] ?: return false
    val page =
        try {
            URI(origin)
        } catch (_: URISyntaxException) {
            return false
        }
    val scheme = page.scheme?.lowercase()
    val pageHost = page.host ?: return false
    if (scheme != "http" && scheme != "https") return false
    val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
    val pagePort = if (page.port == -1) defaultPort else page.port
    val split = host.lastIndexOf(':')
    val hostName = if (split > host.lastIndexOf(']')) host.substring(0, split) else host
    val hostPort =
        if (split > host.lastIndexOf(']')) host.substring(split + 1).toIntOrNull() else defaultPort
    return pageHost.trim('[', ']').equals(hostName.trim('[', ']'), ignoreCase = true) &&
        pagePort == hostPort
}

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

val ApplicationCall.token: TokenInfo
    get() = attributes[TokenKey]

private class ScopePluginConfig {
    var scopes: Set<Scope> = emptySet()
}

private val RequireScope =
    createRouteScopedPlugin("RequireScope", ::ScopePluginConfig) {
        val allowed = pluginConfig.scopes
        val named = allowed.sorted()
        onCall { call ->
            if (call.isHandled) return@onCall
            if (allowed.none(call.token.scopes::contains)) throw ApiException.forbidden(named)
        }
    }

/**
 * Child routes require one of [scopes]. A route added outside [scoped] has no scope, which the
 * route test rejects.
 */
fun Route.scoped(vararg scopes: Scope, build: Route.() -> Unit): Route {
    require(scopes.isNotEmpty()) { "Name a scope, or use anyToken" }
    val allowed = scopes.toSet()
    val child = createChild(ScopeRouteSelector(allowed))
    child.install(RequireScope) { this.scopes = allowed }
    child.build()
    return child
}

/**
 * Child routes any token may call, needing no particular scope: starting and ending one's own
 * session. The route test checks them as it does [scoped] ones.
 */
fun Route.anyToken(build: Route.() -> Unit): Route {
    val child = createChild(ScopeRouteSelector(emptySet()))
    child.build()
    return child
}

/**
 * Marks a [scoped] or [anyToken] block, with no [scopes] for the latter. Identity equality keeps
 * two blocks with the same scopes from merging.
 */
internal class ScopeRouteSelector(val scopes: Set<Scope>) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String =
        "(scope:${scopes.sorted().joinToString("|") { it.name.lowercase() }.ifEmpty { "any" }})"
}
