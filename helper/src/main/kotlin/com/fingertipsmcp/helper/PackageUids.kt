package com.fingertipsmcp.helper

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Resolves an installed package's uid. `app_process` has no application Context, so this asks `cmd
 * package`, which the shell and root uids can run.
 */
internal object PackageUids {
    private const val TAG = "FingertipsHelper"
    private const val TIMEOUT_MS = 3_000L
    private const val READ_JOIN_MS = 1_000L

    fun resolve(packageName: String): Int? {
        val output =
            try {
                listPackages(packageName)
            } catch (e: IOException) {
                Log.w(TAG, "Could not list package uids", e)
                return null
            } ?: return null
        return parsePackageUid(output, packageName)
    }

    private fun listPackages(packageName: String): String? {
        val process =
            ProcessBuilder(
                    "/system/bin/cmd",
                    "package",
                    "list",
                    "packages",
                    "-U",
                    packageName,
                )
                .redirectErrorStream(true)
                .start()
        val holder = arrayOfNulls<String>(1)
        val reader =
            thread(name = "package-uid", isDaemon = true) {
                holder[0] = process.inputStream.bufferedReader().use { it.readText() }
            }
        val finished =
            try {
                process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                Log.w(TAG, "Package uid lookup interrupted", e)
                return null
            }
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "Package uid lookup timed out")
            return null
        }
        reader.join(READ_JOIN_MS)
        val output = holder[0].orEmpty()
        if (process.exitValue() != 0) {
            Log.w(TAG, "Package uid lookup exited ${process.exitValue()}: ${output.trim()}")
            return null
        }
        return output
    }
}

/** `package:<name> uid:<n>` lines from `cmd package list packages -U`. */
internal fun parsePackageUid(output: String, packageName: String): Int? {
    val marker = "package:$packageName"
    val line = output.lineSequence().map { it.trim() }.firstOrNull { matchesPackage(it, marker) }
    return line?.let { UID_FIELD.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

private fun matchesPackage(line: String, marker: String): Boolean {
    if (!line.startsWith(marker)) return false
    val boundary = line.getOrNull(marker.length) ?: return true
    return boundary.isWhitespace()
}

private val UID_FIELD = Regex("""(?:^|\s)uid:(\d+)""")
