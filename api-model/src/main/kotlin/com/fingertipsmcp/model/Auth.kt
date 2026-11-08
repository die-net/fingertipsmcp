package com.fingertipsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class Scope {
    /** Read UI, device state, events, snapshots. */
    @SerialName("observe") OBSERVE,
    /** Input, app control, wake/unlock. */
    @SerialName("control") CONTROL,
    /** CDP access to Chrome and WebViews. */
    @SerialName("browser") BROWSER,
    /** Video and audio streams. */
    @SerialName("stream") STREAM,
    /** Token management and settings. */
    @SerialName("admin") ADMIN,
}

@Serializable
public data class TokenInfo(
    val id: String,
    val name: String,
    val scopes: Set<Scope>,
    val createdAtMs: Long,
    val lastUsedAtMs: Long? = null,
)

@Serializable
public data class CreateTokenRequest(
    val name: String,
    val scopes: Set<Scope> = Scope.entries.toSet(),
)

/** Replaces a token's scopes. The token keeps its secret, so its clients go on using it. */
@Serializable public data class UpdateTokenRequest(val scopes: Set<Scope>)

/** Returned once on creation; [token] is never retrievable again. */
@Serializable public data class CreatedToken(val info: TokenInfo, val token: String)

/**
 * What a client needs after `CREATE_TOKEN` over ADB. [host] is `127.0.0.1` once `adb forward` is
 * installed; [port] is the local TCP port that reaches the abstract socket.
 */
@Serializable
public data class PairingInfo(
    val host: String,
    val port: Int,
    val token: String,
    val name: String,
)
