package dev.jasonpearson.motiondots.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Messages sent by the Mac that the phone understands. Unknown types parse to null. */
sealed interface Incoming {
    data class Welcome(val host: String?, val rateHz: Int?) : Incoming

    data class Pong(val t: Double?) : Incoming

    data class Config(val rateHz: Int?) : Incoming
}

/** Wire protocol v1 encoder/decoder. See docs/PROTOCOL.md. Pure Kotlin, no android.* imports. */
object Messages {
    const val PROTOCOL_VERSION = 1
    const val MIN_RATE_HZ = 20
    const val MAX_RATE_HZ = 60
    const val DEFAULT_RATE_HZ = 50

    fun isAdoptableRate(hz: Int?): Boolean = hz != null && hz in MIN_RATE_HZ..MAX_RATE_HZ

    fun hello(name: String, rateHz: Int): String =
        buildJsonObject {
                put("type", "hello")
                put("v", PROTOCOL_VERSION)
                put("platform", "android")
                put("name", name)
                put("rateHz", rateHz)
            }
            .toString()

    fun ping(t: Double): String {
        val sb = StringBuilder(40)
        sb.append("{\"type\":\"ping\",\"t\":")
        appendFixed(sb, t)
        sb.append('}')
        return sb.toString()
    }

    fun motion(t: Double, a: FloatArray, g: FloatArray): String {
        val sb = StringBuilder(128)
        appendMotion(sb, t, a, g)
        return sb.toString()
    }

    /** Hot path (up to 60 Hz): appends a motion frame to [sb] with 4 fixed decimals. */
    fun appendMotion(sb: StringBuilder, t: Double, a: FloatArray, g: FloatArray) {
        sb.append("{\"type\":\"motion\",\"t\":")
        appendFixed(sb, t)
        sb.append(",\"a\":[")
        appendFixed(sb, a[0].toDouble())
        sb.append(',')
        appendFixed(sb, a[1].toDouble())
        sb.append(',')
        appendFixed(sb, a[2].toDouble())
        sb.append("],\"g\":[")
        appendFixed(sb, g[0].toDouble())
        sb.append(',')
        appendFixed(sb, g[1].toDouble())
        sb.append(',')
        appendFixed(sb, g[2].toDouble())
        sb.append("]}")
    }

    /** Locale independent fixed 4 decimal formatting. NaN and infinities become 0. */
    internal fun appendFixed(sb: StringBuilder, value: Double) {
        val v = if (value.isNaN() || value.isInfinite()) 0.0 else value.coerceIn(-1e12, 1e12)
        val scaled = Math.round(v * 10_000.0)
        val abs = if (scaled < 0) -scaled else scaled
        if (scaled < 0) sb.append('-')
        sb.append(abs / 10_000)
        sb.append('.')
        val frac = (abs % 10_000).toInt()
        if (frac < 1000) sb.append('0')
        if (frac < 100) sb.append('0')
        if (frac < 10) sb.append('0')
        sb.append(frac)
    }

    /** Parses a Mac to phone frame. Returns null for malformed JSON or unknown types. */
    fun parse(text: String): Incoming? {
        val obj =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
        return when (obj.str("type")) {
            "welcome" -> Incoming.Welcome(obj.str("host"), obj.int("rateHz"))
            "pong" -> Incoming.Pong(obj.num("t"))
            "config" -> Incoming.Config(obj.int("rateHz"))
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        num(key)?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
}
