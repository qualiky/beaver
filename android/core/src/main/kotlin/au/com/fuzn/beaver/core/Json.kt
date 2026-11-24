package au.com.fuzn.beaver.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

/**
 * JSON settings that match Swift's synthesized Codable output: nil optionals are omitted and defaults are written.
 * Use this for backups so both platforms can read each other's files.
 */
val BeaverJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Codes a Kotlin sealed type in Swift's synthesized enum-with-payload shape:
 * `{"caseName": {"label": value}}`, with `{}` for a case without payload and `_0` for an unlabelled payload.
 */
internal abstract class SwiftEnumSerializer<T : Any>(name: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)

    abstract fun encodeCase(value: T): Pair<String, JsonObject>
    abstract fun decodeCase(name: String, payload: JsonObject): T

    override fun serialize(encoder: Encoder, value: T) {
        val (name, payload) = encodeCase(value)
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        json.encodeJsonElement(JsonObject(mapOf(name to payload)))
    }

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        val obj = json.decodeJsonElement().jsonObject
        val entry = obj.entries.singleOrNull() ?: throw SerializationException("Expected one case key")
        return decodeCase(entry.key, entry.value.jsonObject)
    }
}

internal fun JsonObject.intAt(key: String): Int =
    (this[key] ?: throw SerializationException("Missing $key")).jsonPrimitive.int

internal fun JsonObject.stringAt(key: String): String =
    (this[key] ?: throw SerializationException("Missing $key")).jsonPrimitive.content

internal fun payload(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is Int -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            else -> throw IllegalArgumentException("unsupported payload value")
        }
    }
)
