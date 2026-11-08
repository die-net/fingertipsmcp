package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.serialization.ContentConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCEmptyMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.nio.charset.Charset
import kotlinx.io.readByteArray
import kotlinx.serialization.serializer

/**
 * REST bodies use [ApiJson], whose `type` discriminator is how wait conditions are encoded. MCP
 * JSON-RPC uses [McpJson], which writes that field itself. `call.respond` on a JSON-RPC payload is
 * typed as `Any`, so the runtime class selects the serializer.
 */
internal object PhoneJsonConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        if (value == null) return null
        val text = encode(typeInfo, value)
        return TextContent(text, contentType.withCharset(charset))
    }

    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel,
    ): Any {
        val ktype = typeInfo.kotlinType ?: error("No Kotlin type for ${typeInfo.type}")
        val text = content.readBuffer().readByteArray().toString(charset)
        return ApiJson.decodeFromString(serializer(ktype), text) ?: error("Empty JSON")
    }

    private fun encode(typeInfo: TypeInfo, value: Any): String =
        when (value) {
            is JSONRPCMessage -> encodeMessage(value)
            is List<*> ->
                if (value.all { it is JSONRPCMessage }) {
                    @Suppress("UNCHECKED_CAST") // Every element was just checked.
                    McpJson.encodeToString(
                        serializer<List<JSONRPCMessage>>(),
                        value as List<JSONRPCMessage>,
                    )
                } else {
                    encodeApi(typeInfo, value)
                }
            else -> encodeApi(typeInfo, value)
        }

    private fun encodeApi(typeInfo: TypeInfo, value: Any): String {
        val ktype = typeInfo.kotlinType ?: error("No Kotlin type for ${value.javaClass.name}")
        return ApiJson.encodeToString(serializer(ktype), value)
    }

    private fun encodeMessage(message: JSONRPCMessage): String =
        when (message) {
            is JSONRPCResponse -> McpJson.encodeToString(serializer<JSONRPCResponse>(), message)
            is JSONRPCError -> McpJson.encodeToString(serializer<JSONRPCError>(), message)
            is JSONRPCRequest -> McpJson.encodeToString(serializer<JSONRPCRequest>(), message)
            is JSONRPCNotification ->
                McpJson.encodeToString(serializer<JSONRPCNotification>(), message)
            is JSONRPCEmptyMessage ->
                McpJson.encodeToString(serializer<JSONRPCEmptyMessage>(), message)
        }
}
