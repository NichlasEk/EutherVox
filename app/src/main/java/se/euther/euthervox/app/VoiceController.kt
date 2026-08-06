package se.euther.euthervox.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.euther.euthervox.audio.AudioStreamFormat
import se.euther.euthervox.audio.PcmAudioTrackSink
import se.euther.euthervox.audio.PcmMicrophoneSource
import se.euther.euthervox.audio.StreamingAudioSink
import se.euther.euthervox.actions.AndroidDeviceActionExecutor
import se.euther.euthervox.actions.DeviceActionExecutor
import se.euther.euthervox.network.VoiceTransport
import se.euther.euthervox.network.WebSocketClient
import se.euther.euthervox.network.AuthTokenStore
import se.euther.euthervox.network.EutherAuthClient
import se.euther.euthervox.protocol.ServerEvent
import se.euther.euthervox.protocol.actionConfirm
import se.euther.euthervox.protocol.actionResult
import se.euther.euthervox.protocol.audioEnd
import se.euther.euthervox.protocol.audioStart
import se.euther.euthervox.protocol.parseServerEvent
import se.euther.euthervox.protocol.responseCancel
import se.euther.euthervox.protocol.sessionStart
import java.util.UUID

enum class VoiceStatus { Idle, Connecting, Listening, Processing, Speaking, Error }

data class Latencies(
    val captureStartMs: Long? = null,
    val uploadMs: Long? = null,
    val sttFinalMs: Long? = null,
    val firstResponseTextMs: Long? = null,
    val firstTtsAudioMs: Long? = null,
    val audioPlaybackStartMs: Long? = null,
    val totalResponseLatencyMs: Long? = null,
)

data class VoiceUiState(
    val serverAddress: String = "",
    val connectionLabel: String = "Ej ansluten",
    val status: VoiceStatus = VoiceStatus.Idle,
    val microphoneActive: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val responseText: String = "",
    val latencies: Latencies = Latencies(),
    val errorMessage: String? = null,
    val droppedCaptureFrames: Int = 0,
    val canTalk: Boolean = false,
    val pendingAction: ServerEvent.ActionRequest? = null,
    val actionMessage: String? = null,
)

private data class Timeline(
    var buttonDown: Long = 0,
    var firstMic: Long = 0,
    var lastMic: Long = 0,
    var audioEnd: Long = 0,
    var sttFinal: Long = 0,
    var sttPartial: Long = 0,
    var firstText: Long = 0,
    var firstTts: Long = 0,
    var playbackStart: Long = 0,
    var lastTts: Long = 0,
)

class VoiceController(context: Context, private val scope: CoroutineScope) : VoiceTransport.Listener {
    private val microphone = PcmMicrophoneSource(context.applicationContext, scope)
    private val speaker: StreamingAudioSink = PcmAudioTrackSink(scope)
    private val authClient = EutherAuthClient()
    private val tokenStore = AuthTokenStore(context.applicationContext)
    private val actionExecutor: DeviceActionExecutor = AndroidDeviceActionExecutor(context.applicationContext)
    private val mutableState = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = mutableState.asStateFlow()
    private var transport: VoiceTransport? = null
    private var connectionJob: Job? = null
    private var timeoutJob: Job? = null
    private var address = ""
    private var nodeName = "android-phone"
    private var shouldReconnect = false
    private var ready = false
    private var utteranceId: String? = null
    private var timeline = Timeline()

