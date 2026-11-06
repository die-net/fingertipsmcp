package net.die.phoneapi.model.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * kotlinx.serialization [kotlinx.serialization.json.Json] settings that change a document.
 *
 * [encodeDefaults] and [explicitNulls] match the app Json. Descriptors do not carry default values,
 * so a property with a Kotlin default is simply omitted from `required` and has no `default`
 * keyword. [explicitNulls] does not make a nullable property optional: a missing value still fails
 * decode unless the property has a default.
 */
public data class SchemaConfig(
    val classDiscriminator: String = "type",
    val encodeDefaults: Boolean = true,
    val explicitNulls: Boolean = false,
    val ignoreUnknownKeys: Boolean = true,
)

/** Walks descriptors into JSON Schema, sharing one definition map across several roots. */
@OptIn(ExperimentalSerializationApi::class)
public class JsonSchemas(
    public val config: SchemaConfig = SchemaConfig(),
    public val refPrefix: String = "#/\$defs/",
) {
    private val defs = LinkedHashMap<String, JsonObject>()
    private val names = HashMap<String, String>()
    private val visiting = HashSet<String>()
    private val referenced = HashSet<String>()

    public val definitions: Map<String, JsonObject>
        get() = defs

    /** A `$ref` for named types, or an inline schema for primitives, lists, maps, and enums. */
    public fun schema(descriptor: SerialDescriptor): JsonObject = place(descriptor)

    /**
     * The root schema inlined, so a tool or request body can be `type: object` rather than a
     * `$ref`. Named types the root points at are still entered in [definitions].
     */
    public fun inline(descriptor: SerialDescriptor): JsonObject {
        val raw = unwrap(descriptor)
        val body =
            if (raw.kind == StructureKind.CLASS) {
                inlineClass(raw)
            } else if (raw.kind == StructureKind.OBJECT) {
                defineObject(raw)
                defs.getValue(defName(raw))
            } else if (raw.kind == PolymorphicKind.SEALED) {
                defineSealed(raw)
                defs.getValue(defName(raw))
            } else {
                structural(raw)
            }
        return if (descriptor.isNullable) nullable(body) else body
    }

    /**
     * One object schema with the properties of every class in [descriptors], for a JSON object that
     * each of them decodes in part, ignoring the others' fields. A property two of them name keeps
     * the first one's schema.
     */
    public fun merged(descriptors: List<SerialDescriptor>): JsonObject {
        val properties = LinkedHashMap<String, JsonElement>()
        val required = LinkedHashSet<String>()
        for (descriptor in descriptors) {
            val part = inline(descriptor)
            check(part["type"] == JsonPrimitive("object")) {
                "Only classes merge, found ${descriptor.serialName}"
            }
            (part["properties"] as? JsonObject)?.forEach { (name, schema) ->
                properties.putIfAbsent(name, schema)
            }
            (part["required"] as? JsonArray)?.forEach { name ->
                (name as? JsonPrimitive)?.let { required += it.content }
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", strings(required.toList()))
            if (!config.ignoreUnknownKeys) put("additionalProperties", false)
        }
    }

    private fun place(descriptor: SerialDescriptor): JsonObject {
        val body = structural(unwrap(descriptor))
        return if (descriptor.isNullable) nullable(body) else body
    }

    private fun unwrap(descriptor: SerialDescriptor): SerialDescriptor {
        var current = descriptor.nonNullOriginal
        while (current.isInline) current = current.getElementDescriptor(0).nonNullOriginal
        return current
    }

    private fun structural(descriptor: SerialDescriptor): JsonObject =
        when (descriptor.kind) {
            SerialKind.ENUM -> enumSchema(descriptor)
            PrimitiveKind.BOOLEAN -> jsonType("boolean")
            PrimitiveKind.STRING,
            PrimitiveKind.CHAR -> jsonType("string")
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            PrimitiveKind.LONG -> jsonType("integer")
            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE -> jsonType("number")
            StructureKind.LIST -> listSchema(descriptor)
            StructureKind.MAP -> mapSchema(descriptor)
            StructureKind.CLASS -> defineClass(descriptor, discriminator = null)
            StructureKind.OBJECT -> defineObject(descriptor)
            PolymorphicKind.SEALED -> defineSealed(descriptor)
            PolymorphicKind.OPEN -> openSchema(descriptor)
            SerialKind.CONTEXTUAL -> JsonObject(emptyMap())
        }

    private fun openSchema(descriptor: SerialDescriptor): JsonObject =
        if (descriptor.elementsCount > 0) defineSealed(descriptor) else JsonObject(emptyMap())

    private fun inlineClass(descriptor: SerialDescriptor): JsonObject {
        val name = defName(descriptor)
        defs[name]?.let {
            return it
        }
        visiting += descriptor.serialName
        val built = classSchema(descriptor, discriminator = null)
        visiting -= descriptor.serialName
        if (name in referenced) defs[name] = built
        return built
    }

    private fun defineClass(descriptor: SerialDescriptor, discriminator: String?): JsonObject {
        val name = defName(descriptor)
        if (descriptor.serialName in visiting || name in defs) return ref(name)
        visiting += descriptor.serialName
        val built = classSchema(descriptor, discriminator)
        visiting -= descriptor.serialName
        defs[name] = built
        return ref(name)
    }

    private fun defineObject(descriptor: SerialDescriptor): JsonObject {
        val name = defName(descriptor)
        if (name !in defs) defs[name] = jsonType("object")
        return ref(name)
    }

    private fun defineSealed(descriptor: SerialDescriptor): JsonObject {
        val name = defName(descriptor)
        if (descriptor.serialName in visiting || name in defs) return ref(name)
        val variants = variantHolder(descriptor)
        visiting += descriptor.serialName
        val oneOf = buildJsonArray {
            for (index in 0 until variants.elementsCount) {
                add(
                    variantSchema(
                        variants.getElementDescriptor(index),
                        variants.getElementName(index),
                    )
                )
            }
        }
        val body = buildJsonObject {
            descriptionOf(descriptor)?.let { put("description", it) }
            put("oneOf", oneOf)
            put("discriminator", buildJsonObject { put("propertyName", config.classDiscriminator) })
        }
        visiting -= descriptor.serialName
        defs[name] = body
        return ref(name)
    }

    /** Sealed descriptors store subclasses on the `value` element, not as their own properties. */
    private fun variantHolder(descriptor: SerialDescriptor): SerialDescriptor {
        check(descriptor.elementsCount >= 2) {
            "Expected a sealed descriptor with type and value elements, found ${descriptor.serialName}"
        }
        return descriptor.getElementDescriptor(1)
    }

    private fun variantSchema(descriptor: SerialDescriptor, serial: String): JsonObject {
        if (descriptor.kind != StructureKind.OBJECT) return defineClass(descriptor, serial)
        val name = defName(descriptor)
        if (name !in defs) defs[name] = discriminatorObject(serial, descriptor)
        return ref(name)
    }

    private fun discriminatorObject(serial: String, descriptor: SerialDescriptor): JsonObject =
        buildJsonObject {
            descriptionOf(descriptor)?.let { put("description", it) }
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(config.classDiscriminator, buildJsonObject { put("const", serial) })
                },
            )
            put("required", strings(listOf(config.classDiscriminator)))
        }

    private fun classSchema(descriptor: SerialDescriptor, discriminator: String?): JsonObject {
        val properties = LinkedHashMap<String, JsonElement>()
        val required = ArrayList<String>()
        if (discriminator != null) {
            properties[config.classDiscriminator] = buildJsonObject { put("const", discriminator) }
            required += config.classDiscriminator
        }
        for (index in 0 until descriptor.elementsCount) {
            val property = descriptor.getElementName(index)
            val element = descriptor.getElementDescriptor(index)
            val optional = descriptor.isElementOptional(index)
            // An omitted optional property already means null, so `anyOf` with null only adds
            // noise.
            val body = if (optional) structural(unwrap(element)) else place(element)
            properties[property] = described(body, descriptor.getElementAnnotations(index))
            if (!optional) required += property
        }
        return buildJsonObject {
            put("type", "object")
            descriptionOf(descriptor)?.let { put("description", it) }
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", strings(required))
            if (!config.ignoreUnknownKeys) put("additionalProperties", false)
        }
    }

    private fun listSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", place(descriptor.getElementDescriptor(0)))
    }

    private fun mapSchema(descriptor: SerialDescriptor): JsonObject {
        val key = descriptor.getElementDescriptor(0)
        check(key.kind == PrimitiveKind.STRING && !key.isNullable) {
            "JSON Schema maps need string keys, found ${key.serialName}"
        }
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", place(descriptor.getElementDescriptor(1)))
        }
    }

    private fun enumSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "string")
        put(
            "enum",
            buildJsonArray {
                for (index in 0 until descriptor.elementsCount) add(
                    JsonPrimitive(descriptor.getElementName(index))
                )
            },
        )
    }

    private fun described(schema: JsonObject, annotations: List<Annotation>): JsonObject {
        val text = annotations.filterIsInstance<Doc>().firstOrNull()?.value ?: return schema
        return buildJsonObject {
            schema.forEach { key, value -> put(key, value) }
            put("description", text)
        }
    }

    private fun descriptionOf(descriptor: SerialDescriptor): String? =
        descriptor.annotations.filterIsInstance<Doc>().firstOrNull()?.value

    private fun defName(descriptor: SerialDescriptor): String =
        names.getOrPut(descriptor.serialName) {
            val base = descriptor.serialName.substringAfterLast('.').substringAfterLast('$')
            val stem = base.ifBlank { "Type" }
            var name = stem
            var suffix = 2
            while (name in names.values) {
                name = stem + suffix
                suffix++
            }
            name
        }

    private fun ref(name: String): JsonObject {
        referenced += name
        return buildJsonObject { put("\$ref", refPrefix + name) }
    }

    private fun nullable(schema: JsonObject): JsonObject = buildJsonObject {
        put(
            "anyOf",
            buildJsonArray {
                add(schema)
                add(jsonType("null"))
            },
        )
    }

    private fun jsonType(type: String): JsonObject = buildJsonObject { put("type", type) }

    private fun strings(values: List<String>): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })
}
