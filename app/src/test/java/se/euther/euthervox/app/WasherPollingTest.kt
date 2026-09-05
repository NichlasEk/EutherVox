package se.euther.euthervox.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WasherPollingTest {
    @Test fun activeOrScheduledWashUpdatesEveryFiveSeconds() {
        assertEquals(5_000L, washerPollIntervalMs("running", null))
        assertEquals(5_000L, washerPollIntervalMs("paused", null))
        assertEquals(5_000L, washerPollIntervalMs("idle", "scheduled"))
    }
    @Test fun idleAndCompletedKeepPollingForNextWash() {
        assertEquals(15_000L, washerPollIntervalMs("idle", null))
        assertEquals(15_000L, washerPollIntervalMs("finished", "started"))
    }
}
