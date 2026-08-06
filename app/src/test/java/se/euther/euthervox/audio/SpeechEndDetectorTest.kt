package se.euther.euthervox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndDetectorTest {
    @Test fun detectsSpeechThenTrailingSilence() {
        val detector = SpeechEndDetector(
            speechStartMs = 40,
            trailingSilenceMs = 60,
            noSpeechTimeoutMs = 1_000,
        )

        assertEquals(SpeechDetection.Waiting, detector.accept(frame(2_000)))
        assertEquals(SpeechDetection.SpeechStarted, detector.accept(frame(2_000)))
        assertEquals(SpeechDetection.Waiting, detector.accept(frame(0)))
        assertEquals(SpeechDetection.Waiting, detector.accept(frame(0)))
        assertEquals(SpeechDetection.EndOfSpeech, detector.accept(frame(0)))
    }

    @Test fun timesOutWhenNobodySpeaks() {
        val detector = SpeechEndDetector(noSpeechTimeoutMs = 60)

        assertEquals(SpeechDetection.Waiting, detector.accept(frame(0)))
        assertEquals(SpeechDetection.Waiting, detector.accept(frame(0)))
        assertEquals(SpeechDetection.NoSpeechTimeout, detector.accept(frame(0)))
    }

    @Test fun calculatesSignedLittleEndianRms() {
        assertTrue(SpeechEndDetector.pcmRms(frame(-1_500)) in 1_499.0..1_501.0)
    }

    private fun frame(sample: Int): ByteArray = ByteArray(640).also { bytes ->
        var index = 0
        while (index < bytes.size) {
            bytes[index] = (sample and 0xff).toByte()
            bytes[index + 1] = (sample shr 8).toByte()
            index += 2
        }
    }
}
