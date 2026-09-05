from __future__ import annotations

import asyncio
from dataclasses import replace
import json
from pathlib import Path
import sys
import types

from gateway.adapters import (
    FasterWhisperSpeechToTextEngine,
    MockSpeechToTextEngine,
    MockTextGenerationEngine,
    MockTextToSpeechEngine,
    TomlCharacterProvider,
    _cached_whisper_snapshot,
    render_music_acknowledgement,
    render_music_control_acknowledgement,
)
from gateway.actions import ActionPlanner, DeviceAction
from gateway.config import load_config
from gateway.playlists import PlaylistTrack, TomlPlaylistStore
from gateway.session import Phase, ProtocolError, SentenceChunker, VoiceSession
from gateway.youtube import CreatedPlaylist, PlaylistPreview
from gateway.wikipedia import WikipediaArticle
from gateway.lighting import MagicHomeLightService
from gateway.eutherpump import ConfiguredPump


ROOT = Path(__file__).parents[2]


def make_session():
    sent: list[dict | bytes] = []

    async def send_json(message: dict):
        sent.append(message)

    async def send_binary(data: bytes):
        sent.append(data)

    config = load_config(ROOT / "config.example.toml")
    session = VoiceSession(
        config, MockSpeechToTextEngine(), MockTextGenerationEngine(), MockTextToSpeechEngine(),
        TomlCharacterProvider(config.profile_dir), send_json, send_binary,
    )
    return session, sent


def start_message():
    return json.dumps({
        "type": "session.start", "protocol_version": 1, "character": "skinnskattaren",
        "input_audio": {"codec": "pcm_s16le", "sample_rate": 16000, "channels": 1, "frame_ms": 20},
    })


def test_complete_mock_pipeline_streams_control_and_binary_audio():
    async def scenario():
        session, sent = make_session()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "u-1"}))
        await session.response_task
        types = [item["type"] for item in sent if isinstance(item, dict)]
        assert types == ["session.ready", "stt.partial", "stt.final", "assistant.text.delta", "assistant.text.delta", "assistant.text.delta", "assistant.text.final", "tts.start", "tts.end"]
        tts_start = next(item for item in sent if isinstance(item, dict) and item["type"] == "tts.start")
        assert tts_start["audio"]["sample_rate"] == 24000
        assert any(isinstance(item, bytes) and len(item) == 960 for item in sent)
        assert session.phase is Phase.READY
    asyncio.run(scenario())


def test_authenticated_idle_session_accepts_unsolicited_washer_speech():
    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.washer_notifications = types.SimpleNamespace(
            voice_id="piper-nst", jingle_path=None, enabled=True,
            subscribe=lambda _node, _callback: None,
            unsubscribe=lambda _node, _callback: None,
        )
        await session.handle_text(start_message())

        delivered = await session._deliver_washer_notification(
            "wash-done-1", "Tvätten är klar!"
        )

        assert delivered is True
        messages = [item for item in sent if isinstance(item, dict)]
        notification = next(item for item in messages if item["type"] == "assistant.notification")
        assert notification["utterance_id"] == "wash-done-1"
        assert [item["type"] for item in messages[-3:]] == [
            "assistant.notification", "tts.start", "tts.end",
        ]
        assert any(isinstance(item, bytes) for item in sent)
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_session_selects_only_an_allowlisted_ollama_model():
    class SelectableLlm:
        def __init__(self, model: str):
            self.model = model

        def with_model(self, model: str):
            return SelectableLlm(model)

    class FixedPlanner:
        def __init__(self, model: str):
            self.model = model

    async def scenario():
        session, sent = make_session()
        session.config = replace(
            session.config,
            llm_provider="ollama",
            llm_settings={"model": "qwen3:4b-instruct", "models": ["qwen3.8:27b"]},
        )
        session.llm = SelectableLlm("qwen3:4b-instruct")
        session.tool_planner = FixedPlanner("qwen3:4b-instruct")
        message = json.loads(start_message())
        message["llm_model"] = "qwen3.8:27b"

        await session.handle_text(json.dumps(message))

        assert session.llm.model == "qwen3.8:27b"
        assert session.tool_planner.model == "qwen3:4b-instruct"
        assert sent[0]["llm_model"] == "qwen3.8:27b"
        assert sent[0]["available_llm_models"] == ["qwen3:4b-instruct", "qwen3.8:27b"]

        rejected, _ = make_session()
        rejected.config = session.config
        rejected.llm = SelectableLlm("qwen3:4b-instruct")
        message["llm_model"] = "not-installed"
        try:
            await rejected.handle_text(json.dumps(message))
        except ProtocolError as error:
            assert error.code == "LLM_MODEL_NOT_ALLOWED"
        else:
            raise AssertionError("an unlisted model was accepted")

    asyncio.run(scenario())


def test_pipeline_timeout_has_a_human_readable_error():
    class TimeoutLlm:
        async def generate(self, _transcript, _character):
            raise TimeoutError
            yield  # pragma: no cover

    async def scenario():
        session, sent = make_session()
        session.llm = TimeoutLlm()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-timeout"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "u-timeout"}))
        await session.response_task

        error = next(item for item in sent if isinstance(item, dict) and item["type"] == "error")
        assert error["code"] == "PIPELINE_FAILED"
        assert error["message"] == "Modellen svarade inte i tid"

    asyncio.run(scenario())


