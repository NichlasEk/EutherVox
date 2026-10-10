package se.euther.eutherbeam.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungDeviceParserTest {
    @Test
    fun `parses Samsung SSDP headers case insensitively`() {
        val headers = SamsungDeviceParser.parseHeaders(
            "HTTP/1.1 200 OK\r\n" +
                "LOCATION: http://192.168.32.15:7676/smp_7_\r\n" +
                "SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n\r\n",
        )

        assertEquals("http://192.168.32.15:7676/smp_7_", headers["location"])
        assertTrue(SamsungDeviceParser.isSamsungResponse(headers))
    }

    @Test
    fun `parses the live H series device shape`() {
        val device = SamsungDeviceParser.parseDeviceInfo(
            "192.168.32.15",
            """{
                "DUID":"0d1cef00-00dc-1000-9130-123456789abc",
                "Model":"14_X14_BT",
                "ModelName":"UE55H6400",
                "NetworkType":"wired",
                "DeviceName":"[TV]Samsung LED55",
                "DeviceID":"0d1cef00-00dc-1000-9130-123456789abc",
                "ServiceURI":"http://192.168.32.15:8001/ms/1.0/"
            }""",
        )

        assertEquals("UE55H6400", device.modelName)
        assertEquals("[TV]Samsung LED55", device.friendlyName)
        assertEquals("0d1cef00-00dc-1000-9130-123456789abc", device.deviceId)
        assertEquals("12:34:56:78:9A:BC", device.macAddress)
        assertTrue(device.encrypted)
    }
}
