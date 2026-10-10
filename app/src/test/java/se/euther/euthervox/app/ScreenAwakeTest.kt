package se.euther.euthervox.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeTest {
    @Test fun synthesisAndPlaybackPreventAutomaticScreenSleep() {
        assertTrue(keepScreenAwake(VoiceStatus.Processing))
        assertTrue(keepScreenAwake(VoiceStatus.Speaking))
    }

    @Test fun otherStatesRestoreNormalScreenTimeout() {
        VoiceStatus.entries.filter {
            it != VoiceStatus.Processing && it != VoiceStatus.Speaking
        }.forEach { assertFalse("Screen held awake in $it", keepScreenAwake(it)) }
    }
}
