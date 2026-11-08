package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.AtomicTextFile
import com.fingertipsmcp.core.randomToken
import com.fingertipsmcp.model.CreatedToken
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.TokenInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Bearer tokens, stored only as SHA-256 hashes. It reads and writes its file on the caller's
 * thread, which must not be the main thread.
 *
 * More than one process may keep a store on the same file, as `fingertips-chrome token create` does
 * beside a running server. So every change reads the file again and writes it back while holding an
 * OS lock on `tokens.json.lock`, and a store reads the file again before answering when another one
 * has replaced it since.
 */
class TokenStore(dir: File, private val clock: () -> Long = System::currentTimeMillis) :
    TokenGateway {
    @Serializable
    private data class Stored(
        val id: String,
        val name: String,
        val scopes: Set<Scope>,
        val hash: String,
        val createdAtMs: Long,
        val lastUsedAtMs: Long? = null,
    )

    /** What the file was when this store last read or wrote it, to tell when another has since. */
    private data class Stamp(val key: Any?, val modifiedNs: Long, val size: Long)

    private val file = File(dir, "tokens.json")
    private val lockFile = File(dir, "tokens.json.lock")
    private val atomic = AtomicTextFile(file)
    private val random = SecureRandom()
    // Reified serializer<T>() rather than the plugin-generated companion, so detekt's type
    // resolution (which runs without the serialization compiler plugin) can analyse this file.
    private val serializer = serializer<List<Stored>>()
    private var tokens: List<Stored> = emptyList()
    /** The file as of [tokens], or null before the first read. */
    private var stamp: Stamp? = null
    /** Last-used times recorded since the last write, by token id. */
    private val unsavedUse = HashMap<String, Long>()
    private var lastPersistMs = 0L
    private val _revision = MutableStateFlow(0)
    /** Bumps when the token set changes so the settings UI can refresh. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Synchronized
    override fun list(): List<TokenInfo> {
        refresh()
        return tokens.map { it.info() }
    }

    @Synchronized
    fun isEmpty(): Boolean {
        refresh()
        return tokens.isEmpty()
    }

    @Synchronized
    override fun create(name: String, scopes: Set<Scope>): CreatedToken = add(name, scopes) { it }

    /** Revokes every token named [name], then creates a replacement and returns it. */
    @Synchronized
    fun replaceNamed(name: String, scopes: Set<Scope>): CreatedToken =
        add(name, scopes) { current -> current.filterNot { it.name == name } }

    @Synchronized
    override fun setScopes(id: String, scopes: Set<Scope>): TokenInfo? {
        var updated: Stored? = null
        change { current ->
            current.map { token ->
                if (token.id == id) token.copy(scopes = scopes).also { updated = it } else token
            }
        }
        return updated?.info()
    }

    @Synchronized
    override fun revoke(id: String): Boolean {
        var found = false
        change { current ->
            current.filterNot { it.id == id }.also { found = it.size != current.size }
        }
        return found
    }

    /** Returns the token's info if [secret] is valid, updating its last-used time. */
    @Synchronized
    override fun authenticate(secret: String): TokenInfo? {
        refresh()
        val candidate = hash(secret).toByteArray()
        val match =
            tokens.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), candidate) }
                ?: return null
        val now = clock()
        unsavedUse[match.id] = now
        tokens = tokens.map { if (it.id == match.id) it.copy(lastUsedAtMs = now) else it }
        if (now - lastPersistMs > PERSIST_INTERVAL_MS) change { it }
        return match.info()
    }

    private fun add(
        name: String,
        scopes: Set<Scope>,
        before: (List<Stored>) -> List<Stored>,
    ): CreatedToken {
        val secret = "ft_" + randomToken(random, 32)
        val stored =
            Stored(
                id = randomToken(random, 6),
                name = name,
                scopes = scopes,
                hash = hash(secret),
                createdAtMs = clock(),
            )
        change { current -> before(current) + stored }
        return CreatedToken(stored.info(), secret)
    }

    /**
     * Reads the file, applies [update] to what it holds, and writes the result, all under the file
     * lock, so a change another process made in between is kept. Last-used times this store has not
     * written yet go in too. The file is not written when nothing changed.
     */
    private fun change(update: (List<Stored>) -> List<Stored>) {
        withFileLock {
            val (current, read) = readFile()
            val used = current.map { token ->
                val at = unsavedUse[token.id] ?: return@map token
                if ((token.lastUsedAtMs ?: 0) >= at) token else token.copy(lastUsedAtMs = at)
            }
            val next = update(used)
            if (next != current) {
                atomic.write(ApiJson.encodeToString(serializer, next))
                stamp = stampOf()
            } else {
                stamp = read
            }
            unsavedUse.clear()
            lastPersistMs = clock()
            val shown = tokens
            tokens = next
            if (shown.tokenSet() != next.tokenSet()) bumpRevision()
        }
    }

    /** Reads the file again if another store has replaced it since this one last did. */
    private fun refresh() {
        val now = stampOf()
        if (now == stamp) return
        val before = tokens
        val (current, read) = readFile()
        // Keep this store's own last-used times until they are written.
        tokens = current.map { token ->
            unsavedUse[token.id]?.let { token.copy(lastUsedAtMs = it) } ?: token
        }
        stamp = read
        if (before.tokenSet() != tokens.tokenSet()) bumpRevision()
    }

    /**
     * The tokens in the file and its stamp from before the read. A write between the two leaves the
     * stamp behind the text, so the next [refresh] reads again rather than missing it.
     */
    private fun readFile(): Pair<List<Stored>, Stamp> {
        val read = stampOf()
        val text = atomic.read()
        return text?.let { ApiJson.decodeFromString(serializer, it) }.orEmpty() to read
    }

    private fun stampOf(): Stamp =
        try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            Stamp(
                attributes.fileKey(),
                attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                attributes.size(),
            )
        } catch (_: NoSuchFileException) {
            MISSING
        }

    /**
     * Runs [block] holding the lock on [lockFile]. An OS file lock is held by the whole process,
     * and a second lock on it from the same process throws, so stores on one directory in one
     * process take an in-process lock first.
     */
    private fun <T> withFileLock(block: () -> T): T {
        lockFile.absoluteFile.parentFile.mkdirs()
        val local = processLocks.computeIfAbsent(lockFile.canonicalPath) { Any() }
        return synchronized(local) {
            RandomAccessFile(lockFile, "rw").use { raf -> raf.channel.lock().use { block() } }
        }
    }

    /** What the settings UI shows of the tokens, which [revision] says has changed. */
    private fun List<Stored>.tokenSet() = map { Triple(it.id, it.name, it.scopes) }

    private fun bumpRevision() {
        _revision.value += 1
    }

    private fun Stored.info() = TokenInfo(id, name, scopes, createdAtMs, lastUsedAtMs)

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).toHexString()

    private companion object {
        const val PERSIST_INTERVAL_MS = 60_000L
        val MISSING = Stamp(key = null, modifiedNs = -1, size = -1)
        /** One lock object per lock file, shared by every store in this process. */
        val processLocks = ConcurrentHashMap<String, Any>()
    }
}
