package se.euther.euthervox.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLifecycleTest {
    @Test fun houseMessageSurvivesScreenLock() {
        assertTrue(keepNotificationOnPause("washer-1", "washer-1"))
    }
    @Test fun ordinaryVoiceTurnStillStopsWhenLeavingApp() {
        assertFalse(keepNotificationOnPause("voice-1", null))
        assertFalse(keepNotificationOnPause("voice-2", "washer-1"))
        assertFalse(keepNotificationOnPause(null, null))
    }
}
