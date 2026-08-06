from __future__ import annotations

import asyncio
import time
from uuid import UUID

from gateway.cast import CastService
from gateway.youtube_audio import ResolvedAudio, YouTubeAudioResolver


def make_service(**overrides) -> CastService:
    settings = {
        "enabled": True,
        "timeout_seconds": 0.02,
        "operation_timeout_seconds": 0.1,
        "rooms": {
            "köket": {
                "friendly_name": "Kök 2",
                "host": "192.0.2.5",
                "port": 8009,
                "uuid": str(UUID(int=1)),
                "model_name": "Google Nest Mini",
            }
        },
    }
    settings.update(overrides)
    return CastService(settings)


def test_cast_starts_only_first_track_without_blocking_on_queue():
    async def scenario():
        service = make_service()
        calls = []
        service._play_youtube_controller = lambda room, video_id: calls.append((room, video_id))

        await service.play_youtube_tracks("köket", ("video-1", "video-2", "video-3"))

        assert calls == [("köket", "video-1")]

    asyncio.run(scenario())


def test_repeated_room_request_rotates_away_from_previous_first_track():
    async def scenario():
        service = make_service()
        calls = []
        service._play_youtube_controller = lambda room, video_id: calls.append((room, video_id))

        await service.play_youtube_tracks("köket", ("video-1", "video-2", "video-3"))
        await service.play_youtube_tracks("köket", ("video-1", "video-2", "video-3"))

        assert calls == [("köket", "video-1"), ("köket", "video-2")]

    asyncio.run(scenario())


def test_cast_timeout_discards_cached_connection_and_returns_error():
    class FakeCast:
        disconnected = False

        def disconnect(self, timeout: float):
            assert timeout == 0
            self.disconnected = True

    async def scenario():
        service = make_service(operation_timeout_seconds=0.01)
        connection = FakeCast()
        service._connections["köket"] = (connection, object())

        def blocked_play(_room: str, _video_id: str):
            time.sleep(0.05)

        service._play_youtube_controller = blocked_play
        try:
            await service.play_youtube_tracks("köket", ("video-1",))
            assert False, "RuntimeError expected"
        except RuntimeError as error:
            assert "Cast svarade inte" in str(error)

        assert connection.disconnected is True
        assert "köket" not in service._connections

    asyncio.run(scenario())


def test_internal_cast_timeout_is_not_reported_as_full_operation_timeout():
    async def scenario():
        service = make_service(operation_timeout_seconds=1)

        def failed_play(_room: str, _video_id: str):
            raise TimeoutError("socket thread stopped")

        service._play_youtube_controller = failed_play
        try:
            await service.play_youtube_tracks("köket", ("video-1",))
            assert False, "RuntimeError expected"
        except RuntimeError as error:
            assert "avbröts internt" in str(error)
            assert "socket thread stopped" in str(error)
            assert "inom 1" not in str(error)

    asyncio.run(scenario())


def test_direct_audio_backend_resolves_and_casts_only_first_track():
    class FakeResolver:
        async def resolve(self, video_id: str) -> ResolvedAudio:
            assert video_id == "video-1"
            return ResolvedAudio(video_id, "https://media.example/audio.m4a", "audio/mp4", "Testlåt", "")

    async def scenario():
        service = make_service(backend="direct_audio")
        service.audio_resolver = FakeResolver()
        played = []
        service._play_direct_audio = lambda room, audio: played.append((room, audio))

        await service.play_youtube_tracks("köket", ("video-1", "video-2"))

        assert len(played) == 1
        assert played[0][0] == "köket"
        assert played[0][1].content_type == "audio/mp4"

    asyncio.run(scenario())


def test_paused_cast_session_is_explicitly_resumed():
    class FakeStatus:
        media_session_id = 42
        content_id = "https://media.example/new.m4a"
        player_state = "PAUSED"
        player_is_playing = False

    class FakeController:
        status = FakeStatus()
        play_calls = 0

        def block_until_active(self, timeout: float):
            assert timeout == 0.02

        def play(self, timeout: float):
            assert timeout == 0.02
            self.play_calls += 1
            self.status.player_state = "PLAYING"
            self.status.player_is_playing = True

        def update_status(self):
            pass

    service = make_service(playback_confirmation_seconds=0.02)
    controller = FakeController()

    service._confirm_playback(controller, "köket", "https://media.example/new.m4a")

    assert controller.play_calls == 1


def test_existing_media_session_resets_receiver_before_replacement():
    class FakeStatus:
        media_session_id = 41
        player_state = "PAUSED"

    class FakeController:
        status = FakeStatus()

    class FakeCast:
        quit_calls = 0

        def quit_app(self, timeout: float):
            assert timeout == 0.02
            self.quit_calls += 1

    service = make_service(playback_confirmation_seconds=0.02)
    controller = FakeController()
    cast = FakeCast()

    service._reset_existing_receiver(cast, controller, "köket")

    assert cast.quit_calls == 1


def test_media_status_is_synchronized_before_replacement_decision():
    class FakeStatus:
        media_session_id = None
        player_state = "UNKNOWN"

    class FakeController:
        status = FakeStatus()

        def update_status(self, *, callback_function):
            self.status.media_session_id = 41
            self.status.player_state = "PAUSED"
            callback_function(True, {})

    service = make_service(playback_confirmation_seconds=0.02)
    controller = FakeController()

    service._sync_media_status(controller, "köket")

    assert controller.status.media_session_id == 41
    assert controller.status.player_state == "PAUSED"


def test_existing_session_is_not_resumed_before_new_media_is_loaded():
    class FakeStatus:
        media_session_id = 41
        content_id = "https://media.example/old.m4a"
        player_state = "PAUSED"
        player_is_playing = False

    class FakeController:
        status = FakeStatus()
        update_calls = 0
        played_content_id = ""

        def block_until_active(self, timeout: float):
            assert timeout == 0.05

        def update_status(self):
            self.update_calls += 1
            self.status.media_session_id = 42
            self.status.content_id = "https://media.example/new.m4a"

        def play(self, timeout: float):
            assert timeout == 0.05
            self.played_content_id = self.status.content_id
            self.status.player_state = "PLAYING"
            self.status.player_is_playing = True

    service = make_service(playback_confirmation_seconds=0.05)
    controller = FakeController()

    service._confirm_playback(controller, "köket", "https://media.example/new.m4a")

    assert controller.update_calls >= 1
    assert controller.played_content_id == "https://media.example/new.m4a"


def test_youtube_audio_resolver_accepts_only_googlevideo_https_streams():
    class FakeDownloader:
        def __init__(self, _options):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def extract_info(self, url: str, download: bool):
            assert url.endswith("watch?v=En0eXwXgZFI")
            assert download is False
            return {
                "url": "https://r.example.googlevideo.com/videoplayback?signed=yes",
                "ext": "m4a",
                "title": "Cyberpunk",
                "thumbnail": "https://i.ytimg.com/test.jpg",
            }

    async def scenario():
        resolver = YouTubeAudioResolver(downloader_factory=FakeDownloader)
        audio = await resolver.resolve("En0eXwXgZFI")
        assert audio.content_type == "audio/mp4"
        assert audio.title == "Cyberpunk"

        try:
            await resolver.resolve("https://attacker.invalid")
            assert False, "ValueError expected"
        except ValueError:
            pass

    asyncio.run(scenario())
