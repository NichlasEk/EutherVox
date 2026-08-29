package se.euther.euthervox.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun sessionStartAdvertisesExactPcmFormat() {
        val json = JsonParser.parseString(sessionStart("pixel", character = "christian-grosshandlare", voiceId = "moss-christian", llmModel = "qwen3.8:27b")).asJsonObject
        val audio = json["input_audio"].asJsonObject
        assertEquals(1, json["protocol_version"].asInt)
        assertEquals("pixel", json["node_name"].asString)
        assertEquals("christian-grosshandlare", json["character"].asString)
        assertEquals("moss-christian", json["voice_id"].asString)
        assertEquals("qwen3.8:27b", json["llm_model"].asString)
        assertEquals("pcm_s16le", audio["codec"].asString)
        assertEquals(16_000, audio["sample_rate"].asInt)
        assertEquals(20, audio["frame_ms"].asInt)
    }

    @Test fun parsesAvailableLanguageModels() {
        val event = parseServerEvent(
            """{"type":"session.ready","session_id":"s1","llm_model":"qwen3.8:27b","available_llm_models":["qwen3:4b-instruct","qwen3.8:27b"]}"""
        ) as ServerEvent.Ready

        assertEquals("qwen3.8:27b", event.llmModel)
        assertEquals(listOf("qwen3:4b-instruct", "qwen3.8:27b"), event.availableLlmModels)
    }

    @Test fun parsesTtsStart() {
        val event = parseServerEvent("""{"type":"tts.start","utterance_id":"u1","audio":{"codec":"pcm_s16le","sample_rate":24000,"channels":1}}""")
        assertTrue(event is ServerEvent.TtsStart)
        assertEquals(24_000, (event as ServerEvent.TtsStart).sampleRate)
    }

    @Test fun parsesAssistantNotification() {
        val event = parseServerEvent(
            """{"type":"assistant.notification","utterance_id":"wash-1","title":"Tvätten är klar","text":"Dags att hänga tvätten."}"""
        )
        assertTrue(event is ServerEvent.Notification)
        event as ServerEvent.Notification
        assertEquals("wash-1", event.utteranceId)
        assertEquals("Tvätten är klar", event.title)
        assertEquals("Dags att hänga tvätten.", event.text)
    }

    @Test fun parsesRecoverableError() {
        val event = parseServerEvent("""{"type":"error","code":"STT_FAILED","message":"no speech","recoverable":true}""")
        assertEquals(ServerEvent.Error("STT_FAILED", "no speech", true), event)
    }

    @Test fun parsesAllowlistedDeviceAction() {
        val event = parseServerEvent(
            """{"type":"action.request","action_id":"a1","utterance_id":"u1","name":"media.play","target":{"kind":"node","node_name":"pixel"},"arguments":{"provider":"youtube_music","query":"något mörkt och lugnt"},"requires_confirmation":false}"""
        )

        assertEquals(
            ServerEvent.ActionRequest("a1", "u1", "media.play", "pixel", "youtube_music", "något mörkt och lugnt", "", false),
            event,
        )
    }

    @Test fun serializesActionResult() {
        val json = JsonParser.parseString(actionResult("a1", "u1", "completed", "klart")).asJsonObject

        assertEquals("action.result", json["type"].asString)
        assertEquals("a1", json["action_id"].asString)
        assertEquals("completed", json["status"].asString)
    }

    @Test fun parsesCastRoomFromPlaylistAction() {
        val event = parseServerEvent(
            """{"type":"action.request","action_id":"a2","utterance_id":"u2","name":"playlist.create","target":{"kind":"node","node_name":"pixel"},"arguments":{"provider":"euthervox","query":"mörk synth","output_room":"köket"},"requires_confirmation":true}"""
        )

        assertEquals("köket", (event as ServerEvent.ActionRequest).outputRoom)
    }

    @Test fun serializesActionConfirmation() {
        val json = JsonParser.parseString(actionConfirm("a1")).asJsonObject
        assertEquals("action.confirm", json["type"].asString)
        assertEquals("a1", json["action_id"].asString)
    }

    @Test fun serializesAndParsesLightConfigurationWithoutLosingSwedishNames() {
        val saved = JsonParser.parseString(
            lightConfigUpsert("Fönstret", "dotterns rum", "192.168.1.20", "AABBCCDDEE20", "AK001-ZJ200")
        ).asJsonObject
        assertEquals("light.config.upsert", saved["type"].asString)
        assertEquals("Fönstret", saved["name"].asString)

        val event = parseServerEvent(
            """{"type":"lights.config","lights":[{"id":"l1","name":"Fönstret","room":"dotterns rum","host":"192.168.1.20","mac":"AABBCCDDEE20","model":"AK001-ZJ200"}]}"""
        ) as ServerEvent.LightsConfig
        assertEquals("dotterns rum", event.lights.single().room)
        assertEquals("AABBCCDDEE20", event.lights.single().mac)
    }

    @Test fun serializesAndParsesAllowlistedTvMessages() {
        val command = JsonParser.parseString(tvCommand("Stora TV:n", "input_hdmi2")).asJsonObject
        assertEquals("tv.command", command["type"].asString)
        assertEquals("input_hdmi2", command["command"].asString)

        val event = parseServerEvent(
            """{"type":"tvs.config","tvs":[{"id":"t1","name":"Stora TV:n","room":"vardagsrummet","host":"192.168.32.40","port":7142,"model":"NEC display"}]}"""
        ) as ServerEvent.TvsConfig
        assertEquals("vardagsrummet", event.televisions.single().room)
        assertEquals(7142, event.televisions.single().port)
    }

    @Test fun serializesPumpStatusAndParsesNormalizedState() {
        val request = JsonParser.parseString(pumpStatus("Värmepumpen")).asJsonObject
        assertEquals("pump.status", request["type"].asString)
        assertEquals("Värmepumpen", request["target"].asString)

        val event = parseServerEvent(
            """{"type":"pump.status.result","pump":{"id":"pump-1","name":"Värmepumpen","room":"vardagsrummet","online":true,"power":true,"mode":"auto","target_temperature":24,"room_temperature":23,"outdoor_temperature":20,"fan_mode":"auto","power_selection_percent":100,"updated_at":"2026-08-23T12:34:56+00:00","read_only":true}}"""
        ) as ServerEvent.PumpStatusResult
        assertEquals(23.0, event.pump.roomTemperature!!, 0.0)
        assertEquals(24.0, event.pump.targetTemperature!!, 0.0)
        assertTrue(event.pump.readOnly)
    }

    @Test fun serializesCombinedPumpCommandAndParsesConfirmedState() {
        val command = JsonParser.parseString(
            pumpCommand("Värmepumpen", power = true, mode = "heat", targetTemperature = 21, fanMode = "auto")
        ).asJsonObject
        assertEquals("pump.command", command["type"].asString)
        assertEquals(21, command["target_temperature"].asInt)

        val event = parseServerEvent(
            """{"type":"pump.command.result","message":"Pumpen bekräftade ändringen.","pump":{"id":"pump-1","name":"Värmepumpen","room":"vardagsrummet","online":true,"power":true,"mode":"heat","target_temperature":21,"room_temperature":23,"outdoor_temperature":20,"fan_mode":"auto","power_selection_percent":100,"updated_at":"2026-08-23T12:34:56+00:00","read_only":false}}"""
        ) as ServerEvent.PumpCommandResult
        assertEquals("heat", event.pump.mode)
        assertEquals(false, event.pump.readOnly)
    }

    @Test fun serializesWasherRefreshAndParsesSanitizedReport() {
        val request = JsonParser.parseString(washerStatus()).asJsonObject
        assertEquals("washer.status", request["type"].asString)

        val event = parseServerEvent(
            """{"type":"washer.status.result","status":{"available":true,"online":true,"state":"idle","phase":null,"progress_percent":null,"remaining_seconds":null,"program":"Eco 40–60","water_temperature_c":40,"spin_rpm":1400,"rinse_cycles":2,"instantaneous_power_w":null,"cumulative_energy_kwh":827.1,"updated_at":"2026-08-24T10:00:00Z"},"statistics":{"samples_24h":10,"availability_percent_24h":100.0,"cycles_started_7d":1,"cycles_completed_7d":1,"running_minutes_7d":45,"energy_used_kwh_7d":0.4,"last_completed_at":"2026-08-24T09:00:00Z"}}"""
        ) as ServerEvent.WasherStatusResult
        assertEquals("Eco 40–60", event.washer.program)
        assertEquals(827.1, event.washer.cumulativeEnergyKwh!!, 0.0)
        assertEquals(1, event.statistics.cyclesCompleted7d)
    }

    @Test fun serializesConfirmedWasherCommandAndParsesApplianceReadback() {
        val request = JsonParser.parseString(washerCommand("start", confirmed = true)).asJsonObject
        assertEquals("washer.command", request["type"].asString)
        assertEquals("start", request["command"].asString)
        assertTrue(request["confirmed"].asBoolean)

        val event = parseServerEvent(
            """{"type":"washer.command.result","command":"start","message":"Tvättmaskinen bekräftade att den startade.","status":{"available":true,"online":true,"state":"running","phase":"wash","progress_percent":1,"remaining_seconds":3600,"program":"Eco 40–60","water_temperature_c":40,"spin_rpm":1400,"rinse_cycles":2,"remote_control_enabled":true,"instantaneous_power_w":400,"cumulative_energy_kwh":827.2,"updated_at":"2026-08-24T10:00:00Z"}}"""
        ) as ServerEvent.WasherCommandResult
        assertEquals("running", event.washer.state)
        assertEquals(true, event.washer.remoteControlEnabled)
    }

    @Test fun serializesAndParsesWasherSchedule() {
        val request = JsonParser.parseString(
            washerScheduleCreate("2030-01-02T08:00:00+01:00", "1C", "40")
        ).asJsonObject
        assertEquals("washer.schedule.create", request["type"].asString)
        assertEquals("1C", request["program_code"].asString)
        assertEquals("40", request["water_temperature"].asString)
        assertTrue(request["confirmed"].asBoolean)

        val event = parseServerEvent(
            """{"type":"washer.schedule.result","programs":[{"code":"1C","name":"Eco 40–60"}],"water_temperatures":["Cold","20","40","60"],"schedule":{"state":"scheduled","scheduled_for":"2030-01-02T08:00:00+01:00","program_code":"1C","program_name":"Eco 40–60","water_temperature":"40","failure_code":null}}"""
        ) as ServerEvent.WasherScheduleResult
        assertEquals("Eco 40–60", event.programs.single().name)
        assertEquals("scheduled", event.schedule?.state)
        assertEquals(listOf("Cold", "20", "40", "60"), event.waterTemperatures)
        assertEquals("40", event.schedule?.waterTemperature)
    }

    @Test fun serializesConfirmedFastMappingAndParsesVacuumWarnings() {
        val request = JsonParser.parseString(vacuumCommand("start-fast-mapping", confirmed = true)).asJsonObject
        assertEquals("vacuum.command", request["type"].asString)
        assertTrue(request["confirmed"].asBoolean)

        val event = parseServerEvent(
            """{"type":"vacuum.command.result","command":"start-fast-mapping","message":"Robotdammsugaren startade en ny snabbkarta.","status":{"available":true,"online":true,"state":"idle","battery_percent":97,"fault_code":0,"cleaning_time_minutes":0,"cleaning_area_m2":0.0,"mop_attached":false,"main_brush_percent":36,"main_brush_hours_left":108,"side_brush_percent":4,"side_brush_hours_left":8,"filter_percent":0,"filter_hours_left":0,"map_available":true,"multiple_maps_enabled":true,"do_not_disturb_enabled":true,"auto_empty_enabled":true,"maintenance_required":true,"system_messages":["Filtret behöver rengöras eller bytas."],"raw_device_status":2,"raw_operating_mode":14,"raw_task_status":0,"raw_relocation_status":0,"updated_at":"2026-08-24T18:01:00Z"}}"""
        ) as ServerEvent.VacuumCommandResult
        assertEquals("start-fast-mapping", event.command)
        assertEquals(97, event.vacuum.batteryPercent)
        assertEquals(0, event.vacuum.filterPercent)
        assertEquals(false, event.vacuum.mopAttached)
        assertEquals(true, event.vacuum.multipleMapsEnabled)
        assertEquals(1, event.vacuum.systemMessages.size)
    }

    @Test fun serializesAndParsesSanitizedVacuumMap() {
        assertEquals("vacuum.maps", JsonParser.parseString(vacuumMaps()).asJsonObject["type"].asString)
        val event = parseServerEvent(
            """{"type":"vacuum.maps.result","maps":{"available":true,"offline_ready":true,"updated_at":"2026-08-24T20:00:00Z","maps":[{"index":1,"selected":true,"name":"Hemma","width":20,"height":10,"cell_size_mm":50,"rotation":0,"runs":[{"x":1,"y":2,"length":4,"kind":"floor","room_id":1},{"x":0,"y":0,"length":2,"kind":"wall","room_id":null}],"rooms":[{"id":1,"name":"Kök"}],"robot":{"x":3.5,"y":4.5,"angle":90},"charger":null}]}}"""
        ) as ServerEvent.VacuumMapsResult
        assertTrue(event.vacuumMaps.offlineReady)
        assertEquals("Hemma", event.vacuumMaps.maps.single().name)
        assertEquals(1, event.vacuumMaps.maps.single().runs.first().roomId)
        assertTrue(event.vacuumMaps.maps.single().runs.last().wall)
        assertEquals(3.5, event.vacuumMaps.maps.single().robot!!.x, 0.0)
    }
}