def test_tool_clarification_is_spoken_without_running_the_character_llm():
    class ClarifyingPlanner:
        async def plan(self, _transcript: str, node_name: str):
            return DeviceAction(
                action_id="clarify-1",
                name="assistant.clarify",
                target_node=node_name,
                arguments={"domain": "lights"},
                acknowledgement="Vilket rum menar du?",
            )

    class FailingLlm:
        async def generate(self, _transcript: str, _character):
            raise AssertionError("clarifications must not reach the character LLM")
            yield ""

    async def scenario():
        session, sent = make_session()
        session.tool_planner = ClarifyingPlanner()
        session.llm = FailingLlm()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "clarify-u1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "clarify-u1"}))
        await session.response_task

        final = next(item for item in sent if isinstance(item, dict) and item["type"] == "assistant.text.final")
        assert final["text"] == "Vilket rum menar du?"
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_sherlock_requests_automatic_swedish_or_english_stt_detection():
    languages: list[str | None] = []

    class LanguageAwareStt:
        async def transcribe(
            self, pcm: bytes, sample_rate: int, language: str | None = None
        ) -> str:
            languages.append(language)
            return "Berätta vad du ser."

    async def scenario():
        session, _sent = make_session()
        session.stt = LanguageAwareStt()
        await session.handle_text(json.dumps({
            "type": "session.start",
            "protocol_version": 1,
            "character": "sherlock-holmes",
            "voice_id": "matcha-sherlock",
            "input_audio": {
                "codec": "pcm_s16le",
                "sample_rate": 16000,
                "channels": 1,
                "frame_ms": 20,
            },
        }))
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "sherlock-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "sherlock-1"}))
        await session.response_task

    asyncio.run(scenario())
    assert languages == ["auto"]


def test_sentence_chunker_releases_complete_sentences_and_keeps_remainder():
    chunker = SentenceChunker()

    assert chunker.push("Första meningen kommer ") == []
    assert chunker.push("redan nu. Nästa är") == ["Första meningen kommer redan nu."]
    assert chunker.flush() == "Nästa är"


def test_tts_starts_after_first_sentence_before_model_finishes_response():
    first_sentence_synthesized = asyncio.Event()

    class StreamingLlm:
        async def generate(self, transcript: str, character):
            yield "Första meningen är färdig. "
            await asyncio.wait_for(first_sentence_synthesized.wait(), timeout=1)
            yield "Den andra kommer senare."

    class CoordinatedTts:
        sample_rate = 24_000

        async def synthesize(self, text: str, character, sample_rate: int):
            if text.startswith("Första"):
                first_sentence_synthesized.set()
            yield bytes(960)

    async def scenario():
        session, sent = make_session()
        session.llm = StreamingLlm()
        session.tts = CoordinatedTts()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "stream-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "stream-1"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        tts_start_index = next(index for index, item in enumerate(controls) if item["type"] == "tts.start")
        second_delta_index = next(
            index for index, item in enumerate(controls)
            if item["type"] == "assistant.text.delta" and item["text"].startswith("Den andra")
        )
        assert tts_start_index < second_delta_index

    asyncio.run(scenario())


def test_recent_turns_are_passed_to_history_aware_model():
    class HistoryLlm:
        histories = []

        async def generate_with_history(self, transcript: str, character, history):
            self.histories.append(history)
            yield "Jag minns den här repliken."

    async def scenario():
        session, _sent = make_session()
        llm = HistoryLlm()
        session.llm = llm
        await session.handle_text(start_message())
        for index in (1, 2):
            await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": f"memory-{index}"}))
            await session.handle_binary(bytes(640))
            await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": f"memory-{index}"}))
            await session.response_task

        assert llm.histories[0] == ()
        assert llm.histories[1] == (("Var ligger min lödkolv?", "Jag minns den här repliken."),)

    asyncio.run(scenario())


def test_real_beta_model_paths_are_resolved_from_config_location():
    config = load_config(ROOT / "config.real-beta.example.toml")
    assert Path(config.stt_settings["download_root"]).is_absolute()
    assert Path(config.tts_settings["voices"]["piper-nst"]["model_path"]).is_absolute()
    assert Path(config.tts_settings["voices"]["piper-lisa"]["model_path"]).is_absolute()
    assert config.stt_settings["language"] == "sv"


def test_skinnskattaren_music_acknowledgement_comes_from_character_profile():
    config = load_config(ROOT / "config.example.toml")
    character = TomlCharacterProvider(config.profile_dir).get("skinnskattaren")

    acknowledgement = render_music_acknowledgement(character, "November Rain", "köket")

    assert "November Rain" in acknowledgement
    assert "köket" in acknowledgement


def test_skinnskattaren_music_control_acknowledgement_comes_from_character_profile():
    config = load_config(ROOT / "config.example.toml")
    character = TomlCharacterProvider(config.profile_dir).get("skinnskattaren")

    acknowledgement = render_music_control_acknowledgement(character, "resume", "köket")

    assert acknowledgement == "Då låter jag järnet sjunga vidare i köket."


def test_cached_whisper_snapshot_avoids_remote_model_lookup(tmp_path: Path):
    repository = tmp_path / "models--Systran--faster-whisper-small"
    (repository / "refs").mkdir(parents=True)
    (repository / "refs/main").write_text("revision-1", encoding="utf-8")
    snapshot = repository / "snapshots/revision-1"
    snapshot.mkdir(parents=True)
    (snapshot / "model.bin").touch()

    assert _cached_whisper_snapshot("small", str(tmp_path)) == str(snapshot)


def test_faster_whisper_hotwords_merge_configured_names_without_duplicates():
    engine = FasterWhisperSpeechToTextEngine.__new__(FasterWhisperSpeechToTextEngine)
    engine.hotwords = "befintligt ord, Estrids rum"

    count = engine.add_hotwords(["Estrids rum", "Bokhylla", "  YouTube   Music  ", ""])

    assert count == 4
    assert engine.hotwords == "befintligt ord, Estrids rum, Bokhylla, YouTube Music"


def test_faster_whisper_falls_back_to_cpu_when_cuda_driver_is_unavailable(monkeypatch):
    attempts: list[tuple[str, str, str]] = []

    class FakeWhisperModel:
        def __init__(self, model: str, *, device: str, compute_type: str, **_settings):
            attempts.append((model, device, compute_type))
            if device == "cuda":
                raise RuntimeError("driver/library version mismatch")

    monkeypatch.setattr("gateway.adapters._preload_cuda_runtime", lambda: None)
    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=FakeWhisperModel))

    engine = FasterWhisperSpeechToTextEngine(
        model="small", device="cuda", compute_type="float16",
        fallback_model="base", fallback_compute_type="int8",
    )

    assert attempts == [("small", "cuda", "float16"), ("base", "cpu", "int8")]
    assert engine.runtime_device == "cpu"


