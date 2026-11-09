package com.fingertipsmcp

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FingertipsApp : Application() {
    override fun onCreate() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
        super.onCreate()
        // The graph needs the files directory, and getFilesDir stats it, creating it on first
        // run. That is one small disk access the main thread makes once, at process start, before
        // anything else runs, so it is allowed here rather than handed to a thread the main thread
        // would wait for anyway. The stores read and write their files on their callers' threads,
        // which are background threads, so StrictMode still reports any main-thread use of them.
        // allowThreadDiskWrites permits reads too. Every getFilesDir call stats the directory, so
        // this is the only one: the graph hands the result to whatever needs it.
        val policy = StrictMode.allowThreadDiskWrites()
        val files =
            try {
                filesDir
            } finally {
                StrictMode.setThreadPolicy(policy)
            }
        graph = AppGraph(this, filesDir = files).also { it.start() }
        built.countDown()
    }

    companion object {
        // Holds only the Application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        lateinit var graph: AppGraph
            private set

        private val built = CountDownLatch(1)

        fun graphOrNull(): AppGraph? = if (this::graph.isInitialized) graph else null

        /**
         * Waits up to [timeoutMs] for [onCreate] to build the graph, for a provider's binder call.
         * Android publishes providers before it calls [onCreate], so a call that started the
         * process can arrive first. Null when the deadline passes.
         */
        fun awaitGraph(timeoutMs: Long): AppGraph? =
            if (built.await(timeoutMs, TimeUnit.MILLISECONDS)) graph else null
    }
}
