package com.fingertipsmcp.model.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Enum serializer whose descriptor lists [wires] (the `@SerialName` values) and whose decoder
 * accepts any capitalization, with surrounding whitespace ignored. Services used to lowercase these
 * strings themselves; decoding does that now so a bad value fails before the handler runs.
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
public open class LenientEnumSerializer<T : Enum<T>>
protected constructor(
    private val serialName: String,
    private val wires: List<String>,
    private val values: () -> Array<T>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor(serialName, SerialKind.ENUM) {
            wires.forEach { wire ->
                element(wire, buildSerialDescriptor(wire, StructureKind.OBJECT))
            }
        }

    public fun wire(value: T): String = wires[value.ordinal]

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(wire(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString().trim()
        val index = wires.indexOfFirst { it.equals(raw, ignoreCase = true) }
        if (index < 0) {
            throw SerializationException(
                "$serialName has no value '$raw'. Known values: ${wires.joinToString(", ")}"
            )
        }
        return values()[index]
    }
}
