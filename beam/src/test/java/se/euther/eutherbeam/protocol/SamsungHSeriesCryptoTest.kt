package se.euther.eutherbeam.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungHSeriesCryptoTest {
    private val crypto = SamsungHSeriesCrypto()

    @Test
    fun `server hello has the Samsung SPC envelope and deterministic payload`() {
        val first = crypto.generateServerHello("654321", "1234")
        val second = crypto.generateServerHello("654321", "1234")

        assertEquals(first.message, second.message)
        assertEquals(
            "010200000000000000008A00000006363534333231C0DEE54ED567F0310A5185DBCDCB0C3E8753B342409786699D343060AB8200AFCE6BB581E3B60779FE88F1B1BD7C39A0B14898B34F8DFA058E9D377E7050E97D51E18937F04A936B76E00CACBD95B4F5353D8A716B7AB76EC85FC0D3E75CD242E909698ED6F4BA8A3EA5FF33A232400D07CB659F496AA631B5DF86253DECE96A0000000000",
            first.message,
        )
        assertTrue(first.message.startsWith("01020000000000"))
        assertEquals(308, first.message.length)
        assertEquals(20, first.dataHash.size)
        assertEquals(16, first.pinKey.size)
    }

    @Test
    fun `different pins produce different hello payloads`() {
        val first = crypto.generateServerHello("654321", "1234")
        val second = crypto.generateServerHello("654321", "9876")

        assertNotEquals(first.message, second.message)
    }

    @Test
    fun `command encryption round trips with Samsung padding`() {
        val key = ByteArray(16) { it.toByte() }
        val command = "{\"plugin\":\"NNavi\",\"api\":\"GetDUID\"}"

        assertEquals(command, crypto.decryptCommand(key, crypto.encryptCommand(key, command)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pin must be exactly four digits`() {
        crypto.generateServerHello("654321", "12ab")
    }
}
