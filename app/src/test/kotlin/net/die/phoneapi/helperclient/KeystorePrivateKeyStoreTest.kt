package net.die.phoneapi.helperclient

import java.io.File
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The Keystore cipher is faked; what matters is which failures cost the pairing. */
class KeystorePrivateKeyStoreTest {
    @TempDir lateinit var dir: File

    private val pem = "-----BEGIN PRIVATE KEY-----".toByteArray()

    private fun store(decrypt: (ByteArray) -> ByteArray?) =
        KeystorePrivateKeyStore(dir, encrypt = { it.reversedArray() }, decrypt = decrypt)

    private fun pairedStore(decrypt: (ByteArray) -> ByteArray?): KeystorePrivateKeyStore {
        val store = store(decrypt)
        store.writePrivateKeyPemAtomic(pem)
        store.markPaired()
        return store
    }

    @Test
    fun `a stored key reads back`() {
        val store = pairedStore { it.reversedArray() }
        assertArrayEquals(pem, store.readPrivateKeyPem())
        assertTrue(store.isPaired)
    }

    @Test
    fun `a Keystore that fails for now keeps the key and the pairing`() {
        val store = pairedStore { throw KeyStoreException("Keystore is locked") }
        assertThrows<IllegalStateException> { store.readPrivateKeyPem() }
        assertTrue(store.isPaired)
    }

    @Test
    fun `a blob that doesn't match the key is dropped`() {
        val store = pairedStore { throw AEADBadTagException() }
        assertNull(store.readPrivateKeyPem())
        assertFalse(store.isPaired)
    }

    @Test
    fun `a missing Keystore key is dropped`() {
        val store = pairedStore { null }
        assertNull(store.readPrivateKeyPem())
        assertFalse(store.isPaired)
    }

    @Test
    fun `a write that can't put the key in place fails and leaves no temp file`() {
        val store = pairedStore { it.reversedArray() }
        // A directory where the key file goes makes the rename fail.
        val key = File(dir, "adb-key.bin")
        key.delete()
        key.mkdir()
        assertThrows<IllegalStateException> { store.writePrivateKeyPemAtomic(pem) }
        assertFalse(File(dir, "adb-key.bin.tmp").exists())
    }
}
