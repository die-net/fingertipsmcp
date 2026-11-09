package com.fingertipsmcp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.PairingInfo
import java.io.FileNotFoundException

/**
 * `fingertips pair` reads `content://<package>.pairing/token?name=...&scopes=...` with `adb shell
 * content read`, and each read mints a new token and returns its pairing JSON. `content read`
 * copies the file to adb's stdout, and nothing on the way logs it. Only shell and root may read it.
 */
class PairingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (mode != "r" || uri.path != PATH) throw FileNotFoundException(uri.path)
        // Android publishes a process's providers before Application.onCreate builds the graph,
        // so a read that started the process can arrive first.
        val graph =
            checkNotNull(FingertipsApp.awaitGraph(GRAPH_WAIT_MS)) {
                "FingertipsMCP has not started yet"
            }
        // AdbPairing refuses every uid but shell and root.
        val pairing =
            graph.adbPairing.pair(
                uid,
                uri.getQueryParameter("name"),
                uri.getQueryParameter("scopes"),
            )
        val bytes = ApiJson.encodeToString(PairingInfo.serializer(), pairing).toByteArray()
        val (read, write) = ParcelFileDescriptor.createPipe()
        // The JSON is far smaller than a pipe's buffer, so this write does not wait for the reader.
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        return read
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val PATH = "/token"
        const val GRAPH_WAIT_MS = 10_000L
    }
}