def test_binary_audio_without_active_utterance_is_rejected():
    async def scenario():
        session, _ = make_session()
        await session.handle_text(start_message())
        try:
            await session.handle_binary(bytes(640))
            assert False, "ProtocolError expected"
        except ProtocolError as error:
            assert error.code == "UNEXPECTED_AUDIO"
    asyncio.run(scenario())


def test_authenticated_session_exposes_and_reads_configured_pump():
    class FakePump:
        enabled = True

        def list_public(self):
            return [{"id": "pump-1", "name": "Värmepumpen", "room": "vardagsrummet"}]

        def resolve(self, selector: str):
            assert selector == "Värmepumpen"
            return ConfiguredPump("pump-1", "Värmepumpen", "vardagsrummet")

        async def state(self, selector: str):
            assert selector == "Värmepumpen"
            return {
                "pump_id": "pump-1", "online": True, "power": True, "mode": "auto",
                "target_temperature": 24, "room_temperature": 23, "read_only": True,
            }

        async def control(self, selector: str, changes: dict):
            assert selector == "Värmepumpen"
            assert changes == {"mode": "heat", "target_temperature": 21}
            return {
                "pump_id": "pump-1", "online": True, "power": True, "mode": "heat",
                "target_temperature": 21, "room_temperature": 23, "read_only": False,
            }

    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.eutherpump = FakePump()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "pump.status", "target": "Värmepumpen"}))
        await session.handle_text(json.dumps({
            "type": "pump.command", "target": "Värmepumpen",
            "mode": "heat", "target_temperature": 21,
        }))

        config = next(item for item in sent if item.get("type") == "pumps.config")
        result = next(item for item in sent if item.get("type") == "pump.status.result")
        command = next(item for item in sent if item.get("type") == "pump.command.result")
        assert config["pumps"][0]["room"] == "vardagsrummet"
        assert result["pump"]["room_temperature"] == 23
        assert result["pump"]["name"] == "Värmepumpen"
        assert command["pump"]["target_temperature"] == 21

    asyncio.run(scenario())


def test_authenticated_session_exposes_read_only_washer_report():
    class FakeWasher:
        enabled = True
        control_enabled = False

        async def report(self):
            return {
                "status": {"online": True, "state": "idle", "program": "Eco 40–60"},
                "statistics": {"cycles_completed_7d": 2, "running_minutes_7d": 80},
            }

    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.eutherwash = FakeWasher()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "washer.status"}))
        assert next(item for item in sent if item.get("type") == "washer.config")["available"] is True
        result = next(item for item in sent if item.get("type") == "washer.status.result")
        assert result["status"]["program"] == "Eco 40–60"
        assert result["statistics"]["cycles_completed_7d"] == 2

    asyncio.run(scenario())


def test_authenticated_session_controls_washer_with_start_confirmation():
    class FakeWasher:
        enabled = True
        control_enabled = True

        async def report(self):
            return {"status": {"online": True, "state": "idle"}, "statistics": {}}

        async def command(self, command, *, confirmed=False, program_code=None, water_temperature=None):
            assert command == "start"
            assert confirmed is True
            assert program_code == "25"
            assert water_temperature == "60"
            return {"online": True, "state": "running", "remote_control_enabled": True}

    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.eutherwash = FakeWasher()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({
            "type": "washer.command", "command": "start", "confirmed": True,
            "program_code": "25", "water_temperature": "60",
        }))
        config = next(item for item in sent if item.get("type") == "washer.config")
        result = next(item for item in sent if item.get("type") == "washer.command.result")
        assert config["controls_available"] is True
        assert result["status"]["state"] == "running"

    asyncio.run(scenario())


def test_authenticated_session_reads_local_vacuum_maps():
    class FakeWash:
        enabled = True
        control_enabled = True

        async def vacuum_maps(self):
            return {"available": True, "offline_ready": True, "maps": [{"name": "Hemma", "runs": []}]}

    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.eutherwash = FakeWash()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "vacuum.maps"}))
        result = next(item for item in sent if item.get("type") == "vacuum.maps.result")
        assert result["maps"]["offline_ready"] is True
        assert result["maps"]["maps"][0]["name"] == "Hemma"

    asyncio.run(scenario())


def test_authenticated_session_saves_named_light_to_toml_and_returns_config(tmp_path: Path):
    async def scenario():
        session, sent = make_session()
        session.authenticated_user = "nichlas"
        session.lights = MagicHomeLightService(
            {"enabled": True, "config_file": str(tmp_path / "lights.toml")},
            ROOT,
        )
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({
            "type": "light.config.upsert",
            "name": "Fönstret",
            "room": "köket",
            "host": "192.168.1.20",
            "mac": "AABBCCDDEE20",
            "model": "AK001-ZJ200",
        }))

        configs = [item for item in sent if isinstance(item, dict) and item["type"] == "lights.config"]
        assert configs[-1]["lights"][0]["name"] == "Fönstret"
        assert configs[-1]["lights"][0]["host"] == "192.168.1.20"
        assert "Fönstret" in (tmp_path / "lights.toml").read_text(encoding="utf-8")

    asyncio.run(scenario())


def test_anonymous_session_cannot_save_lan_light_address(tmp_path: Path):
    async def scenario():
        session, sent = make_session()
        session.lights = MagicHomeLightService(
            {"enabled": True, "config_file": str(tmp_path / "lights.toml")}, ROOT
        )
        await session.handle_text(start_message())
        assert not any(
            isinstance(item, dict) and item.get("type") == "lights.config"
            for item in sent
        )
        try:
            await session.handle_text(json.dumps({
                "type": "light.config.upsert", "name": "X", "room": "Y",
                "host": "192.168.1.20", "mac": "AABBCCDDEE20", "model": "AK001-ZJ200",
            }))
            assert False
        except ProtocolError as error:
            assert error.code == "AUTH_REQUIRED"

    asyncio.run(scenario())


