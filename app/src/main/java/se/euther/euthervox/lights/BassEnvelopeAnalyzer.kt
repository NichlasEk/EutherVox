package se.euther.euthervox.lights

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Extracts a stable 45–160 Hz envelope without retaining or exposing audio. */
class BassEnvelopeAnalyzer(private val sampleRate: Int = 16_000) {
    private val frequencies = doubleArrayOf(50.0, 70.0, 90.0, 115.0, 145.0)
    private var noiseFloor = 0.001
    private var recentPeak = 0.02
    private var smoothed = 0.0

    fun process(samples: ShortArray, sensitivity: Int): Float {
        val raw = rawBandLevel(samples)
        noiseFloor = if (raw < noiseFloor) {
            noiseFloor * 0.90 + raw * 0.10
        } else {
            noiseFloor * 0.995 + raw * 0.005
        }
        recentPeak = max(raw, recentPeak * 0.96)
        val dynamicRange = max(0.002, recentPeak - noiseFloor)
        val gain = 0.5 + sensitivity.coerceIn(1, 100) / 50.0
        val target = (((raw - noiseFloor) / dynamicRange) * gain).coerceIn(0.0, 1.0)
        val smoothing = if (target > smoothed) 0.65 else 0.18
        smoothed += (target - smoothed) * smoothing
        return smoothed.toFloat()
    }

    internal fun rawBandLevel(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var totalPower = 0.0
        frequencies.forEach { frequency ->
            val coefficient = 2.0 * cos(2.0 * PI * frequency / sampleRate)
            var previous = 0.0
            var previousPrevious = 0.0
            samples.forEach { sample ->
                val current = sample / 32768.0 + coefficient * previous - previousPrevious
                previousPrevious = previous
                previous = current
            }
            totalPower += max(0.0, previous * previous + previousPrevious * previousPrevious - coefficient * previous * previousPrevious)
        }
        return sqrt(totalPower) / samples.size
    }
}
