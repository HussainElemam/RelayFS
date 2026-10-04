package io.relayfs.android.core.provider.pcloud.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/** pCloud sends an unsigned 64-bit decimal integer, not a signed JVM Long. */
object RemoteHashSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("RemoteHash", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val value = if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content else decoder.decodeString()
        return value.toULongOrNull()?.toString() ?: throw SerializationException("Invalid pCloud hash")
    }
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
