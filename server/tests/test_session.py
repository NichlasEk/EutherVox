from __future__ import annotations

import asyncio
import json
from pathlib import Path

from gateway.adapters import (
    MockSpeechToTextEngine,
    MockTextGenerationEngine,
    MockTextToSpeechEngine,
    TomlCharacterProvider,
    _cached_whisper_snapshot,
)
from gateway.actions import ActionPlanner
from gateway.config import load_config
from gateway.session import Phase, ProtocolError, VoiceSession
from gateway.youtube import CreatedPlaylist, PlaylistPreview


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


def test_real_beta_model_paths_are_resolved_from_config_location():
    config = load_config(ROOT / "config.real-beta.example.toml")
    assert Path(config.stt_settings["download_root"]).is_absolute()
    assert Path(config.tts_settings["model_path"]).is_absolute()
    assert config.stt_settings["language"] == "sv"


def test_cached_whisper_snapshot_avoids_remote_model_lookup(tmp_path: Path):
    repository = tmp_path / "models--Systran--faster-whisper-small"
    (repository / "refs").mkdir(parents=True)
    (repository / "refs/main").write_text("revision-1", encoding="utf-8")
    snapshot = repository / "snapshots/revision-1"
    snapshot.mkdir(parents=True)
    (snapshot / "model.bin").touch()

    assert _cached_whisper_snapshot("small", str(tmp_path)) == str(snapshot)


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


def test_action_planner_does_not_treat_recording_as_music_playback():
    assert ActionPlanner().plan("Spela in det här", "pixel") is None


def test_action_planner_proposes_private_playlist_with_confirmation():
    action = ActionPlanner().plan("Skapa en spellista med mörk svensk synth", "pixel")

    assert action is not None
    assert action.name == "playlist.create"
    assert action.arguments["query"] == "mörk svensk synth"
    assert action.requires_confirmation is True


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


def test_playlist_creation_requires_confirmation_then_opens_result():
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med mörk svensk synth"

    class FakeYouTube:
        configured = True
        authorized = True

        def can_use(self, authenticated_user: str) -> bool:
            return authenticated_user == "nichlas"

        def preview(self, query: str) -> PlaylistPreview:
            return PlaylistPreview("EutherVox – mörk svensk synth", query, 15)

        async def create_playlist(self, preview: PlaylistPreview) -> CreatedPlaylist:
            return CreatedPlaylist("PL-test", preview.title, 12)

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.youtube = FakeYouTube()
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
        assert session.phase is Phase.READY

    asyncio.run(scenario())


def test_playlist_proposal_is_not_sent_to_non_owner():
    class PlaylistStt:
        async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
            return "Skapa en spellista med mörk svensk synth"

    class OwnerOnlyYouTube:
        configured = True
        authorized = True

        def can_use(self, authenticated_user: str) -> bool:
            return authenticated_user == "nichlas"

    async def scenario():
        session, sent = make_session()
        session.stt = PlaylistStt()
        session.youtube = OwnerOnlyYouTube()
        session.authenticated_user = "someone-else"
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "playlist-denied"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "playlist-denied"}))
        await session.response_task

        controls = [item for item in sent if isinstance(item, dict)]
        assert not any(item.get("type") == "action.request" for item in controls)
        assert controls[-1]["type"] == "assistant.text.final"
        assert "inte behörighet" in controls[-1]["text"]

    asyncio.run(scenario())
