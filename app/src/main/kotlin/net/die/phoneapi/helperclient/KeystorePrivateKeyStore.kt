package net.die.phoneapi.helperclient

import android.util.Log
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import net.die.phoneapi.core.KeystoreAes

/**
 * Persists the ADB private key Kadb uses to pair with wireless debugging.
 *
 * The key has to be readable (ADB authentication signs with it), so it cannot live only inside the
 * Android Keystore. A non-exportable Keystore key encrypts the PEM on disk instead.
 *
 * The key and the pairing are deleted only when the stored key can never be read again: the
 * Keystore key is gone, or the blob doesn't match it. A failure that may pass, such as the Keystore
 * being unavailable, is thrown, so Kadb fails this connection instead of making a new key and
 * losing the pairing.
 */
internal class KeystorePrivateKeyStore(
    dir: File,
    private val encrypt: (ByteArray) -> ByteArray = KeystoreAes(ALIAS)::encrypt,
    private val decrypt: (ByteArray) -> ByteArray? = KeystoreAes(ALIAS)::decrypt,
) : KadbPrivateKeyStore {
    private val file = File(dir, "adb-key.bin")
    private val paired = File(dir, "adb-paired")

    /** True after a pairing or a wireless shell has been accepted by this device. */
    val isPaired: Boolean
        get() = paired.isFile && file.isFile

    fun markPaired() {
        paired.writeText("1")
    }

    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.isFile) return null
        val blob =
            try {
                file.readBytes()
            } catch (e: IOException) {
                throw IllegalStateException("Could not read the ADB key", e)
            }
        val plain =
            try {
                decrypt(blob)
            } catch (e: AEADBadTagException) {
                return forget("does not match its Keystore key", e)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Could not decrypt the ADB key now", e)
            }
        // decrypt returns null only when the Keystore key is gone or the blob is cut short.
        return plain ?: forget("can't be decrypted", null)
    }

    private fun forget(why: String, cause: Exception?): ByteArray? {
        Log.w(TAG, "The ADB key $why; it and the pairing will be created again", cause)
        clear()
        return null
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        val blob =
            try {
                encrypt(privateKeyPem)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Could not encrypt the ADB key", e)
            }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeBytes(blob)
        } catch (e: IOException) {
            tmp.delete()
            throw IllegalStateException("Could not store the ADB key", e)
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not replace the ADB key")
        }
    }

    override fun clear() {
        file.delete()
        paired.delete()
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val ALIAS = "phoneapi_adb_key"
    }
}
