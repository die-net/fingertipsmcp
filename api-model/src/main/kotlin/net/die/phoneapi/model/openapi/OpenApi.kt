package net.die.phoneapi.model.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.schema.JsonSchemas
import net.die.phoneapi.model.schema.SchemaConfig

private const val COMPONENTS = "#/components/schemas/"

// Android's ICU regex treats an unescaped `}` as a quantifier closer.
private val PATH_PARAM = Regex("""\{([^}]+)\}""")

/** OpenAPI 3.1 document whose component schemas come from the same walker as MCP tool schemas. */
public fun phoneApiOpenApi(): JsonObject = document

private val document: JsonObject by lazy { buildDocument() }

private fun buildDocument(): JsonObject {
    val schemas = JsonSchemas(SchemaConfig(), COMPONENTS)
    phoneApiEndpoints.forEach { endpoint ->
        endpoint.request?.descriptor?.let(schemas::schema)
        endpoint.response?.descriptor?.let(schemas::schema)
    }
    return buildJsonObject {
        put("openapi", "3.1.0")
        put(
            "info",
            buildJsonObject {
                put("title", "PhoneAPI")
                put("version", "0.0.0")
            },
        )
        put(
            "security",
            buildJsonArray { add(buildJsonObject { put("bearer", JsonArray(emptyList())) }) },
        )
        put("paths", paths(schemas))
        put(
            "components",
            buildJsonObject {
                put(
                    "securitySchemes",
                    buildJsonObject {
                        put(
                            "bearer",
                            buildJsonObject {
                                put("type", "http")
                                put("scheme", "bearer")
                            },
                        )
                    },
                )
                put("schemas", JsonObject(schemas.definitions))
            },
        )
    }
}

private fun paths(schemas: JsonSchemas): JsonObject {
    val grouped = LinkedHashMap<String, MutableList<ApiEndpoint>>()
    phoneApiEndpoints.forEach { endpoint ->
        grouped.getOrPut(endpoint.path) { ArrayList() }.add(endpoint)
    }
    return buildJsonObject {
        grouped.forEach { (path, operations) ->
            put(
                path,
                buildJsonObject {
                    operations.forEach { put(operationKey(it), operation(it, schemas)) }
                },
            )
        }
    }
}

private fun operationKey(endpoint: ApiEndpoint): String =
    when (endpoint.method) {
        ApiMethod.GET,
        ApiMethod.WEBSOCKET -> "get"
        ApiMethod.POST -> "post"
        ApiMethod.PUT -> "put"
        ApiMethod.PATCH -> "patch"
        ApiMethod.DELETE -> "delete"
    }

private fun operation(endpoint: ApiEndpoint, schemas: JsonSchemas): JsonObject = buildJsonObject {
    val scopes = endpoint.scopes.sorted().map(::scopeName)
    put("summary", endpoint.summary)
    val needs =
        if (scopes.isEmpty()) {
            " Any token may call it."
        } else {
            " Requires the ${scopes.joinToString(" or ")} scope."
        }
    put("description", endpoint.summary + needs)
    putJsonArray("x-scopes") { scopes.forEach(::add) }
    if (endpoint.method == ApiMethod.WEBSOCKET) put("x-websocket", true)
    pathParameters(endpoint.path)?.let { put("parameters", it) }
    endpoint.request?.let { request ->
        put(
            "requestBody",
            buildJsonObject {
                put("required", endpoint.requestRequired)
                put("content", jsonContent(schemas.schema(request.descriptor)))
            },
        )
    }
    endpoint.requestContentType?.let { type ->
        put(
            "requestBody",
            buildJsonObject {
                put("required", true)
                put("content", rawContent(type))
            },
        )
    }
    put("responses", responses(endpoint, schemas))
}

private fun responses(endpoint: ApiEndpoint, schemas: JsonSchemas): JsonObject {
    val websocket = endpoint.method == ApiMethod.WEBSOCKET
    val code = if (websocket) "101" else endpoint.status.toString()
    val description = if (websocket) "Switching Protocols" else endpoint.summary
    return buildJsonObject {
        put(
            code,
            buildJsonObject {
                put("description", description)
                responseContent(endpoint, schemas)?.let { put("content", it) }
            },
        )
    }
}

private fun responseContent(endpoint: ApiEndpoint, schemas: JsonSchemas): JsonObject? {
    val body = endpoint.response
    if (body != null) return jsonContent(schemas.schema(body.descriptor))
    val contentType = endpoint.responseContentType ?: return null
    return rawContent(contentType)
}

private fun rawContent(contentType: String): JsonObject = buildJsonObject {
    put(
        contentType,
        buildJsonObject {
            put(
                "schema",
                buildJsonObject {
                    put("type", "string")
                    put("contentMediaType", contentType)
                },
            )
        },
    )
}

private fun jsonContent(schema: JsonObject): JsonObject = buildJsonObject {
    put(ApiEndpoint.JSON, buildJsonObject { put("schema", schema) })
}

private fun pathParameters(path: String): JsonArray? {
    val names = PATH_PARAM.findAll(path).map { it.groupValues[1] }.toList()
    if (names.isEmpty()) return null
    return buildJsonArray {
        names.forEach { name ->
            add(
                buildJsonObject {
                    put("name", name)
                    put("in", "path")
                    put("required", true)
                    put("schema", buildJsonObject { put("type", "string") })
                }
            )
        }
    }
}

private fun scopeName(scope: Scope): String =
    serializer<Scope>().descriptor.getElementName(scope.ordinal)
