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
}
