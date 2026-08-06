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
