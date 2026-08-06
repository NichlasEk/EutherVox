from __future__ import annotations

import asyncio
import time
from uuid import UUID

from gateway.cast import CastService


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
        service._play = lambda room, video_id: calls.append((room, video_id))

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

        service._play = blocked_play
        try:
            await service.play_youtube_tracks("köket", ("video-1",))
            assert False, "RuntimeError expected"
        except RuntimeError as error:
            assert "Cast svarade inte" in str(error)

        assert connection.disconnected is True
        assert "köket" not in service._connections

    asyncio.run(scenario())
