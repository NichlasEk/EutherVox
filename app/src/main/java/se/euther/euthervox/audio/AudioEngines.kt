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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class AudioStreamFormat(val codec: String, val sampleRate: Int, val channels: Int, val frameMs: Int? = null)

interface MicrophoneSource {
    fun start(onFrame: (ByteArray) -> Unit, onFirstFrame: () -> Unit)
    fun stop()
}

interface StreamingAudioSink {
    fun start(format: AudioStreamFormat, onPlaybackStarted: () -> Unit)
    fun enqueue(frame: ByteArray): Boolean
    fun finish(onPlaybackComplete: () -> Unit)
    fun stop()
}

class PcmMicrophoneSource(private val context: Context, private val scope: CoroutineScope) : MicrophoneSource {
    private val active = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun start(onFrame: (ByteArray) -> Unit, onFirstFrame: () -> Unit) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (!active.compareAndSet(false, true)) return
        val frameBytes = 640
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, frameBytes * 8),
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Mikrofonen kunde inte initieras" }
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

    override fun start(format: AudioStreamFormat, onPlaybackStarted: () -> Unit) {
        stop()
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
        val channel = Channel<ByteArray>(capacity = 32)
        queue = channel
        writerJob = scope.launch(Dispatchers.IO) {
            var buffered = 0
            var playing = false
            for (frame in channel) {
                audioTrack.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                buffered += frame.size
                if (!playing && buffered >= startBytes) {
                    audioTrack.play()
                    playing = true
                    onPlaybackStarted()
                }
            }
            if (!playing && buffered > 0) {
                audioTrack.play()
                onPlaybackStarted()
            }
            val totalFrames = buffered / 2
            while (isActive && audioTrack.playbackHeadPosition.toLong() < totalFrames) {
                kotlinx.coroutines.delay(20)
            }
            completion?.invoke()
        }
    }

    override fun enqueue(frame: ByteArray): Boolean = queue?.trySend(frame.copyOf())?.isSuccess == true

    override fun finish(onPlaybackComplete: () -> Unit) {
        completion = onPlaybackComplete
        queue?.close()
    }

    override fun stop() {
        queue?.close()
        queue = null
        completion = null
        writerJob?.cancel()
        writerJob = null
        track?.let { audioTrack ->
            audioTrack.runCatching { pause() }
            audioTrack.runCatching { flush() }
            audioTrack.runCatching { stop() }
            audioTrack.runCatching { release() }
        }
        track = null
    }
}
