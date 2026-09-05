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
import se.euther.euthervox.protocol.pumpCommand
import se.euther.euthervox.protocol.washerStatus
import se.euther.euthervox.protocol.vacuumStatus
import se.euther.euthervox.protocol.vacuumMaps
import se.euther.euthervox.protocol.vacuumCommand
import se.euther.euthervox.protocol.washerCommand
import se.euther.euthervox.protocol.washerScheduleCancel
import se.euther.euthervox.protocol.washerScheduleCreate
import se.euther.euthervox.protocol.washerScheduleStatus
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
    val automaticBargeInEnabled: Boolean = false,
    val voiceDiagnostics: List<String> = emptyList(),
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
    val washerState: ServerEvent.WasherState? = null,
    val washerStatistics: ServerEvent.WasherStatistics? = null,
    val washerPrograms: List<ServerEvent.WasherProgram> = emptyList(),
    val washerWaterTemperatures: List<String> = emptyList(),
    val washerSchedule: ServerEvent.WasherSchedule? = null,
    val washerMessage: String? = null,
    val washerBusy: Boolean = false,
    val washerControlsAvailable: Boolean = false,
    val vacuumState: ServerEvent.VacuumState? = null,
    val vacuumMaps: ServerEvent.VacuumMaps? = null,
    val vacuumMapsBusy: Boolean = false,
    val vacuumMapsMessage: String? = null,
    val vacuumMessage: String? = null,
    val vacuumBusy: Boolean = false,
    val vacuumControlsAvailable: Boolean = false,
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

