package com.fingertipsmcp

import android.os.Process
import com.fingertipsmcp.model.PairingInfo
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.TokenGateway

/** The shell user's uid. Android's own constant for it is hidden. */
internal const val SHELL_UID = 2000

/** Whether [uid] is the adb shell or root, the only callers trusted to provision FingertipsMCP. */
internal fun isShellOrRoot(uid: Int): Boolean = uid == SHELL_UID || uid == Process.ROOT_UID

/**
 * Mints the token that `fingertips pair` saves. [PairingProvider] hands the result to `adb shell
 * content read`, which prints it to adb's stdout, so the token never passes through a broadcast
 * result, which Android 16 and 17 write to the system log.
 */
internal class AdbPairing(private val tokens: TokenGateway, private val port: () -> Int) {
    /**
     * Mints a token named [name] (default `adb`) with [scopes], a comma-separated list that grants
     * every scope when null. Throws [SecurityException] unless [callingUid] is shell or root, and
     * [IllegalArgumentException] for a bad scope list.
     */
    fun pair(callingUid: Int, name: String?, scopes: String?): PairingInfo {
        if (!isShellOrRoot(callingUid)) throw SecurityException("Not allowed")
        val granted =
            when (val parsed = parseScopeList(scopes)) {
                is Parse.Error -> throw IllegalArgumentException(parsed.message)
                is Parse.Ok -> parsed.value
            }
        val tokenName = name ?: "adb"
        val created = tokens.create(tokenName, granted)
        return PairingInfo(
            host = "127.0.0.1",
            port = port(),
            token = created.token,
            name = tokenName,
        )
    }
}

/** Null [raw] grants every scope. Blank text and unknown names are errors. */
internal fun parseScopeList(raw: String?): Parse<Set<Scope>> {
    if (raw == null) return Parse.Ok(Scope.entries.toSet())
    val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return Parse.Error("scopes must name at least one scope")
    val scopes = names.map { name ->
        Scope.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Parse.Error("unknown scope $name")
    }
    return Parse.Ok(scopes.toSet())
}
