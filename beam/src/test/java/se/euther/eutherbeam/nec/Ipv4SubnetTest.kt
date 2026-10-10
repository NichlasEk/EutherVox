package se.euther.eutherbeam.nec

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4SubnetTest {
    @Test
    fun `subnet honors the real prefix and enumerates usable hosts`() {
        val address = InetAddress.getByName("192.168.33.24") as Inet4Address
        val subnet = Ipv4Subnet.fromAddress(address, 23)
        assertEquals("192.168.32.0/23", subnet.toString())
        assertEquals(510, subnet.hostCount)
        assertEquals("192.168.32.1", subnet.hosts().first())
        assertEquals("192.168.33.254", subnet.hosts().last())
        assertEquals("192.168.33.255", subnet.broadcastAddress())
    }

    @Test
    fun `manual address rejects malformed and loopback input`() {
        assertEquals("192.168.32.25", Ipv4Subnet.normalizeAddress(" 192.168.32.25 "))
        assertNull(Ipv4Subnet.normalizeAddress("127.0.0.1"))
        assertNull(Ipv4Subnet.normalizeAddress("999.1.1.1"))
    }
}
