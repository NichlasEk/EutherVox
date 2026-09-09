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

class BatteryWakeLockPolicyTest {
    @Test fun batteryModeSleepsWhileIdleOrReconnecting() {
        for (status in listOf(VoiceStatus.Idle, VoiceStatus.Connecting, VoiceStatus.Error)) {
            assertEquals(false, backgroundNeedsWakeLock(true, status, false))
        }
    }
    @Test fun workAndSpeechKeepTemporaryProtection() {
        for (status in listOf(VoiceStatus.Listening, VoiceStatus.Processing, VoiceStatus.Speaking)) {
            assertEquals(true, backgroundNeedsWakeLock(true, status, false))
        }
        assertEquals(true, backgroundNeedsWakeLock(true, VoiceStatus.Idle, true))
    }
    @Test fun reliableModeKeepsReceptionAwake() {
        assertEquals(true, backgroundNeedsWakeLock(false, VoiceStatus.Idle, false))
    }
}
