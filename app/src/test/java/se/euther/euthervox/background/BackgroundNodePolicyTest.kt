package se.euther.euthervox.background

import org.junit.Assert.assertEquals
import org.junit.Test
import se.euther.euthervox.app.VoiceStatus

class BackgroundNodePolicyTest {
    @Test
    fun readyNodeUsesStableConnectedNotification() {
        assertEquals(
            "EutherVox-noden är ansluten",
            backgroundNodeNotificationText(VoiceStatus.Idle, true, "Redo"),
        )
    }

    @Test
    fun reconnectAndNetworkFailureRemainVisible() {
        assertEquals(
            "Återansluter till EutherVox…",
            backgroundNodeNotificationText(VoiceStatus.Connecting, false, "Ansluter"),
        )
        assertEquals(
            "EutherVox väntar på nätverket",
            backgroundNodeNotificationText(VoiceStatus.Error, false, "Fel"),
        )
    }
}
