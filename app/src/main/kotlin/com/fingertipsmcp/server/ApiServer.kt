package com.fingertipsmcp.server

import android.util.Log
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * HTTP API on an abstract local socket, reached from the host with `adb forward`.
 * [ServerController] owns its lifecycle and runs [start] and [stop] one at a time.
 */
class ApiServer(
    private val socketName: String,
    private val dispatcher: CoroutineDispatcher,
    /** Sets up the application, which [fingertipsModule] does. */
    private val module: Application.() -> Unit,
) {
    private var server: EmbeddedServer<AbstractHttpEngine, AbstractHttpEngine.Configuration>? = null

    /** Opens the listener, or does nothing when it is open. Throws when it cannot bind. */
    fun start() {
        if (server != null) return
        val starting =
            embeddedServer(
                AbstractHttpEngine,
                applicationEnvironment {},
                configure = {
                    socketName = this@ApiServer.socketName
                    dispatcher = this@ApiServer.dispatcher
                },
                module = module,
            )
        try {
            starting.start(wait = false)
        } catch (e: IOException) {
            // Ktor creates the application before the engine binds, and leaves it running if the
            // bind fails. Each failed start would add another, with its own session sweep.
            starting.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            throw e
        }
        server = starting
        Log.i(TAG, "Listening on abstract socket $socketName")
    }

    /**
     * Returns once the abstract name is free, and throws when it may still be bound. The
     * connections and the application end either way.
     */
    suspend fun stop() {
        val running = server ?: return
        server = null
        try {
            running.engine.closeListener()
        } finally {
            running.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }
}

private const val TAG = "FingertipsServer"