def test_second_utterance_is_rejected_while_recording():
    async def scenario():
        session, _ = make_session()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-1"}))
        try:
            await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-2"}))
            assert False, "ProtocolError expected"
        except ProtocolError as error:
            assert error.code == "SESSION_BUSY"
    asyncio.run(scenario())


def test_action_planner_extracts_youtube_music_query_and_target():
    action = ActionPlanner().plan("Spela upp något mörkt och lugnt på YouTube Music.", "pixel")

    assert action is not None
    assert action.name == "media.play"
    assert action.target_node == "pixel"
    assert action.arguments == {"provider": "youtube_music", "query": "något mörkt och lugnt"}
    assert action.requires_confirmation is False


def test_action_planner_extracts_kitchen_output_from_music_and_playlist_requests():
    music = ActionPlanner().plan("Spela Ghost i köket", "pixel")
    playlist = ActionPlanner().plan("Skapa en spellista med mörk synth på Kök 2", "pixel")

    assert music is not None
    assert music.arguments == {"provider": "youtube_music", "query": "Ghost", "output_room": "köket"}
    assert playlist is not None
    assert playlist.arguments == {"provider": "euthervox", "query": "mörk synth", "output_room": "köket"}


def test_action_planner_accepts_natural_media_control_commands():
    examples = {
        "Pausa musiken i köket": ("media.pause", {"output_room": "köket"}),
        "Sätt på paus": ("media.pause", {}),
        "Kan du pausa den?": ("media.pause", {}),
        "Fortsätt": ("media.resume", {}),
        "Kan du fortsätta spela musiken i Kök 2?": ("media.resume", {"output_room": "köket"}),
        "Återuppta uppspelningen": ("media.resume", {}),
        "Spela vidare i köket": ("media.resume", {"output_room": "köket"}),
        "Stoppa musiken": ("media.stop", {}),
        "Kan du stoppa upp spelningen i köket?": ("media.stop", {"output_room": "köket"}),
        "Kan du stänga av musiken i köket?": ("media.stop", {"output_room": "köket"}),
        "Sluta spela nu": ("media.stop", {}),
    }

    for transcript, (expected_name, expected_arguments) in examples.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.name == expected_name
        assert action.arguments == expected_arguments


def test_action_planner_does_not_mistake_conversation_about_controls_for_commands():
    assert ActionPlanner().plan("Hur pausar man musiken?", "pixel") is None
    assert ActionPlanner().plan("Vad betyder återuppta?", "pixel") is None
    assert ActionPlanner().plan("Jag pausade musiken igår", "pixel") is None


def test_action_planner_handles_wikipedia_requests_and_observed_stt_spelling():
    examples = {
        "Kan du kolla akvariefisker på vilket pedia?": ("akvariefisker", "summary"),
        "Sammanfatta järnmalm från Wikipedia": ("järnmalm", "summary"),
        "Sök Wikipedia efter Ada Lovelace": ("Ada Lovelace", "summary"),
        "Läs inledningen av Wikipedia-artikeln om Skinnskatteberg": ("Skinnskatteberg", "introduction"),
    }

    for transcript, (query, mode) in examples.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.name == "knowledge.wikipedia"
        assert action.arguments == {"query": query, "mode": mode}


def test_action_planner_requests_wikipedia_topic_instead_of_inventing_one():
    action = ActionPlanner().plan("Kan du köra på Wikipedia?", "pixel")

    assert action is not None
    assert action.name == "knowledge.wikipedia"
    assert action.arguments == {"query": "", "mode": "summary"}


def test_action_planner_accepts_natural_and_observed_stt_music_requests():
    examples = {
        "Kan du spela cyberpunk i köket?": "cyberpunk",
        "Skulle du kunna spela Ghost på Kök 2?": "Ghost",
        "Jag vill spela mörk synth i köket": "mörk synth",
        "Jag spelar Cyberpunk i köket.": "Cyberpunk",
        "Sätt på svensk punk i köket": "svensk punk",
        "Dra igång ambient i köket": "ambient",
        "Jag vill höra Smells Like Teen Spirit i köket": "Smells Like Teen Spirit",
        "Jag skulle vilja ha November Rain i köket": "November Rain",
        "Kan jag få höra låten November Rain i köket?": "November Rain",
        "Ge mig Smells Like Teen Spirit i köket": "Smells Like Teen Spirit",
        "Spela cyberpunk i köken": "cyberpunk",
        "Spela cyberpunk i kök...": "cyberpunk",
        "Spela cyberpunk i köketack": "cyberpunk",
        "Spela cyberpunk i köketag": "cyberpunk",
        "Spela ambient på högtalaren": "ambient",
        "Spela techno på Nest i köket": "techno",
        "Prova igen att spela cyberpunk i köket": "cyberpunk",
        "Cyberpunk i köket, tack!": "Cyberpunk",
    }

    for transcript, expected_query in examples.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.name == "media.play"
        assert action.arguments["query"] == expected_query
        assert action.arguments["output_room"] == "köket"


def test_action_planner_keeps_obscure_music_as_free_search_text():
    requests = {
        "Spela Les Rallizes Dénudés Night of the Assassins i köket": "Les Rallizes Dénudés Night of the Assassins",
        "Jag vill höra Xavlegbmaofffassssitimiwoamndutroabcwapwaeiippohfffx i köket": "Xavlegbmaofffassssitimiwoamndutroabcwapwaeiippohfffx",
        "Sätt på Boris Feedbacker i köket": "Boris Feedbacker",
    }

    for transcript, expected_query in requests.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.arguments == {
            "provider": "youtube_music",
            "query": expected_query,
            "output_room": "köket",
        }


def test_action_planner_ignores_conversational_prefix_before_music_command():
    action = ActionPlanner().plan("Okej, kan du spela lite cyberpunk på YouTube Music?", "pixel")

    assert action is not None
    assert action.arguments == {"provider": "youtube_music", "query": "lite cyberpunk"}


def test_action_planner_does_not_treat_recording_as_music_playback():
    assert ActionPlanner().plan("Spela in det här", "pixel") is None
    assert ActionPlanner().plan("Kan du spela in det här?", "pixel") is None


