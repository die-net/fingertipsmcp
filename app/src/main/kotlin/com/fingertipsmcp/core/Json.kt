package com.fingertipsmcp.core

import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
