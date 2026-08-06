package se.euther.euthervox.audio

import kotlin.math.max
import kotlin.math.sqrt

enum class SpeechDetection { Waiting, SpeechStarted, EndOfSpeech, NoSpeechTimeout }

/** Lightweight local endpoint detector for the app's fixed 20 ms mono s16le frames. */
class SpeechEndDetector(
    frameMs: Int = 20,
    private val minimumSpeechRms: Double = 500.0,
    speechStartMs: Int = 80,
    trailingSilenceMs: Int = 650,
    noSpeechTimeoutMs: Int = 10_000,
) {
    private val speechStartFrames = max(1, speechStartMs / frameMs)
    private val trailingSilenceFrames = max(1, trailingSilenceMs / frameMs)
    private val noSpeechTimeoutFrames = max(1, noSpeechTimeoutMs / frameMs)
    private var totalFrames = 0
    private var voicedFrames = 0
    private var silentFrames = 0
    private var noiseFloor = 120.0
    private var speechStarted = false
    private var terminal = false

    fun accept(frame: ByteArray): SpeechDetection {
        if (terminal) return SpeechDetection.Waiting
        totalFrames += 1
        val rms = pcmRms(frame)
        val threshold = max(minimumSpeechRms, noiseFloor * 2.8)
        val voiced = rms >= threshold

        if (!speechStarted) {
            if (voiced) {
                voicedFrames += 1
            } else {
                voicedFrames = 0
                noiseFloor = noiseFloor * 0.94 + rms * 0.06
            }
            if (voicedFrames >= speechStartFrames) {
                speechStarted = true
                silentFrames = 0
                return SpeechDetection.SpeechStarted
            }
            if (totalFrames >= noSpeechTimeoutFrames) {
                terminal = true
                return SpeechDetection.NoSpeechTimeout
            }
            return SpeechDetection.Waiting
        }

        if (voiced) {
            silentFrames = 0
        } else {
            silentFrames += 1
        }
        if (silentFrames >= trailingSilenceFrames) {
            terminal = true
            return SpeechDetection.EndOfSpeech
        }
        return SpeechDetection.Waiting
    }

    companion object {
        fun pcmRms(frame: ByteArray): Double {
            if (frame.size < 2) return 0.0
            var sum = 0.0
            var samples = 0
            var index = 0
            while (index + 1 < frame.size) {
                val low = frame[index].toInt() and 0xff
                val high = frame[index + 1].toInt()
                val sample = (high shl 8) or low
                sum += sample.toDouble() * sample.toDouble()
                samples += 1
                index += 2
            }
            return if (samples == 0) 0.0 else sqrt(sum / samples)
        }
    }
}