def test_action_planner_proposes_private_playlist_with_confirmation():
    action = ActionPlanner().plan("Skapa en spellista med mörk svensk synth", "pixel")

    assert action is not None
    assert action.name == "playlist.create"
    assert action.arguments["query"] == "mörk svensk synth"
    assert action.requires_confirmation is True


def test_action_planner_accepts_natural_word_order_and_stt_single_l_spelling():
    action = ActionPlanner().plan("Gör en cool cyberpunk spelista.", "pixel")

    assert action is not None
    assert action.name == "playlist.create"
    assert action.arguments == {"provider": "euthervox", "query": "cool cyberpunk"}
    assert action.requires_confirmation is True


def test_action_planner_accepts_hyphenated_playlist_description():
    action = ActionPlanner().plan("Skapa en mörk svensk synth-spellista", "pixel")

    assert action is not None
    assert action.arguments["query"] == "mörk svensk synth"


def test_action_planner_accepts_real_stt_playlist_phrasings():
    examples = {
        "Kan du göra en cool cyberpunkts spellista?": "cool cyberpunkts",
        "Kan du göra en kul spelliste åt mig?": "kul",
        "Cyberpunk spellista": "Cyberpunk",
        "Kan du göra en cyberpunk spelista på YouTube Music?": "cyberpunk",
        "Kan du göra en cool cyberpunk spelista?": "cool cyberpunk",
    }

    for transcript, expected_query in examples.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.name == "playlist.create"
        assert action.arguments["query"] == expected_query


def test_action_planner_accepts_more_natural_playlist_requests():
    examples = {
        "Fixa en glad funk-spellista": "glad funk",
        "Sätt ihop en spellista med svensk punk": "svensk punk",
        "Skulle du kunna skapa en lugn kvällsspellista?": "lugn kvälls",
        "Ge mig en mörk ambient spellista": "mörk ambient",
        "Jag vill ha en snabb träningsspellista": "snabb tränings",
        "Jag skulle vilja ha en spellista med gammal synth": "gammal synth",
    }

    for transcript, expected_query in examples.items():
        action = ActionPlanner().plan(transcript, "pixel")
        assert action is not None, transcript
        assert action.arguments["query"] == expected_query


def test_action_planner_does_not_create_playlist_from_explanatory_question():
    assert ActionPlanner().plan("Vad är en spellista?", "pixel") is None
    assert ActionPlanner().plan("Kan du berätta om en spellista?", "pixel") is None


def test_music_command_returns_action_without_tts():
    class MusicStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Spela Ghost på YouTube Music"

    async def scenario():
        session, sent = make_session()
        session.stt = MusicStt()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "music-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "music-1"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        types = [item["type"] for item in controls]
        assert types == [
            "session.ready", "stt.partial", "stt.final", "assistant.text.delta",
            "assistant.text.final", "action.request",
        ]
        request = controls[-1]
        assert request["target"]["node_name"] == "unknown"
        assert request["arguments"]["query"] == "Ghost"
        assert not any(isinstance(item, bytes) for item in sent)
        assert session.phase is Phase.READY

        await session.handle_text(json.dumps({
            "type": "action.result",
            "action_id": request["action_id"],
            "utterance_id": "music-1",
            "status": "completed",
        }))
        assert not session.pending_actions

    asyncio.run(scenario())


def test_tool_planner_is_used_when_deterministic_parser_does_not_match():
    class NaturalMusicStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Jag är sugen på något mörkt synthigt i köket"

    class FakeToolPlanner:
        async def plan(self, transcript: str, node_name: str):
            assert transcript == "Jag är sugen på något mörkt synthigt i köket"
            return ActionPlanner().plan("Spela mörk synth i köket", node_name)

    class FakeYouTube:
        def authorized(self, user: str) -> bool:
            return True

        def preview(self, query: str) -> PlaylistPreview:
            assert query == "mörk synth"
            return PlaylistPreview("Mörk synth", query, 1)

        async def find_tracks(self, user: str, preview: PlaylistPreview):
            return (PlaylistTrack("youtube", "video-1", "Ett"),)

    class FakeCast:
        played = None

        def configured(self, room: str) -> bool:
            return room == "köket"

        def display_name(self, room: str) -> str:
            return "Kök 2"

        async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]):
            self.played = (room, video_ids)

    async def scenario():
        session, sent = make_session()
        session.stt = NaturalMusicStt()
        session.tool_planner = FakeToolPlanner()
        session.youtube = FakeYouTube()
        session.cast = FakeCast()
        session.authenticated_user = "nichlas"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "tool-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "tool-1"}))
        await session.response_task

        assert session.cast.played == ("köket", ("video-1",))
        assert not any(isinstance(item, dict) and item.get("type") == "action.request" for item in sent)
        assert any(isinstance(item, dict) and item.get("type") == "action.completed" for item in sent)
        assert any(isinstance(item, bytes) for item in sent)
        assert any(isinstance(item, dict) and item.get("type") == "tts.start" for item in sent)

    asyncio.run(scenario())


