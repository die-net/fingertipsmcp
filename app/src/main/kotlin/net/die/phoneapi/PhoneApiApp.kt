package net.die.phoneapi

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

class PhoneApiApp : Application() {
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
        // allowThreadDiskWrites permits reads too.
        val policy = StrictMode.allowThreadDiskWrites()
        val files =
            try {
                filesDir
            } finally {
                StrictMode.setThreadPolicy(policy)
            }
        graph = AppGraph(this, filesDir = files).also { it.start() }
    }

    companion object {
        // Holds only the Application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        lateinit var graph: AppGraph
            private set

        fun graphOrNull(): AppGraph? = if (this::graph.isInitialized) graph else null
    }
}
