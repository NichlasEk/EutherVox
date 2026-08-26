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

fun vacuumStatus() = JsonObject().apply { addProperty("type", "vacuum.status") }.toString()

fun vacuumMaps() = JsonObject().apply { addProperty("type", "vacuum.maps") }.toString()

fun vacuumCommand(command: String, confirmed: Boolean = false) = JsonObject().apply {
    addProperty("type", "vacuum.command")
    addProperty("command", command)
    addProperty("confirmed", confirmed)
}.toString()

fun washerCommand(command: String, confirmed: Boolean = false) = JsonObject().apply {
    addProperty("type", "washer.command")
    addProperty("command", command)
    addProperty("confirmed", confirmed)
}.toString()

fun washerScheduleStatus() = JsonObject().apply {
    addProperty("type", "washer.schedule.status")
}.toString()

fun washerScheduleCreate(scheduledFor: String, programCode: String) = JsonObject().apply {
    addProperty("type", "washer.schedule.create")
    addProperty("scheduled_for", scheduledFor)
    addProperty("program_code", programCode)
    addProperty("confirmed", true)
}.toString()

fun washerScheduleCancel() = JsonObject().apply {
    addProperty("type", "washer.schedule.cancel")
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
    data class VacuumState(
        val available: Boolean,
        val online: Boolean,
        val state: String,
        val batteryPercent: Int?,
        val faultCode: Int?,
        val cleaningTimeMinutes: Int?,
        val cleaningAreaM2: Double?,
        val mopAttached: Boolean?,
        val mainBrushPercent: Int?,
        val mainBrushHoursLeft: Int?,
        val sideBrushPercent: Int?,
        val sideBrushHoursLeft: Int?,
        val filterPercent: Int?,
        val filterHoursLeft: Int?,
        val mapAvailable: Boolean?,
        val multipleMapsEnabled: Boolean?,
        val doNotDisturbEnabled: Boolean?,
        val autoEmptyEnabled: Boolean?,
        val maintenanceRequired: Boolean,
        val systemMessages: List<String>,
        val rawDeviceStatus: Int?,
        val rawOperatingMode: Int?,
        val rawTaskStatus: Int?,
        val rawRelocationStatus: Int?,
        val updatedAt: String?,
    )
    data class VacuumMapRun(val y: Int, val x: Int, val length: Int, val roomId: Int?, val wall: Boolean)
    data class VacuumMapRoom(val id: Int, val name: String)
    data class VacuumMapPoint(val x: Double, val y: Double, val angle: Int?)
    data class VacuumMap(
        val name: String,
        val cellSizeMm: Int,
        val width: Int,
        val height: Int,
        val runs: List<VacuumMapRun>,
        val rooms: List<VacuumMapRoom>,
        val robot: VacuumMapPoint?,
        val charger: VacuumMapPoint?,
    )
    data class VacuumMaps(val available: Boolean, val offlineReady: Boolean, val updatedAt: String?, val maps: List<VacuumMap>)
    data class Ready(
        val sessionId: String,
        val llmModel: String = "",
        val availableLlmModels: List<String> = emptyList(),
    ) : ServerEvent
    data class SttPartial(val utteranceId: String, val text: String) : ServerEvent
    data class SttFinal(val utteranceId: String, val text: String) : ServerEvent
    data class TextDelta(val utteranceId: String, val text: String) : ServerEvent
    data class TextFinal(val utteranceId: String, val text: String) : ServerEvent
    data class Notification(val utteranceId: String, val title: String, val text: String) : ServerEvent
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
    data class WasherProgram(val code: String, val name: String)
    data class WasherSchedule(
        val state: String,
        val scheduledFor: String,
        val programCode: String,
        val programName: String,
        val failureCode: String?,
    )
    data class WasherScheduleResult(
        val programs: List<WasherProgram>,
        val schedule: WasherSchedule?,
    ) : ServerEvent
    data class VacuumConfig(val available: Boolean, val controlsAvailable: Boolean) : ServerEvent
    data class VacuumStatusResult(val vacuum: VacuumState) : ServerEvent
    data class VacuumMapsResult(val vacuumMaps: VacuumMaps) : ServerEvent
    data class VacuumCommandResult(val command: String, val vacuum: VacuumState, val message: String) : ServerEvent
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
        "assistant.notification" -> ServerEvent.Notification(
            utterance,
            json["title"]?.asString.orEmpty(),
            json["text"]?.asString.orEmpty(),
        )
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
        "washer.schedule.result" -> ServerEvent.WasherScheduleResult(
            programs = json["programs"]?.asJsonArray?.mapNotNull { item ->
                item.asJsonObject.let { program ->
                    val code = program["code"]?.asString.orEmpty()
                    val name = program["name"]?.asString.orEmpty()
                    if (code.isBlank() || name.isBlank()) null else ServerEvent.WasherProgram(code, name)
                }
            }.orEmpty(),
            schedule = json["schedule"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { schedule ->
                ServerEvent.WasherSchedule(
                    state = schedule["state"]?.asString.orEmpty(),
                    scheduledFor = schedule["scheduled_for"]?.asString.orEmpty(),
                    programCode = schedule["program_code"]?.asString.orEmpty(),
                    programName = schedule["program_name"]?.asString.orEmpty(),
                    failureCode = schedule["failure_code"]?.takeUnless { it.isJsonNull }?.asString,
                )
            },
        )
        "vacuum.config" -> ServerEvent.VacuumConfig(
            json["available"]?.asBoolean ?: false,
            json["controls_available"]?.asBoolean ?: false,
        )
        "vacuum.status.result" -> ServerEvent.VacuumStatusResult(
            json["status"].asJsonObject.let { vacuum -> ServerEvent.VacuumState(
                available = vacuum["available"]?.asBoolean ?: false,
                online = vacuum["online"]?.asBoolean ?: false,
                state = vacuum["state"]?.asString ?: "unknown",
                batteryPercent = vacuum["battery_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                faultCode = vacuum["fault_code"]?.takeUnless { it.isJsonNull }?.asInt,
                cleaningTimeMinutes = vacuum["cleaning_time_minutes"]?.takeUnless { it.isJsonNull }?.asInt,
                cleaningAreaM2 = vacuum["cleaning_area_m2"]?.takeUnless { it.isJsonNull }?.asDouble,
                mopAttached = vacuum["mop_attached"]?.takeUnless { it.isJsonNull }?.asBoolean,
                mainBrushPercent = vacuum["main_brush_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                mainBrushHoursLeft = vacuum["main_brush_hours_left"]?.takeUnless { it.isJsonNull }?.asInt,
                sideBrushPercent = vacuum["side_brush_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                sideBrushHoursLeft = vacuum["side_brush_hours_left"]?.takeUnless { it.isJsonNull }?.asInt,
                filterPercent = vacuum["filter_percent"]?.takeUnless { it.isJsonNull }?.asInt,
                filterHoursLeft = vacuum["filter_hours_left"]?.takeUnless { it.isJsonNull }?.asInt,
                mapAvailable = vacuum["map_available"]?.takeUnless { it.isJsonNull }?.asBoolean,
                multipleMapsEnabled = vacuum["multiple_maps_enabled"]?.takeUnless { it.isJsonNull }?.asBoolean,
                doNotDisturbEnabled = vacuum["do_not_disturb_enabled"]?.takeUnless { it.isJsonNull }?.asBoolean,
                autoEmptyEnabled = vacuum["auto_empty_enabled"]?.takeUnless { it.isJsonNull }?.asBoolean,
                maintenanceRequired = vacuum["maintenance_required"]?.asBoolean ?: false,
                systemMessages = vacuum["system_messages"]?.asJsonArray?.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty(),
                rawDeviceStatus = vacuum["raw_device_status"]?.takeUnless { it.isJsonNull }?.asInt,
                rawOperatingMode = vacuum["raw_operating_mode"]?.takeUnless { it.isJsonNull }?.asInt,
                rawTaskStatus = vacuum["raw_task_status"]?.takeUnless { it.isJsonNull }?.asInt,
                rawRelocationStatus = vacuum["raw_relocation_status"]?.takeUnless { it.isJsonNull }?.asInt,
                updatedAt = vacuum["updated_at"]?.takeUnless { it.isJsonNull }?.asString,
            ) },
        )
        "vacuum.maps.result" -> json["maps"].asJsonObject.let { collection ->
            ServerEvent.VacuumMapsResult(ServerEvent.VacuumMaps(
                available = collection["available"]?.asBoolean ?: false,
                offlineReady = collection["offline_ready"]?.asBoolean ?: false,
                updatedAt = collection["updated_at"]?.takeUnless { it.isJsonNull }?.asString,
                maps = collection["maps"]?.asJsonArray?.take(3)?.mapNotNull { mapElement ->
                    mapElement.takeIf { it.isJsonObject }?.asJsonObject?.let { map ->
                        ServerEvent.VacuumMap(
                            name = map["name"]?.asString ?: "Karta",
                            cellSizeMm = map["cell_size_mm"]?.asInt ?: 50,
                            width = map["width"]?.asInt ?: 0,
                            height = map["height"]?.asInt ?: 0,
                            runs = map["runs"]?.asJsonArray?.take(50_000)?.mapNotNull { runElement ->
                                runElement.takeIf { it.isJsonObject }?.asJsonObject?.let { run ->
                                    ServerEvent.VacuumMapRun(
                                        y = run["y"]?.asInt ?: return@let null,
                                        x = run["x"]?.asInt ?: return@let null,
                                        length = run["length"]?.asInt ?: return@let null,
                                        roomId = run["room_id"]?.takeUnless { it.isJsonNull }?.asInt,
                                        wall = run["kind"]?.asString == "wall",
                                    )
                                }
                            }.orEmpty(),
                            rooms = map["rooms"]?.asJsonArray?.take(63)?.mapNotNull { roomElement ->
                                roomElement.takeIf { it.isJsonObject }?.asJsonObject?.let { room ->
                                    ServerEvent.VacuumMapRoom(
                                        id = room["id"]?.asInt ?: return@let null,
                                        name = room["name"]?.asString ?: "Rum",
                                    )
                                }
                            }.orEmpty(),
                            robot = map["robot"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { point ->
                                ServerEvent.VacuumMapPoint(point["x"]?.asDouble ?: 0.0, point["y"]?.asDouble ?: 0.0, point["angle"]?.takeUnless { it.isJsonNull }?.asInt)
                            },
                            charger = map["charger"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { point ->
                                ServerEvent.VacuumMapPoint(point["x"]?.asDouble ?: 0.0, point["y"]?.asDouble ?: 0.0, point["angle"]?.takeUnless { it.isJsonNull }?.asInt)
                            },
                        )
                    }
                }.orEmpty(),
            ))
        }
        "vacuum.command.result" -> ServerEvent.VacuumCommandResult(
            command = json["command"]?.asString.orEmpty(),
            vacuum = parseServerEvent(
                JsonObject().apply {
                    addProperty("type", "vacuum.status.result")
                    add("status", json["status"])
                }.toString()
            ).let { (it as ServerEvent.VacuumStatusResult).vacuum },
            message = json["message"]?.asString ?: "Robotdammsugaren accepterade kommandot.",
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
