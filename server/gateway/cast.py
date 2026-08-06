from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
from uuid import UUID


LOG = logging.getLogger("euthervox.cast")


@dataclass(frozen=True)
class CastTarget:
    room: str
    friendly_name: str
    host: str
    port: int
    uuid: UUID
    model_name: str


class CastService:
    """Experimental, explicitly configured Cast targets with no media proxying."""

    def __init__(self, settings: dict):
        self.enabled = bool(settings.get("enabled", False))
        self.timeout_seconds = float(settings.get("timeout_seconds", 8.0))
        self.operation_timeout_seconds = float(
            settings.get("operation_timeout_seconds", self.timeout_seconds * 2 + 2)
        )
        self.targets: dict[str, CastTarget] = {}
        for room, raw in dict(settings.get("rooms", {})).items():
            try:
                target = CastTarget(
                    room=str(room).casefold(),
                    friendly_name=str(raw["friendly_name"]),
                    host=str(raw["host"]),
                    port=int(raw.get("port", 8009)),
                    uuid=UUID(str(raw["uuid"])),
                    model_name=str(raw.get("model_name", "Google Cast")),
                )
            except (KeyError, TypeError, ValueError):
                continue
            self.targets[target.room] = target
        self._lock = asyncio.Lock()
        self._connections: dict[str, tuple[object, object]] = {}

    def configured(self, room: str) -> bool:
        return self.enabled and room.casefold() in self.targets

    def display_name(self, room: str) -> str:
        target = self.targets.get(room.casefold())
        return target.friendly_name if target else room

    async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]) -> None:
        if not video_ids:
            raise ValueError("Inga spelbara YouTube-träffar hittades")
        async with self._lock:
            try:
                await asyncio.wait_for(
                    asyncio.to_thread(self._play, room, video_ids[0]),
                    timeout=self.operation_timeout_seconds,
                )
            except TimeoutError as error:
                self._discard_connection(room)
                raise RuntimeError(
                    f"Cast svarade inte inom {self.operation_timeout_seconds:g} sekunder"
                ) from error
            except Exception:
                self._discard_connection(room)
                raise
            if len(video_ids) > 1:
                LOG.info(
                    "cast_started room=%s first_video=%s deferred_tracks=%d",
                    room,
                    video_ids[0],
                    len(video_ids) - 1,
                )

    def _play(self, room: str, first_video_id: str) -> None:
        try:
            import pychromecast
            from pychromecast.const import MESSAGE_TYPE
            from pychromecast.controllers.youtube import TYPE_GET_SCREEN_ID, YouTubeController
        except ImportError as error:
            raise RuntimeError("PyChromecast är inte installerat") from error

        timeout_seconds = self.timeout_seconds

        class TimedYouTubeController(YouTubeController):
            def update_screen_id(controller_self) -> None:
                controller_self.status_update_event.clear()
                controller_self.send_message({MESSAGE_TYPE: TYPE_GET_SCREEN_ID})
                if not controller_self.status_update_event.wait(timeout_seconds):
                    controller_self.status_update_event.clear()
                    raise RuntimeError("Nest svarade inte på YouTube-sessionens handskakning")
                controller_self.status_update_event.clear()

        target = self.targets.get(room.casefold())
        if not self.enabled or not target:
            raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {room}")
        existing = self._connections.get(target.room)
        if existing:
            cast, controller = existing
        else:
            cast = pychromecast.get_chromecast_from_host(
                (target.host, target.port, target.uuid, target.model_name, target.friendly_name),
                timeout=self.timeout_seconds,
            )
            cast.wait(timeout=self.timeout_seconds)
            controller = TimedYouTubeController(timeout=self.timeout_seconds)
            cast.register_handler(controller)
        controller.play_video(first_video_id)
        self._connections[target.room] = (cast, controller)

    def _discard_connection(self, room: str) -> None:
        connection = self._connections.pop(room.casefold(), None)
        if not connection:
            return
        cast, _controller = connection
        try:
            cast.disconnect(timeout=0)
        except Exception:
            LOG.debug("cast_disconnect_failed room=%s", room, exc_info=True)
