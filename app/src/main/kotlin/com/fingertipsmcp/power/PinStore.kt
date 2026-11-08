package com.fingertipsmcp.power

import android.util.Log
import com.fingertipsmcp.core.KeystoreAes
import com.fingertipsmcp.model.ApiException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** A lock-screen PIN this app can type: 4 to 16 digits, which is what Android accepts. */
internal fun normalizePin(pin: String): String {
    val trimmed = pin.trim()
    val digits = trimmed.length in PIN_LENGTH && trimmed.all { it.isDigit() }
    if (!digits) {
        throw ApiException.badRequest(
            "pin must be ${PIN_LENGTH.first} to ${PIN_LENGTH.last} digits"
        )
    }
    return trimmed
}

private val PIN_LENGTH = 4..16

/**
 * The device's lock-screen PIN, encrypted with an AES key that never leaves the Android Keystore.
 * It is write-only as far as the API is concerned: `PUT /v1/device/pin` and the ADB `SET_PIN`
 * broadcast store it, and only [PowerServiceImpl] ever reads it back.
 *
 * The key deliberately doesn't require an unlocked device, since the whole point is to use it while
 * the keyguard is up. It is still bound to this app and to the device's hardware-backed keystore.
 */
class PinStore(dir: File) {
    private val file = File(dir, "lockpin.bin")
    private val cipher = KeystoreAes(ALIAS)

    val isSet: Boolean
        get() = file.exists()

    /** The stored PIN, or null when none is set or it can no longer be decrypted. */
    fun read(): String? =
        try {
            decrypt()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Stored PIN could not be decrypted", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Stored PIN could not be read", e)
            null
        }

    private fun decrypt(): String? {
        if (!file.exists()) return null
        val plain = cipher.decrypt(file.readBytes()) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    /** Stores [pin], or clears it when [pin] is null or blank. A non-blank value must be a PIN. */
    fun write(pin: String?) {
        if (pin.isNullOrBlank()) {
            file.delete()
            return
        }
        val normalized = normalizePin(pin)
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.encrypt(normalized.toByteArray(Charsets.UTF_8)))
        } catch (e: GeneralSecurityException) {
            throw ApiException(500, "pin_store_failed", "Could not encrypt the PIN", cause = e)
        } catch (e: IOException) {
            throw ApiException(500, "pin_store_failed", "Could not write the PIN", cause = e)
        }
    }

    private companion object {
        const val TAG = "FingertipsPower"
        const val ALIAS = "fingertips_lock_pin"
    }
}