def test_playlist_creation_requires_confirmation_then_opens_result(tmp_path: Path):
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med mörk svensk synth"

    class FakeYouTube:
        configured = True

        def can_use(self, authenticated_user: str) -> bool:
            return bool(authenticated_user)

        def authorized(self, authenticated_user: str) -> bool:
            return authenticated_user == "nichlas"

        def preview(self, query: str) -> PlaylistPreview:
            return PlaylistPreview("EutherVox – mörk svensk synth", query, 15)

        async def find_tracks(self, authenticated_user: str, preview: PlaylistPreview):
            assert authenticated_user == "nichlas"
            return (PlaylistTrack("youtube", "video-1", "Testlåt"),)

        async def create_playlist(self, authenticated_user: str, playlist) -> CreatedPlaylist:
            assert playlist.tracks[0].provider_id == "video-1"
            return CreatedPlaylist("PL-test", playlist.title, 1)

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.youtube = FakeYouTube()
        session.playlists = TomlPlaylistStore({"directory": str(tmp_path / "playlists")}, ROOT)
        session.authenticated_user = "nichlas"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "playlist-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "playlist-1"}))
        await session.response_task

        proposal = next(item for item in sent if isinstance(item, dict) and item.get("name") == "playlist.create")
        assert proposal["requires_confirmation"] is True
        assert proposal["action_id"] in session.pending_confirmations

        await session.handle_text(json.dumps({"type": "action.confirm", "action_id": proposal["action_id"]}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        assert any(item.get("type") == "action.status" and item.get("status") == "running" for item in controls)
        assert any(item.get("type") == "action.completed" and item.get("status") == "completed" for item in controls)
        opened = next(item for item in controls if item.get("name") == "media.open")
        assert opened["arguments"]["uri"] == "https://music.youtube.com/playlist?list=PL-test"
        stored = session.playlists.latest("nichlas")
        assert stored is not None
        assert stored.owner == "nichlas"
        assert stored.youtube_playlist_id == "PL-test"
        assert stored.tracks[0].title == "Testlåt"
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_playlist_proposal_is_not_sent_without_authenticated_user(tmp_path: Path):
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med mörk svensk synth"

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.playlists = TomlPlaylistStore({"directory": str(tmp_path / "playlists")}, ROOT)
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "playlist-denied"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "playlist-denied"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        assert not any(item.get("type") == "action.request" for item in controls)
        assert controls[-1]["type"] == "assistant.text.final"
        assert "Logga in" in controls[-1]["text"]

    asyncio.run(scenario())


def test_playlist_is_saved_locally_without_youtube_oauth(tmp_path: Path):
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med dimmig skogsmusik"

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.authenticated_user = "anna"
        session.playlists = TomlPlaylistStore({"directory": str(tmp_path / "playlists")}, ROOT)
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "local-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "local-1"}))
        await session.response_task
        proposal = next(item for item in sent if isinstance(item, dict) and item.get("name") == "playlist.create")
        await session.handle_text(json.dumps({"type": "action.confirm", "action_id": proposal["action_id"]}))
        await session.response_task

        stored = session.playlists.latest("anna")
        assert stored is not None
        assert stored.query == "dimmig skogsmusik"
        assert stored.youtube_playlist_id == ""
        controls = [item for item in sent if isinstance(item, dict)]
        fallback = next(item for item in controls if item.get("name") == "media.play")
        assert fallback["arguments"]["query"] == "dimmig skogsmusik"
        assert "Koppla YouTube-kontot" in next(item for item in controls if item.get("type") == "action.completed")["message"]

    asyncio.run(scenario())


def test_toml_playlist_store_separates_users(tmp_path: Path):
    store = TomlPlaylistStore({"directory": str(tmp_path / "playlists")}, ROOT)
    anna = store.create("anna", "Annas lista", "lugn musik")
    bo = store.create("bo", "Bos lista", "snabb musik")

    assert store.latest("anna") == anna
    assert store.latest("bo") == bo
    assert len(list((tmp_path / "playlists").glob("*.toml"))) == 2


def test_confirmed_playlist_casts_to_configured_room_without_phone_fallback(tmp_path: Path):
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med mörk synth i köket"

    class FakeYouTube:
        configured = True

        def authorized(self, user: str) -> bool:
            return True

        def preview(self, query: str) -> PlaylistPreview:
            return PlaylistPreview("EutherVox – mörk synth", query, 15)

        async def find_tracks(self, user: str, preview: PlaylistPreview):
            return (PlaylistTrack("youtube", "video-1", "Testlåt"),)

        async def create_playlist(self, user: str, playlist) -> CreatedPlaylist:
            return CreatedPlaylist("PL-kitchen", playlist.title, 1)

    class FakeCast:
        played = None

        def configured(self, room: str) -> bool:
            return room == "köket"

        def display_name(self, room: str) -> str:
            return "Kök 2"

        async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]):
            self.played = (room, video_ids)

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.youtube = FakeYouTube()
        session.cast = FakeCast()
        session.authenticated_user = "nichlas"
        session.playlists = TomlPlaylistStore({"directory": str(tmp_path / "playlists")}, ROOT)
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "cast-list"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "cast-list"}))
        await session.response_task
        proposal = next(item for item in sent if isinstance(item, dict) and item.get("name") == "playlist.create")
        assert proposal["arguments"]["output_room"] == "köket"
        await session.handle_text(json.dumps({"type": "action.confirm", "action_id": proposal["action_id"]}))
        await session.response_task

        assert session.cast.played == ("köket", ("video-1",))
        controls = [item for item in sent if isinstance(item, dict)]
        assert not any(item.get("name") in {"media.open", "media.play"} for item in controls)
        assert "Kök 2" in next(item for item in controls if item.get("type") == "action.completed")["message"]

    asyncio.run(scenario())


def test_direct_music_request_casts_search_results_to_room():
    class MusicStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Spela Ghost i köket"

    class FakeYouTube:
        def authorized(self, user: str) -> bool:
            return True

        def preview(self, query: str) -> PlaylistPreview:
            return PlaylistPreview("Ghost", query, 2)

        async def find_tracks(self, user: str, preview: PlaylistPreview):
            return (
                PlaylistTrack("youtube", "video-1", "Ett"),
                PlaylistTrack("youtube", "video-2", "Två"),
            )

    class FakeCast:
        played = None

        def configured(self, room: str) -> bool:
            return room == "köket"

        def display_name(self, room: str) -> str:
            return "Kök 2"

        async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]):
            self.played = (room, video_ids)

    async def scenario():
        session, sent = make_session()
        session.stt = MusicStt()
        session.youtube = FakeYouTube()
        session.cast = FakeCast()
        session.authenticated_user = "nichlas"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "cast-direct"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "cast-direct"}))
        await session.response_task

        assert session.cast.played == ("köket", ("video-1", "video-2"))
        controls = [item for item in sent if isinstance(item, dict)]
        assert not any(item.get("type") == "action.request" for item in controls)
        comment = next(item for item in controls if item.get("type") == "assistant.text.final")
        assert "Ghost" in comment["text"]
        assert any(item.get("type") == "tts.start" for item in controls)
        assert any(isinstance(item, bytes) for item in sent)
        assert controls[-1]["type"] == "action.completed"

    asyncio.run(scenario())


