package dev.jasonpearson.motiondots.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPortTest {
    @Test
    fun parsesCommonForms() {
        assertEquals(HostPort("192.168.1.20", 5000), HostPort.parse(" 192.168.1.20:5000 "))
        assertEquals(HostPort("mac.local", 80), HostPort.parse("ws://mac.local:80/"))
        assertEquals(HostPort("fe80::1", 9), HostPort.parse("[fe80::1]:9"))
    }

    @Test
    fun rejectsInvalid() {
        for (s in listOf("", "host", "host:", ":80", "host:0", "host:70000", "a:b:80", "[::1]", "ho st:80")) {
            assertNull(s, HostPort.parse(s))
        }
    }

    @Test
    fun addressPickerPrefersIpv4() {
        val v6 = InetAddress.getByAddress(ByteArray(15) + byteArrayOf(5)) // ::5
        val v4 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))
        assertEquals("10.0.0.7", AddressPicker.pick(listOf(v6, v4)))
        assertEquals("0:0:0:0:0:0:0:5", AddressPicker.pick(listOf(v6)))
        assertNull(AddressPicker.pick(emptyList()))
    }
}