    fun connect(serverAddress: String, requestedNodeName: String, username: String = "", password: String = "") {
        val normalized = serverAddress.trim().trimEnd('/')
        if (normalized.isBlank()) {
            fail("Ange serveradress, till exempel wss://server/euthervox/ws", recoverable = false)
            return
        }
        disconnect()
        address = normalized
        nodeName = requestedNodeName.ifBlank { "android-phone" }
        shouldReconnect = true
        mutableState.value = mutableState.value.copy(serverAddress = normalized, status = VoiceStatus.Connecting, connectionLabel = "Ansluter…", errorMessage = null)
        connectionJob = scope.launch {
            var loginPassword = password
            var bearerToken = if (normalized.startsWith("wss://")) tokenStore.load() else null
            if (normalized.startsWith("wss://") && loginPassword.isNotBlank()) {
                mutableState.value = mutableState.value.copy(connectionLabel = "Loggar in…")
                bearerToken = runCatching { authClient.login(normalized, username, loginPassword) }
                    .onSuccess(tokenStore::save)
                    .getOrElse {
                        loginPassword = ""
                        shouldReconnect = false
                        fail(it.message ?: "Inloggningen misslyckades", recoverable = false)
                        return@launch
                    }
                loginPassword = ""
            }
            if (normalized.startsWith("wss://") && bearerToken.isNullOrBlank()) {
                shouldReconnect = false
                fail("Logga in via Inställningar första gången", recoverable = false)
                return@launch
            }
            var retryMs = 500L
            while (shouldReconnect) {
                val candidate = WebSocketClient(scope, bearerToken)
                transport = candidate
                candidate.connect(address, this@VoiceController)
                if (!shouldReconnect) break
                ready = false
                mutableState.value = mutableState.value.copy(connectionLabel = "Återansluter…", status = VoiceStatus.Connecting, canTalk = false)
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(8_000)
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        ready = false
        stopResources()
        connectionJob?.cancel()
        connectionJob = null
        val old = transport
        transport = null
        scope.launch { old?.close() }
        mutableState.value = mutableState.value.copy(connectionLabel = "Ej ansluten", status = VoiceStatus.Idle, canTalk = false, microphoneActive = false)
    }

    fun forgetCredentials() {
        disconnect()
        tokenStore.clear()
        mutableState.value = mutableState.value.copy(errorMessage = "Sparad inloggning borttagen")
    }

    fun startTalking() {
        if (!ready || utteranceId != null) return
        val id = UUID.randomUUID().toString()
        utteranceId = id
        timeline = Timeline(buttonDown = now())
        logTime("button_press", timeline.buttonDown)
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Listening, microphoneActive = true, partialTranscript = "",
            finalTranscript = "", responseText = "", latencies = Latencies(), errorMessage = null, droppedCaptureFrames = 0,
        )
        scope.launch {
            if (transport?.sendText(audioStart(id)) != true) {
                fail("Kunde inte starta ljudströmmen")
                return@launch
            }
            runCatching {
                microphone.start(
                    onFrame = { frame ->
                        val stamp = now()
                        if (timeline.firstMic == 0L) {
                            timeline.firstMic = stamp
                            logTime("first_microphone_frame", stamp)
                        }
                        timeline.lastMic = stamp
                        if (transport?.trySendAudio(frame) != true) {
                            mutableState.value = mutableState.value.copy(droppedCaptureFrames = mutableState.value.droppedCaptureFrames + 1)
                        }
                    },
                    onFirstFrame = { updateLatencies() },
                )
            }.onFailure { fail(it.message ?: "Mikrofonfel") }
        }
    }

    fun stopTalking(cancelledGesture: Boolean = false) {
        val id = utteranceId ?: return
        microphone.stop()
        timeline.audioEnd = now()
        logTime("last_microphone_frame", timeline.lastMic)
        logTime("audio_end", timeline.audioEnd)
        updateLatencies()
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Processing, microphoneActive = false)
        scope.launch {
            transport?.sendText(audioEnd(id))
            if (cancelledGesture) transport?.sendText(responseCancel(id))
        }
        armTimeout(id, 15_000, "Servern svarade inte inom 15 sekunder")
    }

    fun cancelResponse() {
        val id = utteranceId ?: return
        microphone.stop()
        speaker.stop()
        scope.launch { transport?.sendText(responseCancel(id)) }
        finishUtterance()
    }

    fun onPause() {
        if (utteranceId != null) cancelResponse() else stopResources()
    }

    override suspend fun onOpen() {
        mutableState.value = mutableState.value.copy(connectionLabel = "Handshake…", status = VoiceStatus.Connecting)
        transport?.sendText(sessionStart(nodeName))
    }

    override suspend fun onText(text: String) {
        runCatching { parseServerEvent(text) }
            .onSuccess(::handleEvent)
            .onFailure { fail("Ogiltigt servermeddelande: ${it.message}") }
    }

