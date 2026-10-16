package dev.jasonpearson.motiondots.protocol

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun helloMatchesProtocol() {
        val o = obj(Messages.hello("Pixel 9", 50))
        assertEquals("hello", o["type"]!!.jsonPrimitive.content)
        assertEquals(1, o["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("android", o["platform"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", o["name"]!!.jsonPrimitive.content)
        assertEquals(50, o["rateHz"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun helloEscapesName() {
        val o = obj(Messages.hello("Jason's \"Phone\"", 30))
        assertEquals("Jason's \"Phone\"", o["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun pingRoundTrips() {
        val o = obj(Messages.ping(12345.9))
        assertEquals("ping", o["type"]!!.jsonPrimitive.content)
        assertEquals(12345.9, o["t"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun motionRoundTrips() {
        val text = Messages.motion(12345.678, floatArrayOf(0.12f, -0.4f, 0.03f), floatArrayOf(0.1f, 9.7f, 1.2f))
        val o = obj(text)
        assertEquals("motion", o["type"]!!.jsonPrimitive.content)
        assertEquals(12345.678, o["t"]!!.jsonPrimitive.double, 1e-4)
        val a = (o["a"] as JsonArray).map { it.jsonPrimitive.double }
        val g = o["g"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(0.12, -0.4, 0.03), a.map { Math.round(it * 1e4) / 1e4 })
        assertEquals(listOf(0.1, 9.7, 1.2), g.map { Math.round(it * 1e4) / 1e4 })
    }

    @Test
    fun motionUsesFixedFourDecimals() {
        val text = Messages.motion(1.0, floatArrayOf(0f, -0.00004f, 5f), floatArrayOf(0f, 0f, 9.81f))
        assertEquals(
            "{\"type\":\"motion\",\"t\":1.0000,\"a\":[0.0000,0.0000,5.0000],\"g\":[0.0000,0.0000,9.8100]}",
            text,
        )
    }

    @Test
    fun motionIsLocaleIndependent() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = Messages.motion(2.5, floatArrayOf(1.5f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f))
            assertTrue(text, text.contains("\"t\":2.5000"))
            obj(text)
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun motionHandlesNonFiniteAndNegativeFractions() {
        val text = Messages.motion(0.0, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -0.05f), floatArrayOf(0f, 0f, 0f))
        val a = obj(text)["a"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(0.0, 0.0, -0.05), a)
    }

    @Test
    fun parseWelcome() {
        val m = Messages.parse("""{"type":"welcome","v":1,"host":"Jason-MacBook","rateHz":50}""")
        assertEquals(Incoming.Welcome("Jason-MacBook", 50), m)
    }

    @Test
    fun parseToleratesUnknownFieldsAndWrongTypes() {
        val m = Messages.parse("""{"type":"welcome","extra":[1,2],"host":7,"rateHz":"fast","x":{"y":1}}""")
        assertEquals(Incoming.Welcome(null, null), m)
        assertEquals(Incoming.Config(30), Messages.parse("""{"type":"config","rateHz":30.0,"z":null}"""))
        assertEquals(Incoming.Pong(12345.9), Messages.parse("""{"type":"pong","t":12345.9}"""))
    }

    @Test
    fun parseIgnoresUnknownTypeAndGarbage() {
        assertNull(Messages.parse("""{"type":"future-thing","a":1}"""))
        assertNull(Messages.parse("""{"nope":1}"""))
        assertNull(Messages.parse("""{"type":5}"""))
        assertNull(Messages.parse("not json"))
        assertNull(Messages.parse("[1,2,3]"))
        assertNull(Messages.parse(""))
    }

    @Test
    fun adoptableRate() {
        assertTrue(Messages.isAdoptableRate(20))
        assertTrue(Messages.isAdoptableRate(60))
        assertEquals(false, Messages.isAdoptableRate(19))
        assertEquals(false, Messages.isAdoptableRate(61))
        assertEquals(false, Messages.isAdoptableRate(null))
    }
}
