package se.euther.eutherbeam.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun `normalizes common mac formats`() {
        assertEquals("12:34:56:78:9A:BC", WakeOnLan.normalizeMac("12-34-56-78-9a-bc"))
        assertEquals("12:34:56:78:9A:BC", WakeOnLan.normalizeMac("1234.5678.9abc"))
        assertNull(WakeOnLan.normalizeMac("00:00:00:00:00:00"))
        assertNull(WakeOnLan.normalizeMac("not-a-mac"))
    }

    @Test
    fun `extracts mac suffix from Samsung device identifier`() {
        assertEquals(
            "12:34:56:78:9A:BC",
            WakeOnLan.fromSamsungIdentifier("0d1cef00-00dc-1000-9130-123456789abc"),
        )
        assertNull(WakeOnLan.fromSamsungIdentifier("0d1cef00"))
    }

    @Test
    fun `builds standard 102 byte magic packet`() {
        val mac = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())
        val packet = WakeOnLan.magicPacket("12:34:56:78:9a:bc")
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        repeat(16) { index ->
            assertArrayEquals(mac, packet.copyOfRange(6 + index * 6, 12 + index * 6))
        }
    }

    @Test
    fun `parses proc arp and ip neighbor lines`() {
        val address = "192.168.32.15"
        assertEquals(
            "12:34:56:78:9A:BC",
            SamsungMacResolver.parseNeighborLine("$address 0x1 0x2 12:34:56:78:9a:bc * wlan0", address),
        )
        assertEquals(
            "12:34:56:78:9A:BC",
            SamsungMacResolver.parseNeighborLine("$address dev wlan0 lladdr 12:34:56:78:9a:bc STALE", address),
        )
    }
}
