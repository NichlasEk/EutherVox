package se.euther.euthervox.lights

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class BassEnvelopeAnalyzerTest {
    @Test
    fun respondsMuchMoreToBassThanTreble() {
        val analyzer = BassEnvelopeAnalyzer(16_000)
        val bass = tone(80.0)
        val treble = tone(1_000.0)

        assertTrue(analyzer.rawBandLevel(bass) > analyzer.rawBandLevel(treble) * 8)
    }

    @Test
    fun sensitivityRaisesTheNormalizedEnvelope() {
        val quietBass = tone(90.0, amplitude = 1_200)
        val low = BassEnvelopeAnalyzer().process(quietBass, 10)
        val high = BassEnvelopeAnalyzer().process(quietBass, 90)

        assertTrue(high > low)
    }

    private fun tone(frequency: Double, amplitude: Int = 12_000) = ShortArray(800) { index ->
        (sin(2.0 * PI * frequency * index / 16_000) * amplitude).toInt().toShort()
    }
}
