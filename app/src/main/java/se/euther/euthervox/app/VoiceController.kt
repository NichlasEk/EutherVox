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
import se.euther.euthervox.audio.SpeechDetection
import se.euther.euthervox.audio.SpeechEndDetector
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
import se.euther.euthervox.protocol.pumpStatus
import se.euther.euthervox.protocol.responseCancel
import se.euther.euthervox.protocol.sessionStart
import se.euther.euthervox.protocol.lightConfigUpsert
import se.euther.euthervox.protocol.tvCommand
import se.euther.euthervox.protocol.tvConfigUpsert
import se.euther.euthervox.protocol.tvDiscover
import se.euther.euthervox.lights.MagicHomeDevice
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
    val conversationActive: Boolean = false,
    val interruptionListening: Boolean = false,
    val configuredLights: List<ServerEvent.ConfiguredLight> = emptyList(),
    val lightConfigMessage: String? = null,
    val configuredTvs: List<ServerEvent.ConfiguredTv> = emptyList(),
    val discoveredTvs: List<ServerEvent.DiscoveredTv> = emptyList(),
    val tvMessage: String? = null,
    val tvBusy: Boolean = false,
    val configuredPumps: List<ServerEvent.ConfiguredPump> = emptyList(),
    val pumpState: ServerEvent.PumpState? = null,
    val pumpMessage: String? = null,
    val pumpBusy: Boolean = false,
    val llmModel: String = "",
    val availableLlmModels: List<String> = emptyList(),
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

