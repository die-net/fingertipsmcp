package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.DevtoolsSocketInfo
import java.io.Closeable

/**
 * A desktop browser over one [CdpBrowserConnection]. [connection] starts the browser when it is not
 * running, or throws `browser_unavailable`. There is one endpoint, [name], so target ids read
 * `chrome~<targetId>`, shaped like Android's. Read timeouts do not apply: the connection is a pipe
 * or a WebSocket the browser keeps open, and each command has its own timeout.
 */
class ConnectionEndpoints(
    private val connection: suspend () -> CdpBrowserConnection,
    private val name: String = DESKTOP_ENDPOINT,
    private val pid: () -> Int? = { null },
    private val missing: String = "The browser is not running.",
) : DevtoolsEndpoints {
    override val tabs: String = name

    override val tabsPackage: String? = null

    override fun gap(gap: BrowserGap): String = missing

    override suspend fun endpoints(): List<DevtoolsSocketInfo> {
        connection()
        return listOf(DevtoolsSocketInfo(name, pid()))
    }

    override suspend fun targets(endpoint: DevtoolsSocketInfo): List<BrowserTarget> {
        requireEndpoint(endpoint.name)
        val reply = connection().root.call("Target.getTargets")
        return chromeTargets(reply, endpoint.name, endpoint.pid, packageName = null)
    }

    override suspend fun <T> withPage(
        endpoint: String,
        targetId: String,
        block: suspend (CdpSession) -> T,
    ): T = attach(endpoint, targetId).use { block(it) }

    override suspend fun <T> withBrowser(endpoint: String, block: suspend (CdpSession) -> T): T {
        requireEndpoint(endpoint)
        return connection().attachBrowser().use { block(it) }
    }

    @Suppress("MissingUseCall") // The caller closes the session.
    override suspend fun openPage(
        endpoint: String,
        targetId: String,
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit,
    ): OpenCdpSession = attach(endpoint, targetId)

    @Suppress("MissingUseCall") // The caller closes the session.
    override suspend fun openBrowser(
        endpoint: String,
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit,
    ): OpenCdpSession {
        requireEndpoint(endpoint)
        return connection().attachBrowser()
    }

    /**
     * A session on [targetId]. When the browser refuses the attach, its target list says whether
     * the tab has gone, which fails as [tabGone] reads it, rather than by Chrome's wording.
     */
    @Suppress("MissingUseCall") // The caller closes the session.
    private suspend fun attach(endpoint: String, targetId: String): OpenCdpSession {
        requireEndpoint(endpoint)
        val browser = connection()
        return try {
            browser.attach(targetId)
        } catch (e: ApiException) {
            if (e.rejected == null || listed(browser, targetId)) throw e
            throw ApiException(
                502,
                "cdp_error",
                "The tab has closed",
                cause = TargetMissing(targetId),
            )
        }
    }

    private suspend fun listed(browser: CdpBrowserConnection, targetId: String): Boolean =
        chromePages(browser.root.call("Target.getTargets")).any { it.targetId == targetId }

    private fun requireEndpoint(endpoint: String) {
        if (endpoint != name) throw ApiException.notFound("No such browser target")
    }
}

/** The endpoint name a desktop browser's targets are listed under. */
const val DESKTOP_ENDPOINT = "chrome"
