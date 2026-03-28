package party.qwer.iris.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@OptIn(ExperimentalSerializationApi::class)
object StrictLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        if (element === JsonNull) return null
        val primitive = element.jsonPrimitive
        if (primitive.toString().startsWith("\"")) {
            throw SerializationException("expected numeric long literal")
        }
        return primitive.longOrNull ?: throw SerializationException("expected numeric long literal")
    }

    override fun serialize(
        encoder: Encoder,
        value: Long?,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
            return
        }
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeLong(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object StrictIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        if (element === JsonNull) return null
        val primitive = element.jsonPrimitive
        if (primitive.toString().startsWith("\"")) {
            throw SerializationException("expected numeric int literal")
        }
        return primitive.intOrNull ?: throw SerializationException("expected numeric int literal")
    }

    override fun serialize(
        encoder: Encoder,
        value: Int?,
    ) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
            return
        }
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}
