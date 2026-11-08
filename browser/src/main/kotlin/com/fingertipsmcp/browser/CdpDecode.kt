package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

inline fun <reified T> JsonElement.decodeCdp(): T? =
    try {
        ApiJson.decodeFromJsonElement<T>(this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
