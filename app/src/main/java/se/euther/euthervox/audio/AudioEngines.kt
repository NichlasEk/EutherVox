package se.euther.euthervox.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AudioStreamFormat(val codec: String, val sampleRate: Int, val channels: Int, val frameMs: Int? = null)

interface MicrophoneSource {
    fun start(onFrame: (ByteArray) -> Unit, onFirstFrame: () -> Unit, echoCancellation: Boolean = false)
    fun stop()
}

interface StreamingAudioSink {
    fun start(format: AudioStreamFormat, onPlaybackStarted: () -> Unit, onPlaybackError: (String) -> Unit)
    fun enqueue(frame: ByteArray): Boolean
    fun finish(onPlaybackComplete: () -> Unit)
    fun stop()
}

class PcmMicrophoneSource(private val context: Context, private val scope: CoroutineScope) : MicrophoneSource {
    private val active = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    val supportsEchoCancellation: Boolean get() = AcousticEchoCanceler.isAvailable()

    @SuppressLint("MissingPermission")
    override fun start(onFrame: (ByteArray) -> Unit, onFirstFrame: () -> Unit, echoCancellation: Boolean) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (!active.compareAndSet(false, true)) return
        val frameBytes = 640
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioSource = if (echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val audioRecord = AudioRecord(
            audioSource, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, frameBytes * 8),
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Mikrofonen kunde inte initieras" }
        if (echoCancellation && AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.also { it.enabled = true }
        }
        if (echoCancellation && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.also { it.enabled = true }
        }
        recorder = audioRecord
        audioRecord.startRecording()
        captureJob = scope.launch(Dispatchers.IO) {
            var first = true
            while (isActive && active.get()) {
                val frame = ByteArray(frameBytes)
                var offset = 0
                while (offset < frame.size && active.get()) {
                    val count = audioRecord.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                    if (count <= 0) break
                    offset += count
                }
                if (offset == frameBytes) {
                    if (first) { first = false; onFirstFrame() }
                    onFrame(frame)
                }
            }
        }
    }

    override fun stop() {
        if (!active.compareAndSet(true, false)) return
        recorder?.runCatching { stop() }
        captureJob?.cancel()
        echoCanceler?.release()
        noiseSuppressor?.release()
        echoCanceler = null
        noiseSuppressor = null
        recorder?.release()
        recorder = null
        captureJob = null
    }
}

class PcmAudioTrackSink(private val scope: CoroutineScope, private val startBufferMs: Int = 120) : StreamingAudioSink {
    private var track: AudioTrack? = null
    private var writerJob: Job? = null
    private var queue: Channel<ByteArray>? = null
    @Volatile private var completion: (() -> Unit)? = null
    private val generation = AtomicLong(0)

    override fun start(format: AudioStreamFormat, onPlaybackStarted: () -> Unit, onPlaybackError: (String) -> Unit) {
        stop()
        val playbackGeneration = generation.incrementAndGet()
        require(format.codec == "pcm_s16le" && format.channels == 1)
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val startBytes = format.sampleRate * 2 * startBufferMs / 1000
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(format.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, startBytes * 2))
            .build()
        track = audioTrack
        completion = null
        val channel = Channel<ByteArray>(capacity = 128)
        queue = channel
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                var buffered = 0
                var playing = false
                for (frame in channel) {
                    if (generation.get() != playbackGeneration) return@launch
                    var offset = 0
                    while (offset < frame.size && isActive && generation.get() == playbackGeneration) {
                        val written = audioTrack.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written > 0) { "AudioTrack.write misslyckades med kod $written" }
                        offset += written
                    }
                    if (generation.get() != playbackGeneration) return@launch
                    buffered += offset
                    if (!playing && buffered >= startBytes) {
                        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack blev ogiltigt före uppspelning" }
                        audioTrack.play()
                        playing = true
                        onPlaybackStarted()
                    }
                }
                if (generation.get() != playbackGeneration) return@launch
                if (!playing && buffered > 0) {
                    check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack blev ogiltigt före uppspelning" }
                    audioTrack.play()
                    playing = true
                    onPlaybackStarted()
                }
                val totalFrames = buffered / 2
                while (
                    isActive && generation.get() == playbackGeneration &&
                    audioTrack.playbackHeadPosition.toLong() < totalFrames
                ) {
                    kotlinx.coroutines.delay(20)
                }
                if (generation.get() == playbackGeneration) completion?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation.get() == playbackGeneration) {
                    onPlaybackError(error.message ?: "Okänt AudioTrack-fel")
                }
            }
        }
    }

    override fun enqueue(frame: ByteArray): Boolean = queue?.trySend(frame.copyOf())?.isSuccess == true

    override fun finish(onPlaybackComplete: () -> Unit) {
        completion = onPlaybackComplete
        queue?.close()
    }

    override fun stop() {
        generation.incrementAndGet()
        val oldQueue = queue
        queue = null
        oldQueue?.close()
        completion = null
        val oldWriter = writerJob
        writerJob = null
        oldWriter?.cancel()
        val oldTrack = track
        track = null
        oldTrack?.let { audioTrack ->
            audioTrack.runCatching { pause() }
            audioTrack.runCatching { flush() }
            audioTrack.runCatching { stop() }
            audioTrack.runCatching { release() }
        }
    }
}
