package se.euther.euthervox.lights

import org.junit.Assert.assertEquals
import org.junit.Test

class BleLightProtocolClassifierTest {
    @Test
    fun recognizesTrionesGattService() {
        assertEquals(
            BleLightProtocol.Triones,
            BleLightProtocolClassifier.classify("Namnlös", listOf("0000ffd5-0000-1000-8000-00805f9b34fb")),
        )
    }

    @Test
    fun recognizesSurplifeGattService() {
        assertEquals(
            BleLightProtocol.Surplife,
            BleLightProtocolClassifier.classify("Namnlös", listOf("0000f000-0000-1000-8000-00805f9b34fb")),
        )
    }

    @Test
    fun marksKnownAdvertisingNameAsCandidateUntilGattInspection() {
        assertEquals(
            BleLightProtocol.Candidate,
            BleLightProtocolClassifier.classify("ELK-BLEDOM", emptyList()),
        )
    }
}
