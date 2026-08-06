from __future__ import annotations

import asyncio
from dataclasses import dataclass, field, replace
from enum import Enum
import json
import logging
import time
from typing import Awaitable, Callable
from uuid import uuid4

from .adapters import CharacterProvider, SpeechToTextEngine, TextGenerationEngine, TextToSpeechEngine
from .actions import ActionPlanner, DeviceAction
from .cast import CastService
from .config import GatewayConfig
from .playlists import TomlPlaylistStore
from .youtube import YouTubePlaylistService


SendJson = Callable[[dict], Awaitable[None]]
SendBinary = Callable[[bytes], Awaitable[None]]
LOG = logging.getLogger("euthervox.session")


class Phase(Enum):
    CONNECTED = "connected"
    READY = "ready"
    RECORDING = "recording"
    PROCESSING = "processing"
    SPEAKING = "speaking"


class ProtocolError(Exception):
    def __init__(self, code: str, message: str, recoverable: bool = True):
        super().__init__(message)
        self.code = code
        self.recoverable = recoverable


@dataclass
class VoiceSession:
    config: GatewayConfig
    stt: SpeechToTextEngine
    llm: TextGenerationEngine
    tts: TextToSpeechEngine
    characters: CharacterProvider
    send_json: SendJson
    send_binary: SendBinary
    youtube: YouTubePlaylistService | None = None
    playlists: TomlPlaylistStore | None = None
    cast: CastService | None = None
    authenticated_user: str = ""
    session_id: str = field(default_factory=lambda: str(uuid4()))
    phase: Phase = Phase.CONNECTED
    utterance_id: str | None = None
    character_name: str = ""
    node_name: str = "unknown"
    audio: bytearray = field(default_factory=bytearray)
    response_task: asyncio.Task | None = None
    received_frames: int = 0
    started_ns: int = 0
    audio_end_ns: int = 0
    action_planner: ActionPlanner = field(default_factory=ActionPlanner)
    pending_actions: set[str] = field(default_factory=set)
    pending_confirmations: dict[str, DeviceAction] = field(default_factory=dict)

    async def handle_text(self, raw: str) -> None:
        try:
            message = json.loads(raw)
            message_type = message.get("type")
            if message_type == "session.start":
                await self._start_session(message)
            elif message_type == "audio.start":
                await self._start_audio(message)
            elif message_type == "audio.end":
                await self._end_audio(message)
            elif message_type == "response.cancel":
                await self._cancel(message)
            elif message_type == "action.result":
                await self._action_result(message)
            elif message_type == "action.confirm":
                await self._action_confirm(message)
            else:
                raise ProtocolError("UNKNOWN_MESSAGE", f"Unsupported message type: {message_type}")
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
            raise ProtocolError("INVALID_MESSAGE", str(error)) from error

    async def handle_binary(self, data: bytes) -> None:
        if self.phase is not Phase.RECORDING or not self.utterance_id:
            raise ProtocolError("UNEXPECTED_AUDIO", "Binary audio requires an active utterance")
        self.audio.extend(data)
        self.received_frames += 1

    async def close(self) -> None:
        if self.response_task:
            self.response_task.cancel()
            await asyncio.gather(self.response_task, return_exceptions=True)

    async def _start_session(self, message: dict) -> None:
        if self.phase is not Phase.CONNECTED:
            raise ProtocolError("SESSION_EXISTS", "Session has already started")
        if int(message.get("protocol_version", 0)) != 1:
            raise ProtocolError("PROTOCOL_VERSION", "Only protocol version 1 is supported", False)
        audio = message.get("input_audio", {})
        expected = self.config.input_audio
        if (
            audio.get("codec") != "pcm_s16le"
            or int(audio.get("sample_rate", 0)) != expected.sample_rate
            or int(audio.get("channels", 0)) != expected.channels
            or int(audio.get("frame_ms", 0)) != expected.frame_ms
        ):
            raise ProtocolError("UNSUPPORTED_AUDIO", "Expected mono pcm_s16le, 16000 Hz, 20 ms frames", False)
        self.character_name = message.get("character", self.config.default_character)
        self.node_name = str(message.get("node_name", "unknown"))
        self.characters.get(self.character_name)
        self.phase = Phase.READY
        await self.send_json({"type": "session.ready", "session_id": self.session_id, "protocol_version": 1})
        LOG.info("session_ready session=%s character=%s", self.session_id, self.character_name)

    async def _start_audio(self, message: dict) -> None:
        if self.phase is not Phase.READY:
            raise ProtocolError("SESSION_BUSY", "Only one utterance may be active")
        utterance_id = str(message["utterance_id"])
        if not utterance_id:
            raise ProtocolError("INVALID_MESSAGE", "utterance_id is required")
        self.utterance_id = utterance_id
        self.audio.clear()
        self.received_frames = 0
        self.started_ns = time.monotonic_ns()
        self.phase = Phase.RECORDING
        LOG.info("audio_start session=%s utterance=%s", self.session_id, utterance_id)

    async def _end_audio(self, message: dict) -> None:
        if self.phase is not Phase.RECORDING or message.get("utterance_id") != self.utterance_id:
            raise ProtocolError("UTTERANCE_MISMATCH", "audio.end does not match active utterance")
        self.phase = Phase.PROCESSING
        self.audio_end_ns = time.monotonic_ns()
        pcm = bytes(self.audio)
        utterance_id = self.utterance_id
        LOG.info("audio_end session=%s utterance=%s bytes=%d frames=%d", self.session_id, utterance_id, len(pcm), self.received_frames)
        self.response_task = asyncio.create_task(self._respond(utterance_id, pcm))

    async def _cancel(self, message: dict) -> None:
        if message.get("utterance_id") != self.utterance_id:
            raise ProtocolError("UTTERANCE_MISMATCH", "response.cancel does not match active utterance")
        if self.response_task:
            self.response_task.cancel()
            await asyncio.gather(self.response_task, return_exceptions=True)
        await self.send_json({"type": "response.cancelled", "utterance_id": self.utterance_id})
        self._reset()

    async def _action_result(self, message: dict) -> None:
        action_id = str(message["action_id"])
        if action_id in self.pending_confirmations:
            self.pending_confirmations.pop(action_id)
        elif action_id in self.pending_actions:
            self.pending_actions.remove(action_id)
        else:
            raise ProtocolError("ACTION_MISMATCH", "action.result does not match a requested action")
        status = str(message.get("status", "unknown"))
        detail = str(message.get("message", ""))
        LOG.info(
            "action_result session=%s action=%s status=%s message=%r",
            self.session_id,
            action_id,
            status,
            detail,
        )

    async def _action_confirm(self, message: dict) -> None:
        action_id = str(message["action_id"])
        action = self.pending_confirmations.pop(action_id, None)
        if action is None:
            raise ProtocolError("ACTION_MISMATCH", "action.confirm does not match a proposed action")
        if self.phase is not Phase.READY:
            raise ProtocolError("SESSION_BUSY", "Cannot confirm an action while the session is busy")
        self.phase = Phase.PROCESSING
        self.response_task = asyncio.create_task(self._execute_confirmed_action(action))

    async def _execute_confirmed_action(self, action: DeviceAction) -> None:
        try:
            if action.name != "playlist.create" or not self.playlists or not self.authenticated_user:
                raise RuntimeError("Spellistetjänsten är inte konfigurerad")
            query = str(action.arguments["query"])
            output_room = str(action.arguments.get("output_room", ""))
            preview = self.youtube.preview(query) if self.youtube else None
            title = preview.title if preview else f"EutherVox – {query[:60]}"
            await self.send_json({
                "type": "action.status",
                "action_id": action.action_id,
                "status": "running",
                "message": f"Sparar {title} privat för {self.authenticated_user}…",
            })
            local = self.playlists.create(self.authenticated_user, title, query)
            created = None
            bridge_error = ""
            cast_succeeded = False
            cast_error = ""
            if self.youtube and self.youtube.configured and self.youtube.authorized(self.authenticated_user):
                try:
                    tracks = await self.youtube.find_tracks(self.authenticated_user, preview)
                    local = self.playlists.save(replace(local, tracks=tracks))
                    created = await self.youtube.create_playlist(self.authenticated_user, local)
                    local = self.playlists.save(replace(local, youtube_playlist_id=created.playlist_id))
                except Exception as error:
                    bridge_error = str(error)
                    LOG.exception("youtube_playlist_bridge_failed session=%s local_playlist=%s", self.session_id, local.playlist_id)

            if created and output_room:
                try:
                    if not self.cast or not self.cast.configured(output_room):
                        raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {output_room}")
                    await self.send_json({
                        "type": "action.status",
                        "action_id": action.action_id,
                        "status": "running",
                        "message": f"Ansluter till {self.cast.display_name(output_room)}…",
                    })
                    await self.cast.play_youtube_tracks(
                        output_room,
                        tuple(track.provider_id for track in local.tracks),
                    )
                    cast_succeeded = True
                except Exception as error:
                    cast_error = str(error)
                    LOG.exception("playlist_cast_failed session=%s room=%s", self.session_id, output_room)

            if created and cast_succeeded:
                message = f"{local.title} sparades privat och spelar nu på {self.cast.display_name(output_room)}."
            elif created and cast_error:
                message = f"{local.title} sparades privat. Cast misslyckades ({cast_error}); öppnar listan på telefonen."
            elif created:
                message = f"{local.title} sparades privat och speglades till YouTube Music med {created.track_count} låtar."
            elif bridge_error:
                message = f"{local.title} sparades privat. YouTube-synkningen misslyckades: {bridge_error}"
            else:
                message = f"{local.title} sparades privat. Koppla YouTube-kontot för en spelbar spegling."
            await self.send_json({
                "type": "action.completed",
                "action_id": action.action_id,
                "status": "completed",
                "message": message,
            })
            if cast_succeeded:
                LOG.info(
                    "playlist_cast session=%s local_playlist=%s room=%s youtube_playlist=%s",
                    self.session_id,
                    local.playlist_id,
                    output_room,
                    local.youtube_playlist_id,
                )
                return
            playback_arguments = (
                {"provider": "youtube_music", "uri": local.youtube_music_url}
                if local.youtube_music_url
                else {"provider": "youtube_music", "query": local.query}
            )
            playback_action = DeviceAction(
                action_id=str(uuid4()),
                name="media.open" if local.youtube_music_url else "media.play",
                target_node=action.target_node,
                arguments=playback_arguments,
                acknowledgement="Spellistan är klar.",
            )
            self.pending_actions.add(playback_action.action_id)
            await self.send_json(playback_action.to_message(self.utterance_id or ""))
            LOG.info(
                "playlist_saved session=%s local_playlist=%s youtube_playlist=%s owner=%s tracks=%d",
                self.session_id,
                local.playlist_id,
                local.youtube_playlist_id or "none",
                self.authenticated_user,
                len(local.tracks),
            )
        except asyncio.CancelledError:
            raise
        except Exception as error:
            LOG.exception("confirmed_action_failed session=%s action=%s", self.session_id, action.action_id)
            await self.send_json({
                "type": "action.completed",
                "action_id": action.action_id,
                "status": "failed",
                "message": str(error),
            })
        finally:
            self._reset()

    async def _respond(self, utterance_id: str, pcm: bytes) -> None:
        try:
            initial_partial = getattr(self.stt, "initial_partial", None)
            if initial_partial:
                await self.send_json({"type": "stt.partial", "utterance_id": utterance_id, "text": initial_partial})
            transcript = await asyncio.wait_for(
                self.stt.transcribe(pcm, self.config.input_audio.sample_rate),
                timeout=self.config.response_timeout_seconds,
            )
            if self.config.text_logging:
                LOG.info("stt_final session=%s utterance=%s text=%r", self.session_id, utterance_id, transcript)
            if not transcript:
                raise ValueError("No speech was recognized")
            LOG.info(
                "metric session=%s utterance=%s event=stt_final after_audio_end_ms=%d",
                self.session_id,
                utterance_id,
                (time.monotonic_ns() - self.audio_end_ns) // 1_000_000,
            )
            if not initial_partial:
                await self.send_json({"type": "stt.partial", "utterance_id": utterance_id, "text": transcript})
            await self.send_json({"type": "stt.final", "utterance_id": utterance_id, "text": transcript})
            action = self.action_planner.plan(transcript, self.node_name)
            if action:
                if action.name == "playlist.create":
                    if not self.authenticated_user:
                        await self.send_json({
                            "type": "assistant.text.final",
                            "utterance_id": utterance_id,
                            "text": "Logga in med ditt EutherOxide-konto så att spellistan kan knytas privat till dig.",
                        })
                        self._reset()
                        return
                    if not self.playlists:
                        await self.send_json({
                            "type": "assistant.text.final",
                            "utterance_id": utterance_id,
                            "text": "Den lokala spellistetjänsten är inte konfigurerad.",
                        })
                        self._reset()
                        return
                output_room = str(action.arguments.get("output_room", ""))
                if action.name == "media.play" and output_room:
                    try:
                        if not self.authenticated_user or not self.youtube or not self.youtube.authorized(self.authenticated_user):
                            raise RuntimeError("YouTube-kontot är inte kopplat")
                        if not self.cast or not self.cast.configured(output_room):
                            raise RuntimeError(f"Ingen Cast-enhet är konfigurerad för {output_room}")
                        await self.send_json({"type": "assistant.text.delta", "utterance_id": utterance_id, "text": action.acknowledgement})
                        await self.send_json({"type": "assistant.text.final", "utterance_id": utterance_id, "text": action.acknowledgement})
                        await self.send_json({
                            "type": "action.status",
                            "action_id": action.action_id,
                            "status": "running",
                            "message": f"Söker musik och ansluter till {self.cast.display_name(output_room)}…",
                        })
                        preview = self.youtube.preview(str(action.arguments["query"]))
                        tracks = await self.youtube.find_tracks(self.authenticated_user, preview)
                        await self.cast.play_youtube_tracks(output_room, tuple(track.provider_id for track in tracks))
                        await self.send_json({
                            "type": "action.completed",
                            "action_id": action.action_id,
                            "status": "completed",
                            "message": f"Spelar på {self.cast.display_name(output_room)}.",
                        })
                        self._reset()
                        return
                    except Exception as error:
                        LOG.exception("media_cast_failed session=%s room=%s", self.session_id, output_room)
                        action = replace(
                            action,
                            acknowledgement=f"Cast till {output_room} misslyckades ({error}). Jag öppnar musiken på telefonen i stället.",
                        )
                await self.send_json({
                    "type": "assistant.text.delta",
                    "utterance_id": utterance_id,
                    "text": action.acknowledgement,
                })
                await self.send_json({
                    "type": "assistant.text.final",
                    "utterance_id": utterance_id,
                    "text": action.acknowledgement,
                })
                if action.requires_confirmation:
                    self.pending_confirmations[action.action_id] = action
                else:
                    self.pending_actions.add(action.action_id)
                await self.send_json(action.to_message(utterance_id))
                LOG.info(
                    "action_request session=%s utterance=%s action=%s name=%s target=%s",
                    self.session_id,
                    utterance_id,
                    action.action_id,
                    action.name,
                    action.target_node,
                )
                self._reset()
                return
            character = self.characters.get(self.character_name)
            pieces: list[str] = []
            first_piece = True
            async for piece in self.llm.generate(transcript, character):
                if first_piece:
                    first_piece = False
                    LOG.info(
                        "metric session=%s utterance=%s event=first_response_text after_audio_end_ms=%d",
                        self.session_id,
                        utterance_id,
                        (time.monotonic_ns() - self.audio_end_ns) // 1_000_000,
                    )
                pieces.append(piece)
                await self.send_json({"type": "assistant.text.delta", "utterance_id": utterance_id, "text": piece})
            response = "".join(pieces)
            if self.config.text_logging:
                LOG.info("assistant_final session=%s utterance=%s text=%r", self.session_id, utterance_id, response)
            await self.send_json({"type": "assistant.text.final", "utterance_id": utterance_id, "text": response})
            self.phase = Phase.SPEAKING
            await self.send_json({
                "type": "tts.start",
                "utterance_id": utterance_id,
                "audio": {"codec": "pcm_s16le", "sample_rate": self.tts.sample_rate, "channels": 1},
            })
            frames = 0
            async for frame in self.tts.synthesize(response, character, self.tts.sample_rate):
                if frames == 0:
                    LOG.info(
                        "metric session=%s utterance=%s event=first_tts_frame after_audio_end_ms=%d",
                        self.session_id,
                        utterance_id,
                        (time.monotonic_ns() - self.audio_end_ns) // 1_000_000,
                    )
                await self.send_binary(frame)
                frames += 1
            await self.send_json({"type": "tts.end", "utterance_id": utterance_id})
            elapsed_ms = (time.monotonic_ns() - self.started_ns) // 1_000_000
            LOG.info("response_end session=%s utterance=%s tts_frames=%d elapsed_ms=%d", self.session_id, utterance_id, frames, elapsed_ms)
            self._reset()
        except asyncio.CancelledError:
            raise
        except Exception as error:
            LOG.exception("response_failed session=%s utterance=%s", self.session_id, utterance_id)
            await self.send_json({"type": "error", "code": "PIPELINE_FAILED", "message": str(error), "recoverable": True})
            self._reset()

    def _reset(self) -> None:
        self.phase = Phase.READY
        self.utterance_id = None
        self.audio.clear()
        self.response_task = None
        self.audio_end_ns = 0
