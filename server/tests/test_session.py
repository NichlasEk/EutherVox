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
from gateway.playlists import PlaylistTrack, TomlPlaylistStore
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


def test_action_planner_extracts_kitchen_output_from_music_and_playlist_requests():
    music = ActionPlanner().plan("Spela Ghost i köket", "pixel")
    playlist = ActionPlanner().plan("Skapa en spellista med mörk synth på Kök 2", "pixel")

    assert music is not None
    assert music.arguments == {"provider": "youtube_music", "query": "Ghost", "output_room": "köket"}
    assert playlist is not None
    assert playlist.arguments == {"provider": "euthervox", "query": "mörk synth", "output_room": "köket"}


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
        assert not any(isinstance(item, bytes) for item in sent)

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
        assert controls[-1]["type"] == "action.completed"

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
        assert len(finals) == 1
        assert "Kök 2" in finals[0]["text"]
        assert "Cast svarade inte" in finals[0]["text"]
        assert failed["status"] == "failed"
        assert failed["action_id"]
        assert not any(item.get("type") == "action.request" for item in controls)

    asyncio.run(scenario())
