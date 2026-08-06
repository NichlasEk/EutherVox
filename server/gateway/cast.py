from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
import threading
import time
from uuid import UUID

from .youtube_audio import ResolvedAudio, YouTubeAudioResolver


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
        self.backend = str(settings.get("backend", "youtube_controller"))
        self.timeout_seconds = float(settings.get("timeout_seconds", 8.0))
        self.operation_timeout_seconds = float(
            settings.get("operation_timeout_seconds", self.timeout_seconds * 2 + 2)
        )
        self.resolver_timeout_seconds = float(settings.get("resolver_timeout_seconds", 20.0))
        self.playback_confirmation_seconds = float(settings.get("playback_confirmation_seconds", 8.0))
        self.audio_resolver = YouTubeAudioResolver(self.timeout_seconds)
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
        self._last_video_ids: dict[str, str] = {}

    def configured(self, room: str) -> bool:
        return self.enabled and room.casefold() in self.targets

    def display_name(self, room: str) -> str:
        target = self.targets.get(room.casefold())
        return target.friendly_name if target else room

    def resolve_control_room(self, requested_room: str = "") -> str:
        room = requested_room.casefold().strip()
        if room:
            if room not in self.targets:
                raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {requested_room}")
            return room
        active_rooms = [candidate for candidate in self._connections if candidate in self.targets]
        if len(active_rooms) == 1:
            return active_rooms[0]
        if len(self.targets) == 1:
            return next(iter(self.targets))
        if not self.targets:
            raise RuntimeError("Ingen Cast-enhet är konfigurerad")
        raise RuntimeError("Ange vilket rum som ska styras")

    async def control_playback(self, command: str, requested_room: str = "") -> str:
        if command not in {"pause", "resume", "stop"}:
            raise ValueError(f"Okänt mediakommando: {command}")
        room = self.resolve_control_room(requested_room)
        async with self._lock:
            operation = asyncio.create_task(asyncio.to_thread(self._control_playback_sync, room, command))
            try:
                await asyncio.wait_for(operation, timeout=self.operation_timeout_seconds)
            except TimeoutError as error:
                if operation.done() and not operation.cancelled():
                    raise RuntimeError(f"Cast-kommandot avbröts internt: {error}") from error
                raise RuntimeError(f"Cast svarade inte inom {self.operation_timeout_seconds:g} sekunder") from error
        return room

    async def play_youtube_tracks(self, room: str, video_ids: tuple[str, ...]) -> None:
        if not video_ids:
            raise ValueError("Inga spelbara YouTube-träffar hittades")
        room_key = room.casefold()
        previous_video_id = self._last_video_ids.get(room_key, "")
        if previous_video_id in video_ids:
            selected_video_id = video_ids[(video_ids.index(previous_video_id) + 1) % len(video_ids)]
        else:
            selected_video_id = video_ids[0]
        async with self._lock:
            try:
                if self.backend == "direct_audio":
                    audio = await asyncio.wait_for(
                        self.audio_resolver.resolve(selected_video_id),
                        timeout=self.resolver_timeout_seconds,
                    )
                    operation = asyncio.to_thread(self._play_direct_audio, room, audio)
                elif self.backend == "youtube_controller":
                    operation = asyncio.to_thread(self._play_youtube_controller, room, selected_video_id)
                else:
                    raise RuntimeError(f"Okänd Cast-backend: {self.backend}")
                operation_task = asyncio.create_task(operation)
                await asyncio.wait_for(
                    operation_task,
                    timeout=self.operation_timeout_seconds,
                )
            except TimeoutError as error:
                self._discard_connection(room)
                if "operation_task" in locals() and operation_task.done() and not operation_task.cancelled():
                    raise RuntimeError(f"Cast-operationen avbröts internt: {error}") from error
                raise RuntimeError(
                    f"Cast svarade inte inom {self.operation_timeout_seconds:g} sekunder"
                ) from error
            except Exception:
                self._discard_connection(room)
                raise
            self._last_video_ids[room_key] = selected_video_id
            if len(video_ids) > 1:
                LOG.info(
                    "cast_started room=%s first_video=%s deferred_tracks=%d",
                    room,
                    selected_video_id,
                    len(video_ids) - 1,
                )

    def _play_youtube_controller(self, room: str, first_video_id: str) -> None:
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

    def _play_direct_audio(self, room: str, audio: ResolvedAudio) -> None:
        try:
            import pychromecast
        except ImportError as error:
            raise RuntimeError("PyChromecast är inte installerat") from error

        target = self.targets.get(room.casefold())
        if not self.enabled or not target:
            raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {room}")
        existing = self._connections.get(target.room)
        created = existing is None
        if existing:
            cast, _controller = existing
            LOG.info("cast_audio_connection room=%s mode=reuse", target.room)
        else:
            LOG.info("cast_audio_connection room=%s mode=connect host=%s port=%d", target.room, target.host, target.port)
            cast = pychromecast.get_chromecast_from_host(
                (target.host, target.port, target.uuid, target.model_name, target.friendly_name),
                timeout=self.timeout_seconds,
            )
            cast.wait(timeout=self.timeout_seconds)
            LOG.info("cast_audio_connected room=%s", target.room)
        controller = cast.media_controller
        try:
            self._sync_media_status(controller, target.room)
            self._reset_existing_receiver(cast, controller, target.room)
            controller.play_media(
                audio.url,
                audio.content_type,
                title=audio.title,
                thumb=audio.thumbnail or None,
                stream_type="BUFFERED",
            )
            LOG.info("cast_audio_media_sent room=%s video_id=%s", target.room, audio.video_id)
            self._confirm_playback(controller, target.room, audio.url)
        except Exception:
            if created:
                try:
                    cast.disconnect(timeout=0)
                except Exception:
                    LOG.debug("cast_disconnect_failed room=%s", room, exc_info=True)
            raise
        self._connections[target.room] = (cast, controller)
        LOG.info(
            "cast_audio_playing room=%s video_id=%s content_type=%s title=%r",
            target.room,
            audio.video_id,
            audio.content_type,
            audio.title,
        )

    def _control_playback_sync(self, room: str, command: str) -> None:
        try:
            import pychromecast
        except ImportError as error:
            raise RuntimeError("PyChromecast är inte installerat") from error
        target = self.targets.get(room)
        if not self.enabled or not target:
            raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {room}")
        existing = self._connections.get(room)
        created = existing is None
        if existing:
            cast, controller = existing
        else:
            cast = pychromecast.get_chromecast_from_host(
                (target.host, target.port, target.uuid, target.model_name, target.friendly_name),
                timeout=self.timeout_seconds,
            )
            cast.wait(timeout=self.timeout_seconds)
            controller = cast.media_controller
        try:
            self._sync_media_status(controller, room)
            self._apply_media_control(cast, controller, command, room)
        except Exception:
            if created:
                self._disconnect(cast, room)
            else:
                self._discard_connection(room)
            raise
        if command == "stop":
            if created:
                self._disconnect(cast, room)
            else:
                self._discard_connection(room)
        else:
            self._connections[room] = (cast, controller)

    def _apply_media_control(self, cast: object, controller: object, command: str, room: str) -> None:
        if not controller.status.media_session_id:
            raise RuntimeError(f"Ingen aktiv uppspelning finns i {room}")
        if command == "pause":
            controller.pause(timeout=self.playback_confirmation_seconds)
        elif command == "resume":
            controller.play(timeout=self.playback_confirmation_seconds)
        else:
            cast.quit_app(timeout=self.playback_confirmation_seconds)
        LOG.info("cast_audio_control room=%s command=%s", room, command)

    def _sync_media_status(self, controller: object, room: str) -> None:
        response_received = threading.Event()

        def status_response(_success: bool, _response: object) -> None:
            response_received.set()

        controller.update_status(callback_function=status_response)
        if not response_received.wait(timeout=self.playback_confirmation_seconds):
            raise RuntimeError("Nest svarade inte på mediastatusförfrågan")
        LOG.info(
            "cast_audio_synced room=%s player_state=%s media_session=%s",
            room,
            controller.status.player_state,
            controller.status.media_session_id or "none",
        )

    def _reset_existing_receiver(self, cast: object, controller: object, room: str) -> None:
        if not controller.status.media_session_id:
            return
        LOG.info(
            "cast_audio_replace room=%s player_state=%s media_session=%s",
            room,
            controller.status.player_state,
            controller.status.media_session_id,
        )
        cast.quit_app(timeout=self.playback_confirmation_seconds)
        LOG.info("cast_audio_receiver_reset room=%s", room)

    def _confirm_playback(self, controller: object, room: str, expected_content_id: str) -> None:
        controller.block_until_active(timeout=self.playback_confirmation_seconds)
        load_deadline = time.monotonic() + self.playback_confirmation_seconds
        while controller.status.content_id != expected_content_id and time.monotonic() < load_deadline:
            controller.update_status()
            time.sleep(0.2)
        status = controller.status
        LOG.info(
            "cast_audio_status room=%s player_state=%s media_session=%s",
            room,
            status.player_state,
            status.media_session_id or "none",
        )
        if not status.media_session_id:
            raise RuntimeError("Nest skapade ingen mediasession")
        if status.content_id != expected_content_id:
            raise RuntimeError("Nest bekräftade inte det nya mediet")
        if not status.player_is_playing:
            LOG.info("cast_audio_resume room=%s player_state=%s", room, status.player_state)
            deadline = time.monotonic() + self.playback_confirmation_seconds
            controller.play(timeout=self.playback_confirmation_seconds)
            while not controller.status.player_is_playing and time.monotonic() < deadline:
                controller.update_status()
                time.sleep(0.2)
        if not controller.status.player_is_playing:
            raise RuntimeError(
                f"Nest skapade en mediasession men startade inte ljudet (status {controller.status.player_state})"
            )

    def _discard_connection(self, room: str) -> None:
        connection = self._connections.pop(room.casefold(), None)
        if not connection:
            return
        cast, _controller = connection
        self._disconnect(cast, room)

    def _disconnect(self, cast: object, room: str) -> None:
        try:
            cast.disconnect(timeout=0)
        except Exception:
            LOG.debug("cast_disconnect_failed room=%s", room, exc_info=True)
