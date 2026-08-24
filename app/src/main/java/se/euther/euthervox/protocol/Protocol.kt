package se.euther.euthervox.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser

const val PROTOCOL_VERSION = 1

fun sessionStart(
    nodeName: String = "android-phone",
    character: String = "skinnskattaren",
    voiceId: String = "piper-nst",
    llmModel: String = "",
): String =
    JsonObject().apply {
        addProperty("type", "session.start")
        addProperty("protocol_version", PROTOCOL_VERSION)
        addProperty("client_id", "android-phone")
        addProperty("node_name", nodeName)
        addProperty("room", "mobile")
        addProperty("character", character)
        addProperty("voice_id", voiceId)
        if (llmModel.isNotBlank()) addProperty("llm_model", llmModel)
        add("input_audio", JsonObject().apply {
            addProperty("codec", "pcm_s16le")
            addProperty("sample_rate", 16_000)
            addProperty("channels", 1)
            addProperty("frame_ms", 20)
        })
    }.toString()

fun audioStart(utteranceId: String) = message("audio.start", utteranceId)
fun audioEnd(utteranceId: String) = message("audio.end", utteranceId)
fun responseCancel(utteranceId: String) = message("response.cancel", utteranceId)

fun actionResult(actionId: String, utteranceId: String, status: String, resultMessage: String) = JsonObject().apply {
    addProperty("type", "action.result")
    addProperty("action_id", actionId)
    addProperty("utterance_id", utteranceId)
    addProperty("status", status)
    addProperty("message", resultMessage)
}.toString()

fun actionConfirm(actionId: String) = JsonObject().apply {
    addProperty("type", "action.confirm")
    addProperty("action_id", actionId)
}.toString()

fun lightConfigUpsert(name: String, room: String, host: String, mac: String, model: String) = JsonObject().apply {
    addProperty("type", "light.config.upsert")
    addProperty("name", name)
    addProperty("room", room)
    addProperty("host", host)
    addProperty("mac", mac)
    addProperty("model", model)
}.toString()

fun tvDiscover() = JsonObject().apply { addProperty("type", "tv.discover") }.toString()

fun tvConfigUpsert(name: String, room: String, host: String) = JsonObject().apply {
    addProperty("type", "tv.config.upsert")
    addProperty("name", name)
    addProperty("room", room)
    addProperty("host", host)
    addProperty("port", 7142)
    addProperty("model", "NEC display")
}.toString()

fun tvCommand(target: String, command: String) = JsonObject().apply {
    addProperty("type", "tv.command")
    addProperty("target", target)
    addProperty("command", command)
}.toString()

fun pumpStatus(target: String) = JsonObject().apply {
    addProperty("type", "pump.status")
    addProperty("target", target)
}.toString()

fun washerStatus() = JsonObject().apply { addProperty("type", "washer.status") }.toString()

fun washerCommand(command: String, confirmed: Boolean = false) = JsonObject().apply {
    addProperty("type", "washer.command")
    addProperty("command", command)
    addProperty("confirmed", confirmed)
}.toString()

fun pumpCommand(
    target: String,
    power: Boolean? = null,
    mode: String? = null,
    targetTemperature: Int? = null,
    fanMode: String? = null,
    swing: String? = null,
    powerSelectionPercent: Int? = null,
) = JsonObject().apply {
    addProperty("type", "pump.command")
    addProperty("target", target)
    power?.let { addProperty("power", it) }
    mode?.let { addProperty("mode", it) }
    targetTemperature?.let { addProperty("target_temperature", it) }
    fanMode?.let { addProperty("fan_mode", it) }
    swing?.let { addProperty("swing", it) }
    powerSelectionPercent?.let { addProperty("power_selection_percent", it) }
}.toString()

private fun message(type: String, utteranceId: String) = JsonObject().apply {
    addProperty("type", type)
    addProperty("utterance_id", utteranceId)
}.toString()