private data class ConnectionIdentity(
    val address: String,
    val nodeName: String,
    val username: String,
    val voiceId: String,
    val characterId: String,
    val llmModel: String,
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
    private var connectionIdentity: ConnectionIdentity? = null
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
    @Volatile private var conversationRequested = false
    @Volatile private var automaticBargeInEnabled = false
    private var utteranceId: String? = null
    private var notificationUtteranceId: String? = null
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
        val requestedIdentity = ConnectionIdentity(
            normalized,
            requestedNodeName.ifBlank { "android-phone" },
            username.trim(),
            requestedVoiceId.ifBlank { "piper-nst" },
            requestedCharacterId.ifBlank { "skinnskattaren" },
            requestedLlmModel.trim(),
        )
        if (shouldReconnect && connectionIdentity == requestedIdentity && password.isBlank()) return
        disconnect()
        connectionIdentity = requestedIdentity
        address = normalized
        nodeName = requestedIdentity.nodeName
        characterId = requestedIdentity.characterId
        voiceId = requestedIdentity.voiceId
        llmModel = requestedIdentity.llmModel
        shouldReconnect = true
        recordDiagnostic("transport.connect_requested")
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
        conversationRequested = false
        connectionIdentity = null
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
        conversationRequested = true
        recordDiagnostic("conversation.requested")
        mutableState.value = mutableState.value.copy(
            conversationActive = true,
            actionMessage = "Samtalsläge aktivt – jag lyssnar tills du gör en kort paus.",
            errorMessage = null,
        )
        beginUtterance(automaticEndpoint = true)
    }

    fun stopConversation() {
        conversationRequested = false
        recordDiagnostic("conversation.stopped")
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
        if (!conversationRequested) return
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
            if (conversationRequested) beginUtterance(automaticEndpoint = true)
        }
    }

    fun setAutomaticBargeInEnabled(enabled: Boolean) {
        automaticBargeInEnabled = enabled
        if (!enabled) {
            bargeInJob?.cancel()
            bargeInJob = null
            if (mutableState.value.status == VoiceStatus.Speaking) microphone.stop()
        }
        mutableState.value = mutableState.value.copy(
            automaticBargeInEnabled = enabled,
            interruptionListening = enabled && mutableState.value.interruptionListening,
            microphoneActive = if (!enabled && mutableState.value.status == VoiceStatus.Speaking) false else mutableState.value.microphoneActive,
        )
        recordDiagnostic(if (enabled) "barge_in.enabled" else "barge_in.disabled")
    }

    private fun beginUtterance(automaticEndpoint: Boolean) {
        if (!ready || utteranceId != null) return
        speaker.stop()
        serverActionInProgress = false
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = null
        bargeInJob?.cancel()
        bargeInJob = null
        val id = UUID.randomUUID().toString()
        utteranceId = id
        recordDiagnostic("turn.listening")
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
        recordDiagnostic(if (cancelledGesture) "turn.cancel_requested" else "turn.processing")
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
        if (conversationRequested) {
            interruptAndListen()
            return
        }
        microphone.stop()
        speaker.stop()
        scope.launch { transport?.sendText(responseCancel(id)) }
        finishUtterance()
    }

    fun onPause() {
        conversationRequested = false
        mutableState.value = mutableState.value.copy(conversationActive = false, interruptionListening = false)
        nextConversationTurnJob?.cancel()
        bargeInJob?.cancel()
        microphone.stop()
        if (!keepNotificationOnPause(utteranceId, notificationUtteranceId)) {
            if (utteranceId != null) cancelResponse() else stopResources()
        }
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

    fun controlPump(
        target: String,
        power: Boolean? = null,
        mode: String? = null,
        targetTemperature: Int? = null,
        fanMode: String? = null,
        swing: String? = null,
        powerSelectionPercent: Int? = null,
    ) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(pumpMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(pumpBusy = true, pumpMessage = "Skickar och väntar på pumpens bekräftelse…")
        scope.launch {
            val sent = transport?.sendText(pumpCommand(
                target, power, mode, targetTemperature, fanMode, swing, powerSelectionPercent,
            )) == true
            if (!sent) mutableState.value = mutableState.value.copy(pumpBusy = false, pumpMessage = "Kunde inte skicka pumpkommandot.")
        }
    }

    fun refreshWasher() {
        if (!ready) {
            mutableState.value = mutableState.value.copy(washerMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(washerBusy = true, washerMessage = "Läser tvättstatus…")
        scope.launch {
            if (transport?.sendText(washerStatus()) != true) {
                mutableState.value = mutableState.value.copy(washerBusy = false, washerMessage = "Kunde inte fråga tvättmaskinstjänsten.")
            }
            transport?.sendText(washerScheduleStatus())
        }
    }

    fun createWasherSchedule(
        scheduledFor: String, programCode: String, waterTemperature: String?
    ) {
        if (!ready) return
        mutableState.value = mutableState.value.copy(
            washerBusy = true,
            washerMessage = "Sparar tvättschemat på servern…",
        )
        scope.launch {
            if (
                transport?.sendText(
                    washerScheduleCreate(scheduledFor, programCode, waterTemperature)
                ) != true
            ) {
                mutableState.value = mutableState.value.copy(
                    washerBusy = false,
                    washerMessage = "Kunde inte spara tvättschemat.",
                )
            }
        }
    }

    fun cancelWasherSchedule() {
        if (!ready) return
        mutableState.value = mutableState.value.copy(washerBusy = true, washerMessage = "Avbryter tvättschemat…")
        scope.launch { transport?.sendText(washerScheduleCancel()) }
    }

    fun refreshVacuum() {
        if (!ready) {
            mutableState.value = mutableState.value.copy(vacuumMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(vacuumBusy = true, vacuumMessage = "Läser dammsugarstatus…")
        scope.launch {
            if (transport?.sendText(vacuumStatus()) != true) {
                mutableState.value = mutableState.value.copy(vacuumBusy = false, vacuumMessage = "Kunde inte fråga dammsugartjänsten.")
            }
        }
    }

    fun refreshVacuumMaps() {
        if (!ready) {
            mutableState.value = mutableState.value.copy(vacuumMapsMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(vacuumMapsBusy = true, vacuumMapsMessage = "Läser den lokala kartan…")
        scope.launch {
            if (transport?.sendText(vacuumMaps()) != true) {
                mutableState.value = mutableState.value.copy(vacuumMapsBusy = false, vacuumMapsMessage = "Kunde inte hämta kartan.")
            }
        }
    }

    fun controlVacuum(command: String, confirmed: Boolean = false) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(vacuumMessage = "Anslut till servern under Röst först.")
            return
        }
        val progress = when (command) {
            "start" -> "Startar robotdammsugaren…"
            "pause" -> "Pausar robotdammsugaren…"
            "stop" -> "Stoppar robotdammsugaren…"
            "return-to-dock" -> "Skickar robotdammsugaren till laddaren…"
            "start-fast-mapping" -> "Startar en ny snabbkarta…"
            else -> "Skickar dammsugarkommandot…"
        }
        mutableState.value = mutableState.value.copy(vacuumBusy = true, vacuumMessage = progress)
        scope.launch {
            if (transport?.sendText(vacuumCommand(command, confirmed)) != true) {
                mutableState.value = mutableState.value.copy(vacuumBusy = false, vacuumMessage = "Kunde inte skicka dammsugarkommandot.")
            }
        }
    }

    fun controlWasher(command: String, confirmed: Boolean = false,
                      programCode: String? = null, waterTemperature: String? = null) {
        if (!ready) {
            mutableState.value = mutableState.value.copy(washerMessage = "Anslut till servern under Röst först.")
            return
        }
        mutableState.value = mutableState.value.copy(
            washerBusy = true,
            washerMessage = "Skickar och väntar på tvättmaskinens bekräftelse…",
        )
        scope.launch {
            if (transport?.sendText(washerCommand(command, confirmed, programCode, waterTemperature)) != true) {
                mutableState.value = mutableState.value.copy(
                    washerBusy = false,
                    washerMessage = "Kunde inte skicka tvättkommandot.",
                )
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
        recordDiagnostic("transport.open")
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
        if (!speaker.enqueue(data)) {
            recordDiagnostic("playback.queue_unavailable")
            fail("Ljuduppspelningen tappade sitt tillstånd")
        }
    }

    override suspend fun onClosed(cause: Throwable?) {
        ready = false
        nextConversationTurnJob?.cancel()
        stopResources()
        recordDiagnostic("transport.closed.${cause?.javaClass?.simpleName ?: "clean"}")
        if (cause?.message?.contains("(401)") == true) {
            conversationRequested = false
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
                conversationActive = conversationRequested,
                interruptionListening = false,
                actionMessage = if (conversationRequested) "Samtalet återansluter automatiskt…" else mutableState.value.actionMessage,
            )
        }
    }

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> {
                ready = true
                recordDiagnostic("transport.ready")
                mutableState.value = mutableState.value.copy(
                    connectionLabel = "Ansluten",
                    status = VoiceStatus.Idle,
                    canTalk = true,
                    errorMessage = null,
                    llmModel = event.llmModel,
                    availableLlmModels = event.availableLlmModels,
                    conversationActive = conversationRequested,
                    actionMessage = if (conversationRequested) "Ansluten igen – lyssningen återstartas." else mutableState.value.actionMessage,
                )
                sendPendingLightConfig()
                if (conversationRequested) scheduleNextConversationTurn(250)
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
            is ServerEvent.Notification -> {
                if (utteranceId != null || event.utteranceId.isBlank()) return
                utteranceId = event.utteranceId
                notificationUtteranceId = event.utteranceId
                serverActionInProgress = false
                timeline = Timeline()
                mutableState.value = mutableState.value.copy(
                    status = VoiceStatus.Processing,
                    canTalk = false,
                    microphoneActive = false,
                    partialTranscript = "",
                    finalTranscript = "",
                    responseText = event.text,
                    actionMessage = event.title,
                    errorMessage = null,
                    interruptionListening = false,
                )
            }
            is ServerEvent.TtsStart -> {
                if (event.utteranceId != utteranceId || event.utteranceId == cancelledUtteranceId) return
                cancelledUtteranceId = null
                timeoutJob?.cancel()
                timeoutJob = null
                mutableState.value = mutableState.value.copy(status = VoiceStatus.Speaking)
                recordDiagnostic("turn.speaking")
                runCatching {
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
                }.onFailure { fail("Ljuduppspelningen kunde inte starta") }
            }
            is ServerEvent.TtsEnd -> {
                if (event.utteranceId != utteranceId || event.utteranceId == cancelledUtteranceId) return
                logTime("last_tts_frame", timeline.lastTts)
                recordDiagnostic("turn.tts_complete")
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
            is ServerEvent.PumpCommandResult -> mutableState.value = mutableState.value.copy(
                pumpState = event.pump,
                pumpBusy = false,
                pumpMessage = event.message,
            )
            is ServerEvent.WasherConfig -> {
                mutableState.value = mutableState.value.copy(
                    washerControlsAvailable = event.controlsAvailable,
                    washerMessage = if (event.available) "Tvättmaskinen är kopplad till EutherVox." else "Tvättmaskinstjänsten är inte tillgänglig.",
                )
                if (event.available) refreshWasher()
            }
            is ServerEvent.WasherStatusResult -> mutableState.value = mutableState.value.copy(
                washerState = event.washer,
                washerStatistics = event.statistics,
                washerBusy = false,
                washerMessage = if (event.washer.online) "Status och statistik uppdaterade." else "Tvättmaskinen är offline.",
            )
            is ServerEvent.WasherCommandResult -> mutableState.value = mutableState.value.copy(
                washerState = event.washer,
                washerBusy = false,
                washerMessage = event.message,
            )
            is ServerEvent.WasherScheduleResult -> mutableState.value = mutableState.value.copy(
                washerPrograms = event.programs,
                washerWaterTemperatures = event.waterTemperatures,
                washerSchedule = event.schedule,
                washerBusy = false,
                washerMessage = when (event.schedule?.state) {
                    "scheduled" -> "Tvätten är schemalagd."
                    "started" -> "Den schemalagda tvätten har startat."
                    "failed" -> "Den schemalagda starten misslyckades: ${event.schedule.failureCode ?: "okänt fel"}."
                    "cancelled" -> "Tvättschemat är avbrutet."
                    else -> "Program och schema uppdaterade."
                },
            )
            is ServerEvent.VacuumConfig -> {
                mutableState.value = mutableState.value.copy(
                    vacuumMessage = if (event.available) "Robotdammsugaren är kopplad till EutherVox." else "Dammsugartjänsten är inte tillgänglig.",
                    vacuumControlsAvailable = event.controlsAvailable,
                )
                if (event.available) {
                    refreshVacuum()
                    refreshVacuumMaps()
                }
            }
            is ServerEvent.VacuumStatusResult -> mutableState.value = mutableState.value.copy(
                vacuumState = event.vacuum,
                vacuumBusy = false,
                vacuumMessage = if (event.vacuum.online) "Dammsugarstatus uppdaterad." else "Robotdammsugaren är offline.",
            )
            is ServerEvent.VacuumCommandResult -> mutableState.value = mutableState.value.copy(
                vacuumState = event.vacuum,
                vacuumBusy = false,
                vacuumMessage = event.message,
            )
            is ServerEvent.VacuumMapsResult -> mutableState.value = mutableState.value.copy(
                vacuumMaps = event.vacuumMaps,
                vacuumMapsBusy = false,
                vacuumMapsMessage = when {
                    event.vacuumMaps.maps.isNotEmpty() -> "Lokal karta uppdaterad."
                    event.vacuumMaps.available -> "Kartarkivet innehåller ännu ingen karta."
                    else -> "Ingen lokal karta är konfigurerad ännu."
                },
            )
            is ServerEvent.Error -> if (event.code.startsWith("PUMP_")) {
                mutableState.value = mutableState.value.copy(pumpBusy = false, pumpMessage = event.message)
            } else if (event.code.startsWith("WASHER_")) {
                mutableState.value = mutableState.value.copy(washerBusy = false, washerMessage = event.message)
            } else if (event.code.startsWith("VACUUM_")) {
                mutableState.value = mutableState.value.copy(
                    vacuumBusy = false,
                    vacuumMapsBusy = false,
                    vacuumMessage = event.message,
                    vacuumMapsMessage = if (event.code.startsWith("VACUUM_MAP")) event.message else mutableState.value.vacuumMapsMessage,
                )
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
        notificationUtteranceId = null
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Idle,
            microphoneActive = false,
            interruptionListening = false,
            canTalk = ready,
        )
        recordDiagnostic("turn.idle")
        if (scheduleConversation && conversationRequested && mutableState.value.pendingAction == null) {
            scheduleNextConversationTurn()
        }
    }

    private fun scheduleNextConversationTurn(delayMs: Long = 350) {
        nextConversationTurnJob?.cancel()
        nextConversationTurnJob = scope.launch {
            delay(delayMs)
            if (ConversationRecoveryPolicy.shouldArm(
                    ready = ready,
                    requested = conversationRequested,
                    utteranceActive = utteranceId != null,
                    pendingAction = mutableState.value.pendingAction != null,
                )
            ) {
                beginUtterance(automaticEndpoint = true)
            }
        }
    }

    private fun startBargeInMonitoring(id: String) {
        if (!automaticBargeInEnabled || !conversationRequested || !microphone.supportsEchoCancellation) return
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            delay(500)
            if (
                utteranceId != id || !conversationRequested ||
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
                                if (utteranceId == id && conversationRequested) interruptAndListen()
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
        conversationRequested = false
        recordDiagnostic("conversation.no_speech_timeout")
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
        notificationUtteranceId = null
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
        notificationUtteranceId = null
        cancelledUtteranceId = null
    }

    private fun fail(message: String, recoverable: Boolean = true) {
        val activeId = utteranceId
        if (!recoverable) conversationRequested = false
        val shouldResumeConversation = ConversationRecoveryPolicy.keepRequestedAfterFailure(
            recoverable = recoverable,
            requested = conversationRequested,
        )
        recordDiagnostic(if (recoverable) "failure.recoverable" else "failure.terminal")
        stopResources()
        mutableState.value = mutableState.value.copy(
            status = VoiceStatus.Error, errorMessage = message, microphoneActive = false,
            canTalk = false, conversationActive = shouldResumeConversation,
            interruptionListening = false,
            actionMessage = if (shouldResumeConversation) "Tillfälligt fel – samtalet återupptas automatiskt." else mutableState.value.actionMessage,
        )
        if (recoverable) scope.launch {
            if (activeId != null && ready) transport?.sendText(responseCancel(activeId))
            delay(1_250)
            if (ready) {
                mutableState.value = mutableState.value.copy(
                    status = VoiceStatus.Idle,
                    errorMessage = null,
                    canTalk = true,
                    conversationActive = shouldResumeConversation,
                )
                recordDiagnostic("failure.recovered")
                if (shouldResumeConversation) scheduleNextConversationTurn(250)
            }
        }
    }

    private fun recordDiagnostic(event: String) {
        val elapsed = SystemClock.elapsedRealtime()
        val entry = "$elapsed $event"
        mutableState.value = mutableState.value.copy(
            voiceDiagnostics = (mutableState.value.voiceDiagnostics + entry).takeLast(48),
        )
        Log.i("EutherVoxVoice", entry)
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

internal fun keepNotificationOnPause(activeId: String?, notificationId: String?): Boolean =
    activeId != null && activeId == notificationId
