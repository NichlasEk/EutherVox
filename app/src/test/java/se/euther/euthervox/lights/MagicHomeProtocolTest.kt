package se.euther.euthervox.lights

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicHomeProtocolTest {
    @Test
    fun parsesDiscoveryReply() {
        val device = MagicHomeProtocol.parseDiscovery("192.168.32.51,600194B94629,AK001-ZJ200\r\n")!!
        assertEquals("192.168.32.51", device.ip)
        assertEquals("600194B94629", device.mac)
        assertEquals("AK001-ZJ200", device.model)
        assertNull(MagicHomeProtocol.parseDiscovery("not a module"))
    }

    @Test
    fun buildsKnownPowerPacketsWithChecksum() {
        assertArrayEquals(byteArrayOf(0x71, 0x23, 0x0f, 0xa3.toByte()), MagicHomeProtocol.power(true))
        assertArrayEquals(byteArrayOf(0x71, 0x24, 0x0f, 0xa4.toByte()), MagicHomeProtocol.power(false))
    }

    @Test
    fun parsesObservedAk001Status() {
        val bytes = "81 04 24 61 01 0f 9f 00 60 00 05 00 f0 0e"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val status = MagicHomeProtocol.parseStatus("192.168.32.6", "mac", "AK001-ZJ200", bytes)!!
        assertFalse(status.powerOn!!)
        assertEquals("#9F0060", status.colorHex)
    }

    @Test
    fun buildsAllowlistedEffectWithFluxLedSpeedMapping() {
        assertArrayEquals(
            byteArrayOf(0x61, 0x25, 0x13, 0x0f, 0xa8.toByte()),
            MagicHomeProtocol.effect("Regnbåge mjuk", 40),
        )
    }

    @Test
    fun validatesCredentialsWithoutReturningSecrets() {
        assertNull(MagicHomeProtocol.validateWifiCredentials("Hemma", "hemligt123"))
        assertTrue(MagicHomeProtocol.validateWifiCredentials("Hemma", "kort")!!.contains("8"))
        assertTrue(MagicHomeProtocol.validateWifiCredentials("fel,nät", "hemligt123")!!.contains("tecken"))
    }
}