private data class PendingLightConfig(
    val device: MagicHomeDevice,
    val name: String,
    val room: String,
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
    private var nextConversationTurnJob: Job? = null
    private var bargeInJob: Job? = null
    private var address = ""
    private var nodeName = "android-phone"
    private var characterId = "skinnskattaren"
    private var voiceId = "piper-nst"
    private var llmModel = ""
    private var shouldReconnect = false
    private var ready = false
    private var utteranceId: String? = null
    private var endpointDetector: SpeechEndDetector? = null
    @Volatile private var automaticEndpointHandled = false
    private var cancelledUtteranceId: String? = null
    @Volatile private var serverActionInProgress = false
    private var pendingLightConfig: PendingLightConfig? = null
    private var timeline = Timeline()

    fun connect(
        serverAddress: String,
        requestedNodeName: String,
        username: String = "",
        password: String = "",
        requestedVoiceId: String = "piper-nst",
        requestedCharacterId: String = "skinnskattaren",
        requestedLlmModel: String = "",
    ) {
        val normalized = serverAddress.trim().trimEnd('/')
        if (normalized.isBlank()) {
            fail("Ange serveradress, till exempel wss://server/euthervox/ws", recoverable = false)
            return
        }
        disconnect()
        address = normalized
        nodeName = requestedNodeName.ifBlank { "android-phone" }
        characterId = requestedCharacterId.ifBlank { "skinnskattaren" }
        voiceId = requestedVoiceId.ifBlank { "piper-nst" }
        llmModel = requestedLlmModel.trim()
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
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = null
        bargeInJob?.cancel()
        bargeInJob = null
        stopResources()
        connectionJob?.cancel()
        connectionJob = null
        val old = transport
        transport = null
        scope.launch { old?.close() }
        mutableState.value = mutableState.value.copy(
            connectionLabel = "Ej ansluten", status = VoiceStatus.Idle, canTalk = false,
            microphoneActive = false, conversationActive = false,
            interruptionListening = false,
        )
    }

    fun forgetCredentials() {
        disconnect()
        tokenStore.clear()
        mutableState.value = mutableState.value.copy(errorMessage = "Sparad inloggning borttagen")
    }

    fun startTalking() {
        beginUtterance(automaticEndpoint = false)
    }

    fun startConversation() {
        if (!ready || utteranceId != null || mutableState.value.conversationActive) return
        mutableState.value = mutableState.value.copy(
            conversationActive = true,
            actionMessage = "Samtalsläge aktivt – jag lyssnar tills du gör en kort paus.",
            errorMessage = null,
        )
        beginUtterance(automaticEndpoint = true)
    }

    fun stopConversation() {
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = null
        bargeInJob?.cancel()
        bargeInJob = null
        val activeId = utteranceId
        mutableState.value = mutableState.value.copy(
            conversationActive = false,
            interruptionListening = false,
            actionMessage = "Samtalet avslutades.",
        )
        if (activeId != null) {
            microphone.stop()
            speaker.stop()
            endpointDetector = null
            scope.launch { transport?.sendText(responseCancel(activeId)) }
        }
        finishUtterance(scheduleConversation = false, expectedUtteranceId = activeId)
    }

    fun interruptAndListen() {
        if (!mutableState.value.conversationActive) return
        val activeId = utteranceId ?: run {
            scheduleNextConversationTurn(0)
            return
        }
        cancelledUtteranceId = activeId
        bargeInJob?.cancel()
        bargeInJob = null
        endpointDetector = null
        microphone.stop()
        speaker.stop()
        mutableState.value = mutableState.value.copy(interruptionListening = false, microphoneActive = false)
        scope.launch {
            transport?.sendText(responseCancel(activeId))
            finishUtterance(scheduleConversation = false, expectedUtteranceId = activeId)
            delay(150)
            if (mutableState.value.conversationActive) beginUtterance(automaticEndpoint = true)
        }
    }

    private fun beginUtterance(automaticEndpoint: Boolean) {
        if (!ready || utteranceId != null) return
        serverActionInProgress = false
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = null
        bargeInJob?.cancel()
        bargeInJob = null
        val id = UUID.randomUUID().toString()
        utteranceId = id
        endpointDetector = if (automaticEndpoint) SpeechEndDetector() else null
        automaticEndpointHandled = false
        timeline = Timeline(buttonDown = now())
        logTime("button_press", timeline.buttonDown)
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Listening, microphoneActive = true, partialTranscript = "",
            finalTranscript = "", responseText = "", latencies = Latencies(), errorMessage = null,
            droppedCaptureFrames = 0, interruptionListening = false,
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
                        when (endpointDetector?.accept(frame)) {
                            SpeechDetection.EndOfSpeech -> handleAutomaticEndpoint(id)
                            SpeechDetection.NoSpeechTimeout -> handleNoSpeechTimeout(id)
                            else -> Unit
                        }
                    },
                    onFirstFrame = { updateLatencies() },
                    echoCancellation = automaticEndpoint,
                )
            }.onFailure { fail(it.message ?: "Mikrofonfel") }
        }
    }

    fun stopTalking(cancelledGesture: Boolean = false) {
        val id = utteranceId ?: return
        endpointDetector = null
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
        val responseTimeoutMs = if (llmModel == "qwen3.8:27b") 45_000L else 15_000L
        val responseTimeoutSeconds = responseTimeoutMs / 1_000
        armTimeout(id, responseTimeoutMs, "Servern svarade inte inom $responseTimeoutSeconds sekunder")
    }

    fun cancelResponse() {
        val id = utteranceId ?: return
        if (mutableState.value.conversationActive) {
            interruptAndListen()
            return
        }
        microphone.stop()
        speaker.stop()
        scope.launch { transport?.sendText(responseCancel(id)) }
        finishUtterance()
    }

    fun onPause() {
        mutableState.value = mutableState.value.copy(conversationActive = false, interruptionListening = false)
        nextConversationTurnJob?.cancel()
        bargeInJob?.cancel()
        if (utteranceId != null) cancelResponse() else stopResources()
    }

    fun saveLightConfig(device: MagicHomeDevice, name: String, room: String) {
        pendingLightConfig = PendingLightConfig(device, name, room)
        if (!ready) {
            mutableState.value = mutableState.value.copy(
                lightConfigMessage = if (shouldReconnect) {
                    "Väntar på serveranslutning; namnet sparas automatiskt när den är klar."
                } else {
                    "Anslut till servern under Röst först."
                },
            )
            return
        }
        sendPendingLightConfig()
    }

    fun discoverTvs() {
        if (!ready) {
            mutableState.value = mutableState.value.copy(tvMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(tvBusy = true, tvMessage = "Söker NEC-TV på serverns lokalnät…", discoveredTvs = emptyList())
        scope.launch {
            if (transport?.sendText(tvDiscover()) != true) {
                mutableState.value = mutableState.value.copy(tvBusy = false, tvMessage = "Kunde inte starta TV-sökningen.")
            }
        }
    }

    fun saveTv(host: String, name: String, room: String) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(tvMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(tvBusy = true, tvMessage = "Sparar TV:n i serverns tvs.toml…")
        scope.launch {
            if (transport?.sendText(tvConfigUpsert(name.trim(), room.trim(), host.trim())) != true) {
                mutableState.value = mutableState.value.copy(tvBusy = false, tvMessage = "Kunde inte spara TV:n.")
            }
        }
    }

    fun controlTv(target: String, command: String) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(tvMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(tvBusy = true, tvMessage = "Skickar TV-kommandot…")
        scope.launch {
            if (transport?.sendText(tvCommand(target, command)) != true) {
                mutableState.value = mutableState.value.copy(tvBusy = false, tvMessage = "Kunde inte skicka TV-kommandot.")
            }
        }
    }

    fun refreshPump(target: String? = mutableState.value.configuredPumps.firstOrNull()?.name) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(pumpMessage = "Anslut till servern under Röst först.")
            return
        }
        if (target.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(pumpMessage = "Ingen värmepump är konfigurerad.")
            return
        }
        mutableState.value = mutableState.value.copy(pumpBusy = true, pumpMessage = "Läser pumpstatus…")
        scope.launch {
            if (transport?.sendText(pumpStatus(target)) != true) {
                mutableState.value = mutableState.value.copy(pumpBusy = false, pumpMessage = "Kunde inte fråga pumpservern.")
            }
        }
    }

    private fun sendPendingLightConfig() {
        val pending = pendingLightConfig ?: return
        mutableState.value = mutableState.value.copy(lightConfigMessage = "Sparar lampnamnet i serverns TOML…")
        scope.launch {
            val device = pending.device
            val sent = transport?.sendText(
                lightConfigUpsert(pending.name, pending.room, device.ip, device.mac, device.model),
            ) == true
            if (!sent) mutableState.value = mutableState.value.copy(lightConfigMessage = "Kunde inte skicka lampnamnet till servern.")
        }
    }

    override suspend fun onOpen() {
        mutableState.value = mutableState.value.copy(connectionLabel = "Handshake…", status = VoiceStatus.Connecting)
        transport?.sendText(sessionStart(nodeName, character = characterId, voiceId = voiceId, llmModel = llmModel))
    }

    override suspend fun onText(text: String) {
        runCatching { parseServerEvent(text) }
            .onSuccess(::handleEvent)
            .onFailure { fail("Ogiltigt servermeddelande: ${it.message}") }
    }

    override suspend fun onBinary(data: ByteArray) {
        if (cancelledUtteranceId != null) return
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
        nextConversationTurnJob?.cancel()
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
                conversationActive = false,
                interruptionListening = false,
            )
            return
        }
        if (shouldReconnect) {
            mutableState.value = mutableState.value.copy(
                connectionLabel = "Frånkopplad: ${cause?.message ?: "servern stängde"}",
                status = VoiceStatus.Connecting, canTalk = false, microphoneActive = false,
                conversationActive = false,
                interruptionListening = false,
            )
        }
    }

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> {
                ready = true
                mutableState.value = mutableState.value.copy(
                    connectionLabel = "Ansluten",
                    status = VoiceStatus.Idle,
                    canTalk = true,
                    errorMessage = null,
                    llmModel = event.llmModel,
                    availableLlmModels = event.availableLlmModels,
                )
                sendPendingLightConfig()
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
                if (event.utteranceId != utteranceId || event.utteranceId == cancelledUtteranceId) return
                cancelledUtteranceId = null
                timeoutJob?.cancel()
                timeoutJob = null
                mutableState.value = mutableState.value.copy(status = VoiceStatus.Speaking)
                speaker.start(
                    AudioStreamFormat("pcm_s16le", event.sampleRate, event.channels),
                    onPlaybackStarted = {
                        timeline.playbackStart = now()
                        logTime("audio_playback_start", timeline.playbackStart)
                        updateLatencies()
                        startBargeInMonitoring(event.utteranceId)
                    },
                    onPlaybackError = { message ->
                        scope.launch {
                            if (utteranceId == event.utteranceId) fail("Ljuduppspelningen avbröts: $message")
                        }
                    },
                )
            }
            is ServerEvent.TtsEnd -> {
                if (event.utteranceId != utteranceId || event.utteranceId == cancelledUtteranceId) return
                logTime("last_tts_frame", timeline.lastTts)
                speaker.finish {
                    if (!serverActionInProgress) finishUtterance(expectedUtteranceId = event.utteranceId)
                }
            }
            is ServerEvent.Cancelled -> {
                if (event.utteranceId == cancelledUtteranceId) cancelledUtteranceId = null
                finishUtterance(expectedUtteranceId = event.utteranceId)
            }
            is ServerEvent.ActionRequest -> handleAction(event)
            is ServerEvent.ActionStatus -> {
                serverActionInProgress = true
                utteranceId?.let { armTimeout(it, 45_000, "Åtgärden svarade inte inom 45 sekunder") }
                mutableState.value = mutableState.value.copy(
                    status = VoiceStatus.Processing,
                    actionMessage = event.message,
                    canTalk = false,
                )
            }
            is ServerEvent.ActionCompleted -> {
                val speechStillPlaying =
                    event.status == "completed" && mutableState.value.status == VoiceStatus.Speaking
                serverActionInProgress = false
                if (!speechStillPlaying) finishUtterance()
                mutableState.value = mutableState.value.copy(
                    status = when {
                        event.status != "completed" -> VoiceStatus.Error
                        speechStillPlaying -> VoiceStatus.Speaking
                        else -> VoiceStatus.Idle
                    },
                    actionMessage = event.message,
                    errorMessage = if (event.status == "completed") null else event.message,
                    pendingAction = null,
                    canTalk = ready && !speechStillPlaying,
                )
            }
            is ServerEvent.LightsConfig -> {
                pendingLightConfig = null
                mutableState.value = mutableState.value.copy(
                    configuredLights = event.lights,
                    lightConfigMessage = if (event.lights.isEmpty()) null else "Namn och rum är sparade i serverns lights.toml.",
                )
            }
            is ServerEvent.TvsConfig -> mutableState.value = mutableState.value.copy(
                configuredTvs = event.televisions, tvBusy = false,
                tvMessage = if (event.televisions.isEmpty()) "Ingen TV sparad ännu." else "TV-konfigurationen är sparad i serverns tvs.toml.",
            )
            is ServerEvent.TvsDiscovered -> mutableState.value = mutableState.value.copy(
                discoveredTvs = event.televisions, tvBusy = false,
                tvMessage = if (event.televisions.isEmpty()) "Ingen enhet med NEC-port 7142 hittades." else "Hittade ${event.televisions.size} möjlig NEC-TV.",
            )
            is ServerEvent.TvCommandResult -> mutableState.value = mutableState.value.copy(tvBusy = false, tvMessage = event.message)
            is ServerEvent.PumpsConfig -> {
                mutableState.value = mutableState.value.copy(
                    configuredPumps = event.pumps,
                    pumpMessage = if (event.pumps.isEmpty()) "Ingen värmepump är konfigurerad." else "Värmepumpen är kopplad till EutherVox.",
                )
                if (event.pumps.isNotEmpty()) refreshPump(event.pumps.first().name)
            }
            is ServerEvent.PumpStatusResult -> mutableState.value = mutableState.value.copy(
                pumpState = event.pump,
                pumpBusy = false,
                pumpMessage = if (event.pump.online) "Status uppdaterad." else "Offline – visar senaste kända mätning.",
            )
            is ServerEvent.Error -> if (event.code.startsWith("PUMP_")) {
                mutableState.value = mutableState.value.copy(pumpBusy = false, pumpMessage = event.message)
            } else if (event.code.startsWith("TV_")) {
                mutableState.value = mutableState.value.copy(tvBusy = false, tvMessage = event.message)
            } else {
                fail("${event.code}: ${event.message}", event.recoverable)
            }
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

    private fun finishUtterance(scheduleConversation: Boolean = true, expectedUtteranceId: String? = null) {
        if (expectedUtteranceId != null && utteranceId != expectedUtteranceId) return
        serverActionInProgress = false
        timeoutJob?.cancel()
        timeoutJob = null
        endpointDetector = null
        bargeInJob?.cancel()
        bargeInJob = null
        microphone.stop()
        utteranceId = null
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Idle,
            microphoneActive = false,
            interruptionListening = false,
            canTalk = ready,
        )
        if (scheduleConversation && mutableState.value.conversationActive && mutableState.value.pendingAction == null) {
            scheduleNextConversationTurn()
        }
    }

    private fun scheduleNextConversationTurn(delayMs: Long = 350) {
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = scope.launch {
            delay(delayMs)
            if (
                ready && utteranceId == null && mutableState.value.conversationActive &&
                mutableState.value.pendingAction == null
            ) {
                beginUtterance(automaticEndpoint = true)
            }
        }
    }

    private fun startBargeInMonitoring(id: String) {
        if (!mutableState.value.conversationActive || !microphone.supportsEchoCancellation) return
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            delay(500)
            if (
                utteranceId != id || !mutableState.value.conversationActive ||
                mutableState.value.status != VoiceStatus.Speaking
            ) return@launch
            val detector = SpeechEndDetector(
                minimumSpeechRms = 800.0,
                speechStartMs = 160,
                trailingSilenceMs = 1_000,
                noSpeechTimeoutMs = 120_000,
            )
            var triggered = false
            runCatching {
                microphone.start(
                    onFrame = { frame ->
                        if (!triggered && detector.accept(frame) == SpeechDetection.SpeechStarted) {
                            triggered = true
                            scope.launch {
                                if (utteranceId == id && mutableState.value.conversationActive) interruptAndListen()
                            }
                        }
                    },
                    onFirstFrame = {
                        mutableState.value = mutableState.value.copy(
                            microphoneActive = true,
                            interruptionListening = true,
                        )
                    },
                    echoCancellation = true,
                )
            }.onFailure { error ->
                Log.w("EutherVoxAudio", "Automatiskt talavbrott kunde inte startas", error)
                mutableState.value = mutableState.value.copy(
                    microphoneActive = false,
                    interruptionListening = false,
                )
            }
        }
    }

    private fun handleAutomaticEndpoint(id: String) {
        if (automaticEndpointHandled || utteranceId != id) return
        automaticEndpointHandled = true
        scope.launch { stopTalking() }
    }

    private fun handleNoSpeechTimeout(id: String) {
        if (automaticEndpointHandled || utteranceId != id) return
        automaticEndpointHandled = true
        endpointDetector = null
        microphone.stop()
        mutableState.value = mutableState.value.copy(
            conversationActive = false,
            microphoneActive = false,
            interruptionListening = false,
            status = VoiceStatus.Idle,
            actionMessage = "Samtalet avslutades eftersom jag inte hörde något.",
            canTalk = ready,
        )
        scope.launch { transport?.sendText(responseCancel(id)) }
        utteranceId = null
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
        serverActionInProgress = false
        endpointDetector = null
        bargeInJob?.cancel()
        bargeInJob = null
        microphone.stop()
        speaker.stop()
        timeoutJob?.cancel()
        nextConversationTurnJob?.cancel()
        utteranceId = null
        cancelledUtteranceId = null
    }

    private fun fail(message: String, recoverable: Boolean = true) {
        stopResources()
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Error, errorMessage = message, microphoneActive = false,
            canTalk = false, conversationActive = false,
            interruptionListening = false,
        )
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
