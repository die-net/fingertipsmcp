package net.die.phoneapi.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.ktor.events.Events
import io.ktor.http.cio.Request
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.PipelineCall
import io.ktor.server.cio.backend.ServerIncomingConnection
import io.ktor.server.cio.backend.ServerRequestScope
import io.ktor.server.cio.backend.startServerConnectionPipeline
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Ktor engine that accepts HTTP on an abstract-namespace [LocalServerSocket]. CIO's own connector
 * only binds filesystem unix sockets, so each accepted [LocalSocket] is handed to CIO's connection
 * pipeline directly.
 */
@OptIn(InternalAPI::class)
class AbstractHttpEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    private val configuration: Configuration,
    private val applicationProvider: () -> Application,
) : BaseApplicationEngine(environment, monitor, developmentMode) {
    class Configuration : BaseApplicationEngine.Configuration() {
        var socketName: String = "phoneapi"

        /** Where connections and CIO's request work run: the graph's I/O dispatcher. */
        lateinit var dispatcher: CoroutineDispatcher
    }

    private val scope = CoroutineScope(SupervisorJob() + configuration.dispatcher)
    private var listener: AcceptLoop<LocalSocket>? = null

    @Suppress("MissingUseCall") // closeListener() closes the server socket.
    override fun start(wait: Boolean): ApplicationEngine {
        applicationProvider()
        val listening = LocalServerSocket(configuration.socketName)
        resolvedConnectorsDeferred.complete(emptyList())
        listener =
            AcceptLoop(
                    threadName = "phoneapi-abstract",
                    scope = scope,
                    accept = listening::accept,
                    wake = { shutDown(listening) },
                    close = listening::close,
                    serve = { client -> serve(this, client) },
                )
                .also { it.start() }
        return this
    }

    /**
     * Stops taking connections, and returns once the abstract name is free. It is apart from [stop]
     * because [stop] can't suspend, and because EmbeddedServer logs and drops what [stop] throws.
     *
     * @throws IOException when the name may still be bound.
     */
    suspend fun closeListener() {
        val closing = listener ?: return
        listener = null
        closing.stop()
    }

    /**
     * Ends the connections. Cancelling them closes their sockets too, so streams and keep-alive
     * clients end with the listener, which [closeListener] has closed by now.
     */
    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        scope.cancel()
    }

    /** Makes a blocked accept fail, which closing the socket does not do. */
    private fun shutDown(listening: LocalServerSocket) {
        try {
            Os.shutdown(listening.fileDescriptor, OsConstants.SHUT_RDWR)
        } catch (e: ErrnoException) {
            throw IOException("Could not shut down the listening socket", e)
        }
    }

    private suspend fun serve(caller: CoroutineScope, client: LocalSocket) {
        client.use { socket ->
            val connection =
                ServerIncomingConnection(
                    input = socket.inputStream.toByteReadChannel(),
                    output =
                        ShutdownOnClose(socket.outputStream.asByteWriteChannel()) {
                            // The client may have closed the socket already.
                            runCatching { socket.shutdownOutput() }
                        },
                    remoteAddress = null,
                    localAddress = null,
                )
            caller
                .startServerConnectionPipeline(connection, IDLE_TIMEOUT) { request ->
                    dispatch(this, request)
                }
                .join()
        }
    }

    private suspend fun dispatch(scope: ServerRequestScope, request: Request) {
        val call =
            CioCalls.open(
                application = applicationProvider(),
                request = request,
                input = scope.input,
                output = scope.output,
                io = configuration.dispatcher,
                upgraded = scope.upgraded,
                coroutineContext = scope.coroutineContext,
            )
        try {
            pipeline.execute(call, Unit)
        } finally {
            CioCalls.release(call)
        }
    }

    companion object : ApplicationEngineFactory<AbstractHttpEngine, Configuration> {
        private val IDLE_TIMEOUT = 45.seconds

        override fun configuration(configure: Configuration.() -> Unit): Configuration =
            Configuration().apply(configure)

        override fun create(
            environment: ApplicationEnvironment,
            monitor: Events,
            developmentMode: Boolean,
            configuration: Configuration,
            applicationProvider: () -> Application,
        ): AbstractHttpEngine =
            AbstractHttpEngine(
                environment,
                monitor,
                developmentMode,
                configuration,
                applicationProvider,
            )
    }
}

/**
 * [io.ktor.server.cio.CIOApplicationCall] is internal. It lives in this APK, so the constructor is
 * reachable by reflection; R8 is told to keep it.
 */
private object CioCalls {
    private val ctor =
        Class.forName("io.ktor.server.cio.CIOApplicationCall")
            .declaredConstructors
            .first {
                it.parameterCount == 10
            }
            .apply { isAccessible = true }

    private val release =
        Class.forName("io.ktor.server.cio.CIOApplicationCall")
            .getDeclaredMethod("release\$ktor_server_cio")
            .apply { isAccessible = true }

    fun open(
        application: Application,
        request: Request,
        input: Any?,
        output: Any?,
        io: CoroutineDispatcher,
        upgraded: Any?,
        coroutineContext: Any?,
    ): PipelineCall =
        ctor.newInstance(
            application,
            request,
            input,
            output,
            io,
            io,
            upgraded,
            null,
            null,
            coroutineContext,
        ) as PipelineCall

    fun release(call: PipelineCall) {
        release.invoke(call)
    }
}
