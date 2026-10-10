package se.euther.eutherbeam.nec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class NecLiveDiscoveryTest {
    @Test
    fun `real display answers the read only NEC identity query`() {
        val host = System.getenv("EUTHERBEAM_NEC_IP")
        assumeNotNull(host)
        assertTrue(runBlocking { NecRemoteClient().isVerifiedDisplay(checkNotNull(host)) })
    }
}