    override suspend fun onBinary(data: ByteArray) {
        val id = utteranceId ?: return
        if (timeline.firstTts == 0L) {
            timeline.firstTts = now()
            logTime("first_tts_frame", timeline.firstTts)
            updateLatencies()
        }
        timeline.lastTts = now()
        if (!speaker.enqueue(data)) fail("Uppspelningskön blev full för $id")
    }

    override suspend fun onClosed(cause: Throwable?) {
        ready = false
        stopResources()
        if (cause?.message?.contains("(401)") == true) {
            tokenStore.clear()
            shouldReconnect = false
            mutableState.value = mutableState.value.copy(
                connectionLabel = "Inloggning krävs",
                status = VoiceStatus.Error,
                errorMessage = "Sessionen avvisades. Logga in igen i Inställningar.",
                canTalk = false,
                microphoneActive = false,
            )
            return
        }
        if (shouldReconnect) {
            mutableState.value = mutableState.value.copy(
                connectionLabel = "Frånkopplad: ${cause?.message ?: "servern stängde"}",
                status = VoiceStatus.Connecting, canTalk = false, microphoneActive = false,
            )
        }
    }

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> {
                ready = true
                mutableState.value = mutableState.value.copy(connectionLabel = "Ansluten", status = VoiceStatus.Idle, canTalk = true, errorMessage = null)
            }
            is ServerEvent.SttPartial -> {
                if (timeline.sttPartial == 0L) {
                    timeline.sttPartial = now()
                    logTime("first_stt_partial", timeline.sttPartial)
                }
                mutableState.value = mutableState.value.copy(partialTranscript = event.text)
            }
            is ServerEvent.SttFinal -> {
                timeline.sttFinal = now()
                logTime("stt_final", timeline.sttFinal)
                mutableState.value = mutableState.value.copy(finalTranscript = event.text, partialTranscript = "")
                updateLatencies()
            }
            is ServerEvent.TextDelta -> {
                if (timeline.firstText == 0L) {
                    timeline.firstText = now()
                    logTime("first_response_text", timeline.firstText)
                }
                mutableState.value = mutableState.value.copy(responseText = mutableState.value.responseText + event.text)
                updateLatencies()
            }
            is ServerEvent.TextFinal -> mutableState.value = mutableState.value.copy(responseText = event.text)
            is ServerEvent.TtsStart -> {
                timeoutJob?.cancel()
                timeoutJob = null
                mutableState.value = mutableState.value.copy(status = VoiceStatus.Speaking)
                speaker.start(AudioStreamFormat("pcm_s16le", event.sampleRate, event.channels)) {
                    timeline.playbackStart = now()
                    logTime("audio_playback_start", timeline.playbackStart)
                    updateLatencies()
                }
            }
            is ServerEvent.TtsEnd -> {
                logTime("last_tts_frame", timeline.lastTts)
                speaker.finish { finishUtterance() }
            }
            is ServerEvent.Cancelled -> finishUtterance()
            is ServerEvent.ActionRequest -> handleAction(event)
            is ServerEvent.ActionStatus -> {
                utteranceId?.let { armTimeout(it, 45_000, "Åtgärden svarade inte inom 45 sekunder") }
                mutableState.value = mutableState.value.copy(
                    status = VoiceStatus.Processing,
                    actionMessage = event.message,
                    canTalk = false,
                )
            }
            is ServerEvent.ActionCompleted -> {
                finishUtterance()
                mutableState.value = mutableState.value.copy(
                    status = if (event.status == "completed") VoiceStatus.Idle else VoiceStatus.Error,
                    actionMessage = event.message,
                    errorMessage = if (event.status == "completed") null else event.message,
                    pendingAction = null,
                    canTalk = ready,
                )
            }
            is ServerEvent.Error -> fail("${event.code}: ${event.message}", event.recoverable)
            is ServerEvent.Unknown -> Unit
        }
    }

    private fun handleAction(event: ServerEvent.ActionRequest) {
        val activeUtterance = utteranceId
        if (event.requiresConfirmation) {
            if (event.name != "playlist.create" || event.provider != "euthervox" || event.targetNode != nodeName) {
                scope.launch {
                    transport?.sendText(actionResult(event.actionId, event.utteranceId, "rejected", "Okänd bekräftelseåtgärd"))
                }
                fail("Servern föreslog en otillåten åtgärd")
                return
            }
            finishUtterance()
            mutableState.value = mutableState.value.copy(
                status = VoiceStatus.Processing,
                pendingAction = event,
                actionMessage = if (event.outputRoom.isNotBlank()) {
                    "Spara en privat lista med: ${event.query} och spela den i ${event.outputRoom}?"
                } else {
                    "Spara en privat lista med: ${event.query}? Den speglas till YouTube Music om ditt konto är kopplat."
                },
                canTalk = false,
            )
            return
        }
        val wrongUtterance = event.utteranceId.isNotBlank() && event.utteranceId != activeUtterance
        if (event.targetNode != nodeName || wrongUtterance) {
            scope.launch {
                transport?.sendText(actionResult(event.actionId, event.utteranceId, "rejected", "Fel mål eller yttrande"))
            }
            fail("Servern skickade en åtgärd till fel nod")
            return
        }

        finishUtterance()
        val result = actionExecutor.execute(event)
        scope.launch {
            transport?.sendText(actionResult(event.actionId, event.utteranceId, result.status, result.message))
        }
        if (result.status != "completed") {
            mutableState.value = mutableState.value.copy(
                status = VoiceStatus.Error,
                errorMessage = result.message,
                canTalk = ready,
            )
        }
    }

    fun confirmPendingAction() {
        val action = mutableState.value.pendingAction ?: return
        mutableState.value = mutableState.value.copy(
            pendingAction = null,
            actionMessage = "Sparar listan…",
            status = VoiceStatus.Processing,
            canTalk = false,
        )
        scope.launch { transport?.sendText(actionConfirm(action.actionId)) }
    }

    fun rejectPendingAction() {
        val action = mutableState.value.pendingAction ?: return
        mutableState.value = mutableState.value.copy(
            pendingAction = null,
            actionMessage = "Spellistan avbröts.",
            status = VoiceStatus.Idle,
            canTalk = ready,
        )
        scope.launch {
            transport?.sendText(actionResult(action.actionId, action.utteranceId, "rejected", "Användaren avbröt"))
        }
    }

    private fun finishUtterance() {
        timeoutJob?.cancel()
        timeoutJob = null
        microphone.stop()
        utteranceId = null
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Idle, microphoneActive = false, canTalk = ready)
    }

    private fun armTimeout(id: String, timeoutMs: Long, message: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (utteranceId == id) {
                transport?.sendText(responseCancel(id))
                fail(message)
            }
        }
    }

    private fun stopResources() {
        microphone.stop()
        speaker.stop()
        timeoutJob?.cancel()
        utteranceId = null
    }

    private fun fail(message: String, recoverable: Boolean = true) {
        stopResources()
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Error, errorMessage = message, microphoneActive = false, canTalk = false)
        if (recoverable) scope.launch {
            delay(2_000)
            if (ready) mutableState.value = mutableState.value.copy(status = VoiceStatus.Idle, errorMessage = null, canTalk = true)
        }
    }

    private fun updateLatencies() {
        fun delta(end: Long, start: Long): Long? = if (end > 0 && start > 0) (end - start).coerceAtLeast(0) else null
        mutableState.value = mutableState.value.copy(latencies = Latencies(
            captureStartMs = delta(timeline.firstMic, timeline.buttonDown),
            uploadMs = delta(timeline.audioEnd, timeline.lastMic),
            sttFinalMs = delta(timeline.sttFinal, timeline.audioEnd),
            firstResponseTextMs = delta(timeline.firstText, timeline.audioEnd),
            firstTtsAudioMs = delta(timeline.firstTts, timeline.audioEnd),
            audioPlaybackStartMs = delta(timeline.playbackStart, timeline.firstTts),
            totalResponseLatencyMs = delta(timeline.playbackStart, timeline.audioEnd),
        ))
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun logTime(event: String, timestampMs: Long) {
        Log.i("EutherVoxMetrics", "event=$event elapsed_ms=$timestampMs utterance=${utteranceId ?: "none"}")
    }
}