sealed interface ServerEvent {
    data class ConfiguredLight(
        val id: String,
        val name: String,
        val room: String,
        val host: String,
        val mac: String,
        val model: String,
    )
    data class ConfiguredTv(val id: String, val name: String, val room: String, val host: String, val port: Int, val model: String)
    data class DiscoveredTv(val host: String, val port: Int, val model: String)
    data class ConfiguredPump(val id: String, val name: String, val room: String)
    data class PumpState(
        val id: String,
        val name: String,
        val room: String,
        val online: Boolean,
        val power: Boolean?,
        val mode: String?,
        val targetTemperature: Double?,
        val roomTemperature: Double?,
        val outdoorTemperature: Double?,
        val fanMode: String?,
        val powerSelectionPercent: Int?,
        val updatedAt: String?,
        val readOnly: Boolean,
    )
    data class WasherState(
        val available: Boolean,
        val online: Boolean,
        val state: String,
        val phase: String?,
        val progressPercent: Int?,
        val remainingSeconds: Int?,
        val program: String?,
        val waterTemperatureC: Int?,
        val spinRpm: Int?,
        val rinseCycles: Int?,
        val remoteControlEnabled: Boolean?,
        val instantaneousPowerW: Double?,
        val cumulativeEnergyKwh: Double?,
        val updatedAt: String?,
    )
    data class WasherStatistics(
        val samples24h: Int,
        val availabilityPercent24h: Double?,
        val cyclesStarted7d: Int,
        val cyclesCompleted7d: Int,
        val runningMinutes7d: Int,
        val energyUsedKwh7d: Double?,
        val lastCompletedAt: String?,
    )
    data class Ready(
        val sessionId: String,
        val llmModel: String = "",
        val availableLlmModels: List<String> = emptyList(),
    ) : ServerEvent
    data class SttPartial(val utteranceId: String, val text: String) : ServerEvent
    data class SttFinal(val utteranceId: String, val text: String) : ServerEvent
    data class TextDelta(val utteranceId: String, val text: String) : ServerEvent
    data class TextFinal(val utteranceId: String, val text: String) : ServerEvent
    data class TtsStart(val utteranceId: String, val sampleRate: Int, val channels: Int) : ServerEvent
    data class TtsEnd(val utteranceId: String) : ServerEvent
    data class Cancelled(val utteranceId: String) : ServerEvent
    data class ActionRequest(
        val actionId: String,
        val utteranceId: String,
        val name: String,
        val targetNode: String,
        val provider: String,
        val query: String,
        val uri: String,
        val requiresConfirmation: Boolean,
        val outputRoom: String = "",
    ) : ServerEvent
    data class ActionStatus(val actionId: String, val status: String, val message: String) : ServerEvent
    data class ActionCompleted(val actionId: String, val status: String, val message: String) : ServerEvent
    data class LightsConfig(val lights: List<ConfiguredLight>) : ServerEvent
    data class TvsConfig(val televisions: List<ConfiguredTv>) : ServerEvent
    data class TvsDiscovered(val televisions: List<DiscoveredTv>) : ServerEvent
    data class TvCommandResult(val status: String, val message: String) : ServerEvent
    data class PumpsConfig(val pumps: List<ConfiguredPump>) : ServerEvent
    data class PumpStatusResult(val pump: PumpState) : ServerEvent
    data class PumpCommandResult(val pump: PumpState, val message: String) : ServerEvent
    data class WasherConfig(val available: Boolean, val controlsAvailable: Boolean) : ServerEvent
    data class WasherStatusResult(val washer: WasherState, val statistics: WasherStatistics) : ServerEvent
    data class WasherCommandResult(val command: String, val washer: WasherState, val message: String) : ServerEvent
    data class Error(val code: String, val message: String, val recoverable: Boolean) : ServerEvent
    data class Unknown(val type: String) : ServerEvent
}