def test_music_control_runs_on_server_and_speaks_character_acknowledgement():
    class ControlStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Kan du pausa musiken?"

    class FakeCast:
        controlled = None

        async def control_playback(self, command: str, requested_room: str = "") -> str:
            self.controlled = (command, requested_room)
            return "köket"

    async def scenario():
        session, sent = make_session()
        session.stt = ControlStt()
        session.cast = FakeCast()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "control-pause"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "control-pause"}))
        await session.response_task

        assert session.cast.controlled == ("pause", "")
        controls = [item for item in sent if isinstance(item, dict)]
        assert any(item.get("type") == "action.status" and item.get("status") == "running" for item in controls)
        final = next(item for item in controls if item.get("type") == "assistant.text.final")
        assert final["text"] == "Jag håller tonen stilla i köket."
        assert any(item.get("type") == "tts.start" for item in controls)
        assert any(isinstance(item, bytes) for item in sent)
        completed = next(item for item in controls if item.get("type") == "action.completed")
        assert completed["status"] == "completed"
        assert not any(item.get("type") == "action.request" for item in controls)
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_tv_action_uses_fast_acknowledgement_and_returns_ready():
    class TvStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Kan du sätta på Necteven?"

    class FakeToolPlanner:
        async def plan(self, transcript: str, node_name: str):
            return DeviceAction(
                action_id="tv-action", name="tv.control", target_node=node_name,
                arguments={"target": "TV", "command": "power_on"},
                acknowledgement="Jag slår på TV i vardagsrummet.",
            )

    class FakeTelevision:
        calls = []

        async def control(self, target: str, command: str) -> str:
            self.calls.append((target, command))
            return "TV i vardagsrummet: på."

    class FastTts:
        sample_rate = 24_000
        fast_calls = []

        async def synthesize_fast(self, text, character, sample_rate):
            self.fast_calls.append(text)
            yield bytes(960)

        async def synthesize(self, text, character, sample_rate):
            raise AssertionError("TV acknowledgement must not use the slow selected voice")
            yield bytes(960)

    async def scenario():
        session, sent = make_session()
        session.stt = TvStt()
        session.tool_planner = FakeToolPlanner()
        session.television = FakeTelevision()
        session.tts = FastTts()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "tv-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "tv-1"}))
        await session.response_task

        assert session.television.calls == [("TV", "power_on")]
        assert session.tts.fast_calls == ["Jag slår på TV i vardagsrummet."]
        assert session.phase is Phase.READY
        controls = [item for item in sent if isinstance(item, dict)]
        assert any(item.get("type") == "action.completed" and item.get("status") == "completed" for item in controls)

    asyncio.run(scenario())


def test_pump_status_is_read_server_side_and_spoken():
    class PumpStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Hur varmt är det vid värmepumpen?"

    class FakeToolPlanner:
        async def plan(self, transcript: str, node_name: str):
            return DeviceAction(
                action_id="pump-status",
                name="pump.status",
                target_node=node_name,
                arguments={"target": "Värmepumpen"},
                acknowledgement="Jag läser av värmepumpen.",
            )

    class FakePump:
        calls: list[str] = []

        async def status_text(self, target: str) -> str:
            self.calls.append(target)
            return "Värmepumpen i vardagsrummet är på, det är 20 grader i rummet."

    async def scenario():
        session, sent = make_session()
        session.stt = PumpStt()
        session.tool_planner = FakeToolPlanner()
        session.eutherpump = FakePump()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({
            "type": "audio.start", "utterance_id": "pump-1"
        }))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({
            "type": "audio.end", "utterance_id": "pump-1"
        }))
        await session.response_task

        assert session.eutherpump.calls == ["Värmepumpen"]
        controls = [item for item in sent if isinstance(item, dict)]
        final = next(item for item in controls if item.get("type") == "assistant.text.final")
        assert "20 grader" in final["text"]
        assert any(
            item.get("type") == "action.completed" and item.get("status") == "completed"
            for item in controls
        )
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_pump_voice_control_requires_login_executes_server_side_and_updates_ui():
    class PumpStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Jag fryser"

    class FakeToolPlanner:
        def plan_deterministic(self, transcript: str, node_name: str):
            assert transcript == "Jag fryser"
            return DeviceAction(
                action_id="pump-control",
                name="pump.control",
                target_node=node_name,
                arguments={"target": "Värmepumpen", "temperature_delta": 1},
                acknowledgement="Jag höjer temperaturen med 1 grad.",
            )

    class Target:
        name = "Värmepumpen"

        @staticmethod
        def public() -> dict[str, str]:
            return {"id": "pump-1", "name": "Värmepumpen", "room": "vardagsrummet"}

    class FakePump:
        enabled = True
        calls: list[tuple[str, dict[str, object]]] = []

        @staticmethod
        def list_public() -> list[dict[str, str]]:
            return [{"id": "pump-1", "name": "Värmepumpen", "room": "vardagsrummet"}]

        def resolve(self, target: str) -> Target:
            assert target == "Värmepumpen"
            return Target()

        async def control_voice(self, target: str, changes: dict[str, object]):
            self.calls.append((target, changes))
            return {
                "pump_id": "pump-1", "online": True, "power": True,
                "mode": "heat", "target_temperature": 23,
            }

    async def scenario():
        session, sent = make_session()
        session.stt = PumpStt()
        session.tool_planner = FakeToolPlanner()
        session.eutherpump = FakePump()
        session.authenticated_user = "nichlas"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "pump-voice"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "pump-voice"}))
        await session.response_task

        assert session.eutherpump.calls == [("Värmepumpen", {"temperature_delta": 1})]
        controls = [item for item in sent if isinstance(item, dict)]
        result = next(item for item in controls if item.get("type") == "pump.command.result")
        assert result["pump"]["target_temperature"] == 23
        assert any(
            item.get("type") == "action.completed" and item.get("status") == "completed"
            for item in controls
        )
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_wikipedia_tool_summarizes_source_speaks_and_shows_link():
    class WikipediaStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Berätta om Skinnskatteberg"

    class FakeToolPlanner:
        async def plan(self, transcript: str, node_name: str):
            return DeviceAction(
                action_id="wiki-action",
                name="knowledge.wikipedia",
                target_node=node_name,
                arguments={"query": "Skinnskatteberg", "mode": "summary"},
                acknowledgement="Jag slår upp Skinnskatteberg.",
            )

    class FakeWikipedia:
        async def lookup(self, query: str) -> WikipediaArticle:
            assert query == "Skinnskatteberg"
            return WikipediaArticle(
                "Skinnskatteberg",
                "Skinnskatteberg är en tätort i Västmanland med en historia präglad av bergsbruk.",
                "https://sv.wikipedia.org/wiki/Skinnskatteberg",
            )

    class GroundedLlm:
        async def generate(self, transcript: str, character):
            assert "bergsbruk" in transcript
            yield "Skinnskatteberg ligger i Västmanland. "
            yield "Bergsbruket har satt spår i ortens historia."

    async def scenario():
        session, sent = make_session()
        session.stt = WikipediaStt()
        session.tool_planner = FakeToolPlanner()
        session.wikipedia = FakeWikipedia()
        session.llm = GroundedLlm()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "wiki-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "wiki-1"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        final = next(item for item in controls if item.get("type") == "assistant.text.final")
        assert "Bergsbruket" in final["text"]
        assert "Källa: Skinnskatteberg" in final["text"]
        assert "https://sv.wikipedia.org/wiki/Skinnskatteberg" in final["text"]
        assert any(item.get("type") == "tts.start" for item in controls)
        assert any(isinstance(item, bytes) for item in sent)
        completed = next(item for item in controls if item.get("type") == "action.completed")
        assert completed["status"] == "completed"
        assert not any(item.get("type") == "action.request" for item in controls)

    asyncio.run(scenario())


