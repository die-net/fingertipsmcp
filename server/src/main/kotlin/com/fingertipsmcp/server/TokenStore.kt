package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.AtomicTextFile
import com.fingertipsmcp.core.randomToken
import com.fingertipsmcp.model.CreatedToken
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.TokenInfo
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Bearer tokens, stored only as SHA-256 hashes. It reads and writes its file on the caller's
 * thread, which must not be the main thread.
 */
class TokenStore(dir: File) : TokenGateway {
    @Serializable
    private data class Stored(
        val id: String,
        val name: String,
        val scopes: Set<Scope>,
        val hash: String,
        val createdAtMs: Long,
        val lastUsedAtMs: Long? = null,
    )

    private val atomic = AtomicTextFile(File(dir, "tokens.json"))
    private val random = SecureRandom()
    // Reified serializer<T>() rather than the plugin-generated companion, so detekt's type
    // resolution (which runs without the serialization compiler plugin) can analyse this file.
    private val serializer = serializer<List<Stored>>()
    private var tokens: List<Stored> = emptyList()
    private var loaded = false
    private var lastPersistMs = 0L
    private val _revision = MutableStateFlow(0)
    /** Bumps when the token set changes so the settings UI can refresh. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Synchronized
    override fun list(): List<TokenInfo> {
        ensureLoaded()
        return tokens.map { it.info() }
    }

    @Synchronized
    fun isEmpty(): Boolean {
        ensureLoaded()
        return tokens.isEmpty()
    }

    @Synchronized
    override fun create(name: String, scopes: Set<Scope>): CreatedToken {
        ensureLoaded()
        val secret = "ft_" + randomToken(random, 32)
        val stored =
            Stored(
                id = randomToken(random, 6),
                name = name,
                scopes = scopes,
                hash = hash(secret),
                createdAtMs = System.currentTimeMillis(),
            )
        tokens = tokens + stored
        persist()
        bumpRevision()
        return CreatedToken(stored.info(), secret)
    }

    /** Revokes every token named [name], then creates a replacement and returns it. */
    @Synchronized
    fun replaceNamed(name: String, scopes: Set<Scope>): CreatedToken {
        ensureLoaded()
        tokens = tokens.filterNot { it.name == name }
        return create(name, scopes)
    }

    @Synchronized
    override fun setScopes(id: String, scopes: Set<Scope>): TokenInfo? {
        ensureLoaded()
        val found = tokens.firstOrNull { it.id == id } ?: return null
        val updated = found.copy(scopes = scopes)
        tokens = tokens.map { if (it.id == id) updated else it }
        persist()
        bumpRevision()
        return updated.info()
    }

    @Synchronized
    override fun revoke(id: String): Boolean {
        ensureLoaded()
        val before = tokens.size
        tokens = tokens.filterNot { it.id == id }
        if (tokens.size == before) return false
        persist()
        bumpRevision()
        return true
    }

    /** Returns the token's info if [secret] is valid, updating its last-used time. */
    @Synchronized
    override fun authenticate(secret: String): TokenInfo? {
        ensureLoaded()
        val candidate = hash(secret).toByteArray()
        val match =
            tokens.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), candidate) }
                ?: return null
        val now = System.currentTimeMillis()
        tokens = tokens.map { if (it.id == match.id) it.copy(lastUsedAtMs = now) else it }
        if (now - lastPersistMs > PERSIST_INTERVAL_MS) persist()
        return match.info()
    }

    private fun ensureLoaded() {
        if (loaded) return
        tokens = atomic.read()?.let { ApiJson.decodeFromString(serializer, it) }.orEmpty()
        loaded = true
    }

    private fun persist() {
        lastPersistMs = System.currentTimeMillis()
        val text = ApiJson.encodeToString(serializer, tokens)
        atomic.write(text)
    }

    private fun bumpRevision() {
        _revision.value += 1
    }

    private fun Stored.info() = TokenInfo(id, name, scopes, createdAtMs, lastUsedAtMs)

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).toHexString()

    private companion object {
        const val PERSIST_INTERVAL_MS = 60_000L
    }
}