fun parseServerEvent(raw: String): ServerEvent {
    val json = JsonParser.parseString(raw).asJsonObject
    val type = json["type"].asString
    val utterance = json["utterance_id"]?.asString.orEmpty()
    return when (type) {
        "session.ready" -> ServerEvent.Ready(
            sessionId = json["session_id"].asString,
            llmModel = json["llm_model"]?.asString.orEmpty(),
            availableLlmModels = json["available_llm_models"]?.asJsonArray?.map { it.asString }.orEmpty(),
        )
        "stt.partial" -> ServerEvent.SttPartial(utterance, json["text"].asString)
        "stt.final" -> ServerEvent.SttFinal(utterance, json["text"].asString)
        "assistant.text.delta" -> ServerEvent.TextDelta(utterance, json["text"].asString)
        "assistant.text.final" -> ServerEvent.TextFinal(utterance, json["text"].asString)
        "tts.start" -> json["audio"].asJsonObject.let {
            ServerEvent.TtsStart(utterance, it["sample_rate"].asInt, it["channels"].asInt)
        }
        "tts.end" -> ServerEvent.TtsEnd(utterance)
        "response.cancelled" -> ServerEvent.Cancelled(utterance)
        "action.request" -> ServerEvent.ActionRequest(
            actionId = json["action_id"].asString,
            utteranceId = utterance,
            name = json["name"].asString,
            targetNode = json["target"].asJsonObject["node_name"].asString,
            provider = json["arguments"].asJsonObject["provider"].asString,
            query = json["arguments"].asJsonObject["query"]?.asString.orEmpty(),
            uri = json["arguments"].asJsonObject["uri"]?.asString.orEmpty(),
            requiresConfirmation = json["requires_confirmation"]?.asBoolean ?: true,
            outputRoom = json["arguments"].asJsonObject["output_room"]?.asString.orEmpty(),
        )
        "action.status" -> ServerEvent.ActionStatus(
            json["action_id"].asString,
            json["status"].asString,
            json["message"].asString,
        )
        "action.completed" -> ServerEvent.ActionCompleted(
            json["action_id"].asString,
            json["status"].asString,
            json["message"].asString,
        )
        "lights.config" -> ServerEvent.LightsConfig(
            json["lights"].asJsonArray.map { item ->
                item.asJsonObject.let { light ->
                    ServerEvent.ConfiguredLight(
                        id = light["id"].asString,
                        name = light["name"].asString,
                        room = light["room"].asString,
                        host = light["host"]?.asString.orEmpty(),
                        mac = light["mac"]?.asString.orEmpty(),
                        model = light["model"].asString,
                    )
                }
            },
        )
        "tvs.config" -> ServerEvent.TvsConfig(
            json["tvs"].asJsonArray.map { item -> item.asJsonObject.let { tv ->
                ServerEvent.ConfiguredTv(
                    id = tv["id"].asString, name = tv["name"].asString, room = tv["room"].asString,
                    host = tv["host"].asString, port = tv["port"]?.asInt ?: 7142, model = tv["model"]?.asString ?: "NEC display",
                )
            } },
        )
        "tvs.discovered" -> ServerEvent.TvsDiscovered(
            json["tvs"].asJsonArray.map { item -> item.asJsonObject.let { tv ->
                ServerEvent.DiscoveredTv(tv["host"].asString, tv["port"]?.asInt ?: 7142, tv["model"]?.asString ?: "NEC display")
            } },
        )
        "tv.command.result" -> ServerEvent.TvCommandResult(json["status"].asString, json["message"].asString)
        "pumps.config" -> ServerEvent.PumpsConfig(
            json["pumps"].asJsonArray.map { item -> item.asJsonObject.let { pump ->
                ServerEvent.ConfiguredPump(
                    id = pump["id"].asString,
                    name = pump["name"].asString,
                    room = pump["room"].asString,
                )
            } },
        )
        "pump.status.result" -> ServerEvent.PumpStatusResult(parsePumpState(json["pump"].asJsonObject))
        "pump.command.result" -> ServerEvent.PumpCommandResult(
            parsePumpState(json["pump"].asJsonObject),
            json["message"]?.asString ?: "Pumpen bekräftade ändringen.",
        )
        "washer.config" -> ServerEvent.WasherConfig(
            json["available"]?.asBoolean ?: false,
            json["controls_available"]?.asBoolean ?: false,
        )
        "washer.status.result" -> ServerEvent.WasherStatusResult(
            washer = json["status"].asJsonObject.let { washer -> ServerEvent.WasherState(
                available = washer["available"]?.asBoolean ?: false,
                online = washer["online"]?.asBoolean ?: false,
                state = washer["state"]?.asString ?: "unknown",
                phase = washer["phase"]?.takeUnless { it.isJsonNull }?.asString,
                progressPercent = washer["progress_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                remainingSeconds = washer["remaining_seconds"]?.takeUnless { it.isJsonNull }?.asInt,
                program = washer["program"]?.takeUnless { it.isJsonNull }?.asString,
                waterTemperatureC = washer["water_temperature_c"]?.takeUnless { it.isJsonNull }?.asInt,
                spinRpm = washer["spin_rpm"]?.takeUnless { it.isJsonNull }?.asInt,
                rinseCycles = washer["rinse_cycles"]?.takeUnless { it.isJsonNull }?.asInt,
                remoteControlEnabled = washer["remote_control_enabled"]?.takeUnless { it.isJsonNull }?.asBoolean,
                instantaneousPowerW = washer["instantaneous_power_w"]?.takeUnless { it.isJsonNull }?.asDouble,
                cumulativeEnergyKwh = washer["cumulative_energy_kwh"]?.takeUnless { it.isJsonNull }?.asDouble,
                updatedAt = washer["updated_at"]?.takeUnless { it.isJsonNull }?.asString,
            ) },
            statistics = json["statistics"].asJsonObject.let { stats -> ServerEvent.WasherStatistics(
                samples24h = stats["samples_24h"]?.asInt ?: 0,
                availabilityPercent24h = stats["availability_percent_24h"]?.takeUnless { it.isJsonNull }?.asDouble,
                cyclesStarted7d = stats["cycles_started_7d"]?.asInt ?: 0,
                cyclesCompleted7d = stats["cycles_completed_7d"]?.asInt ?: 0,
                runningMinutes7d = stats["running_minutes_7d"]?.asInt ?: 0,
                energyUsedKwh7d = stats["energy_used_kwh_7d"]?.takeUnless { it.isJsonNull }?.asDouble,
                lastCompletedAt = stats["last_completed_at"]?.takeUnless { it.isJsonNull }?.asString,
            ) },
        )
        "washer.command.result" -> ServerEvent.WasherCommandResult(
            command = json["command"]?.asString.orEmpty(),
            washer = json["status"].asJsonObject.let { washer -> ServerEvent.WasherState(
                available = washer["available"]?.asBoolean ?: false,
                online = washer["online"]?.asBoolean ?: false,
                state = washer["state"]?.asString ?: "unknown",
                phase = washer["phase"]?.takeUnless { it.isJsonNull }?.asString,
                progressPercent = washer["progress_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                remainingSeconds = washer["remaining_seconds"]?.takeUnless { it.isJsonNull }?.asInt,
                program = washer["program"]?.takeUnless { it.isJsonNull }?.asString,
                waterTemperatureC = washer["water_temperature_c"]?.takeUnless { it.isJsonNull }?.asInt,
                spinRpm = washer["spin_rpm"]?.takeUnless { it.isJsonNull }?.asInt,
                rinseCycles = washer["rinse_cycles"]?.takeUnless { it.isJsonNull }?.asInt,
                remoteControlEnabled = washer["remote_control_enabled"]?.takeUnless { it.isJsonNull }?.asBoolean,
                instantaneousPowerW = washer["instantaneous_power_w"]?.takeUnless { it.isJsonNull }?.asDouble,
                cumulativeEnergyKwh = washer["cumulative_energy_kwh"]?.takeUnless { it.isJsonNull }?.asDouble,
                updatedAt = washer["updated_at"]?.takeUnless { it.isJsonNull }?.asString,
            ) },
            message = json["message"]?.asString ?: "Tvättmaskinen bekräftade ändringen.",
        )
        "error" -> ServerEvent.Error(json["code"].asString, json["message"].asString, json["recoverable"]?.asBoolean ?: false)
        else -> ServerEvent.Unknown(type)
    }
}

private fun parsePumpState(pump: JsonObject) = ServerEvent.PumpState(
    id = pump["id"].asString,
    name = pump["name"].asString,
    room = pump["room"].asString,
    online = pump["online"]?.asBoolean ?: false,
    power = pump["power"]?.takeUnless { it.isJsonNull }?.asBoolean,
    mode = pump["mode"]?.takeUnless { it.isJsonNull }?.asString,
    targetTemperature = pump["target_temperature"]?.takeUnless { it.isJsonNull }?.asDouble,
    roomTemperature = pump["room_temperature"]?.takeUnless { it.isJsonNull }?.asDouble,
    outdoorTemperature = pump["outdoor_temperature"]?.takeUnless { it.isJsonNull }?.asDouble,
    fanMode = pump["fan_mode"]?.takeUnless { it.isJsonNull }?.asString,
    powerSelectionPercent = pump["power_selection_percent"]?.takeUnless { it.isJsonNull }?.asInt,
    updatedAt = pump["updated_at"]?.takeUnless { it.isJsonNull }?.asString,
    readOnly = pump["read_only"]?.asBoolean ?: true,
)