def test_wikipedia_request_without_topic_asks_for_clarification_without_lookup():
    class WikipediaStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Kan du köra på Wikipedia?"

    class ForbiddenWikipedia:
        async def lookup(self, query: str):
            raise AssertionError("Wikipedia must not be queried without a topic")

    async def scenario():
        session, sent = make_session()
        session.stt = WikipediaStt()
        session.wikipedia = ForbiddenWikipedia()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "wiki-clarify"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "wiki-clarify"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        final = next(item for item in controls if item.get("type") == "assistant.text.final")
        assert final["text"] == "Vad vill du att jag slår upp på Wikipedia?"
        assert any(item.get("type") == "tts.start" for item in controls)
        assert any(isinstance(item, bytes) for item in sent)
        assert not any(item.get("type") == "action.status" for item in controls)
        assert session.pending_wikipedia_mode == "summary"

    asyncio.run(scenario())


def test_wikipedia_clarification_uses_the_next_utterance_as_topic():
    class SequentialStt:
        transcripts = iter(("Kan du köra på Wikipedia?", "Akvariefiskar"))

        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return next(self.transcripts)

    class FakeWikipedia:
        queries = []

        async def lookup(self, query: str) -> WikipediaArticle:
            self.queries.append(query)
            return WikipediaArticle(
                "Akvariefiskar",
                "Akvariefiskar är fiskar som hålls i akvarium.",
                "https://sv.wikipedia.org/wiki/Akvariefiskar",
            )

    class GroundedLlm:
        async def generate(self, transcript: str, character):
            yield "Akvariefiskar hålls i akvarium."

    async def scenario():
        session, sent = make_session()
        wikipedia = FakeWikipedia()
        session.stt = SequentialStt()
        session.wikipedia = wikipedia
        session.llm = GroundedLlm()
        await session.handle_text(start_message())

        for utterance_id in ("wiki-question", "wiki-topic"):
            await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": utterance_id}))
            await session.handle_binary(bytes(640))
            await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": utterance_id}))
            await session.response_task

        assert wikipedia.queries == ["Akvariefiskar"]
        assert session.pending_wikipedia_mode is None
        finals = [
            item for item in sent
            if isinstance(item, dict) and item.get("type") == "assistant.text.final"
        ]
        assert finals[0]["text"] == "Vad vill du att jag slår upp på Wikipedia?"
        assert "Källa: Akvariefiskar" in finals[1]["text"]

    asyncio.run(scenario())


def test_direct_cast_failure_stays_on_requested_room_and_finishes_cleanly():
    class MusicStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Spela mörk synth i köket"

    class FakeYouTube:
        def authorized(self, user: str) -> bool:
            return True

        def preview(self, query: str) -> PlaylistPreview:
            return PlaylistPreview("Mörk synth", query, 1)

        async def find_tracks(self, user: str, preview: PlaylistPreview):
            return (PlaylistTrack("youtube", "video-1", "Ett"),)

    class FailingCast:
        def configured(self, room: str) -> bool:
            return room == "köket"

        def display_name(self, room: str) -> str:
            return "Kök 2"

        async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]):
            raise RuntimeError("Cast svarade inte inom 18 sekunder")

    async def scenario():
        session, sent = make_session()
        session.stt = MusicStt()
        session.youtube = FakeYouTube()
        session.cast = FailingCast()
        session.authenticated_user = "nichlas"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "cast-fallback"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "cast-fallback"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        finals = [item for item in controls if item.get("type") == "assistant.text.final"]
        failed = next(item for item in controls if item.get("type") == "action.completed")
        assert len(finals) == 2
        assert "mörk synth" in finals[0]["text"]
        assert "Kök 2" in finals[-1]["text"]
        assert "Cast svarade inte" in finals[-1]["text"]
        assert failed["status"] == "failed"
        assert failed["action_id"]
        assert not any(item.get("type") == "action.request" for item in controls)

    asyncio.run(scenario())
