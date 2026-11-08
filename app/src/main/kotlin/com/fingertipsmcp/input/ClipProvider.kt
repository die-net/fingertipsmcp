package com.fingertipsmcp.input

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the image FingertipsMCP last put on the clipboard. It is not exported: the clipboard
 * grants each app that reads the clip access to its URI. Some apps ask for the file's name and size
 * first, so [query] answers those.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = type(uri)?.mimeType

    @Suppress("MissingUseCall") // The caller closes the cursor.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment
        if (name == null || type(uri) == null || !NAME.matches(name)) {
            throw FileNotFoundException(uri.toString())
        }
        val file = File(dir(checkNotNull(context)), name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    private fun type(uri: Uri): ImageType? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.let(ImageType::ofExtension)

    companion object {
        private val NAME = Regex("[0-9a-f]{32}\\.[a-z]+")

        fun authority(context: Context): String = "${context.packageName}.clip"

        fun dir(context: Context): File = File(context.cacheDir, "clip")
    }
}
