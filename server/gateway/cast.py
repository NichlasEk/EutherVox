from __future__ import annotations

import asyncio
from dataclasses import dataclass
from uuid import UUID


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
            await asyncio.to_thread(self._play, room, video_ids[0], video_ids[1:])

    def _play(self, room: str, first_video_id: str, queued_ids: tuple[str, ...]) -> None:
        try:
            import pychromecast
            from pychromecast.controllers.youtube import YouTubeController
        except ImportError as error:
            raise RuntimeError("PyChromecast är inte installerat") from error

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
            controller = YouTubeController()
            cast.register_handler(controller)
            self._connections[target.room] = (cast, controller)
        controller.play_video(first_video_id)
        for video_id in queued_ids:
            controller.add_to_queue(video_id)
