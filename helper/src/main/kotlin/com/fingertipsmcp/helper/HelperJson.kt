package com.fingertipsmcp.helper

import kotlinx.serialization.json.Json

/** The helper's JSON for Binder payloads. It matches the app's `ApiJson`. */
internal val HelperJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
