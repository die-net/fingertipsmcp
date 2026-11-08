package com.fingertipsmcp.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.RemoteException
import android.util.Log
import androidx.core.graphics.scale
import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** PNG screenshots from the helper's `screencap`. */
class Screenshotter(
    private val ioDispatcher: CoroutineDispatcher,
    private val helperPng: suspend () -> ByteArray?,
) {
    suspend fun png(scale: Float = 1f): ByteArray {
        if (scale !in MIN_SCALE..1f) throw ApiException.badRequest("scale must be $MIN_SCALE..1")
        val bytes =
            try {
                helperPng()
            } catch (e: RemoteException) {
                Log.w(TAG, "Helper screenshot failed", e)
                throw ApiException.helperDropped(e)
            }
        if (bytes == null || !bytes.startsWithPng()) {
            throw ApiException.unavailable(
                "screenshot_failed",
                "The helper did not return a PNG. The screen may be off, or the capture was empty.",
            )
        }
        return scalePng(bytes, scale)
    }

    private suspend fun scalePng(bytes: ByteArray, scale: Float): ByteArray {
        if (scale >= 1f) return bytes
        val bitmap =
            withContext(ioDispatcher) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                ?: throw ApiException.unavailable(
                    "screenshot_failed",
                    "Could not decode the helper PNG",
                )
        return withContext(ioDispatcher) { encode(bitmap, scale) }
    }

    private fun encode(bitmap: Bitmap, scale: Float): ByteArray {
        val scaled =
            if (scale >= 1f) bitmap
            else
                bitmap.scale(
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                )
        try {
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun ByteArray.startsWithPng(): Boolean =
        size >= PNG_SIGNATURE.size && copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

    private companion object {
        const val TAG = "FingertipsScreenshot"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        const val MIN_SCALE = 0.05f
        const val PNG_QUALITY = 100
    }
}
