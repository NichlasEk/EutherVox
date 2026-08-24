from __future__ import annotations

import argparse
import asyncio
import json
import logging
from http import HTTPStatus
from urllib.parse import parse_qs, urlsplit

from websockets.asyncio.server import ServerConnection, serve
from websockets.datastructures import Headers
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request, Response

from .adapters import TomlCharacterProvider, build_engines
from .cast import CastService
from .config import GatewayConfig, load_config
from .playlists import TomlPlaylistStore
from .session import ProtocolError, VoiceSession
from .youtube import YouTubePlaylistService
from .tool_planner import OllamaToolPlanner
from .tools import EutherVoxToolRegistry
from .wikipedia import WikipediaService
from .lighting import MagicHomeLightService
from .television import NecTvService
from .eutherpump import EutherPumpService
from .eutherwash import EutherWashService
from .washer_notifications import WasherCompletionMonitor


LOG = logging.getLogger("euthervox.gateway")


def configure_device_hotwords(
    stt: object,
    lights: MagicHomeLightService,
    television: NecTvService,
    eutherpump: EutherPumpService | None = None,
    eutherwash: EutherWashService | None = None,
) -> int:
    """Bias STT toward the names that are actually valid command targets."""
    add_hotwords = getattr(stt, "add_hotwords", None)
    if not add_hotwords:
        return 0
    phrases = ["EutherVox", "Skinnskattaren", "YouTube Music", "Wikipedia", "NEC-TV"]
    for target in (*lights.list_public(), *television.list_public()):
        phrases.extend((str(target.get("name", "")), str(target.get("room", ""))))
    if eutherpump is not None:
        for target in eutherpump.list_public():
            phrases.extend((target["name"], target["room"]))
    if eutherwash is not None and eutherwash.enabled:
        phrases.extend((
            "EutherWash", "tvättmaskinen", "tvättrapport", "tvätten är klar",
            "robotdammsugaren", "dammsugarrapport", "sidoborsten", "filtret",
        ))
    count = int(add_hotwords(phrases))
    LOG.info("stt_hotwords_configured phrases=%d", count)
    return count


async def handle_connection(socket: ServerConnection, config: GatewayConfig, engines=None, youtube=None, playlists=None, cast=None, tool_planner=None, wikipedia=None, lights=None, television=None, eutherpump=None, eutherwash=None, washer_notifications=None) -> None:
    async def send_json(message: dict) -> None:
        await socket.send(json.dumps(message, ensure_ascii=False, separators=(",", ":")))

    stt, llm, tts = engines or build_engines(config)
    session = VoiceSession(
        config=config,
        stt=stt,
        llm=llm,
        tts=tts,
        characters=TomlCharacterProvider(config.profile_dir),
        send_json=send_json,
        send_binary=socket.send,
        youtube=youtube,
        playlists=playlists,
        cast=cast,
        tool_planner=tool_planner,
        wikipedia=wikipedia,
        lights=lights,
        television=television,
        eutherpump=eutherpump,
        eutherwash=eutherwash,
        washer_notifications=washer_notifications,
        authenticated_user=socket.request.headers.get("X-Euther-User", "") if socket.request else "",
    )
    try:
        async for message in socket:
            try:
                if isinstance(message, bytes):
                    await session.handle_binary(message)
                else:
                    await session.handle_text(message)
            except ProtocolError as error:
                await send_json({"type": "error", "code": error.code, "message": str(error), "recoverable": error.recoverable})
                if not error.recoverable:
                    await socket.close(code=1002, reason=error.code)
                    break
    except ConnectionClosed as error:
        LOG.info(
            "connection_closed session=%s code=%s reason=%r phase=%s",
            session.session_id,
            error.code,
            error.reason,
            session.phase.value,
        )
    finally:
        await session.close()
        LOG.info("session_closed session=%s", session.session_id)


class OAuthHttpHandler:
    def __init__(self, youtube: YouTubePlaylistService):
        self.youtube = youtube

    async def __call__(self, _connection: ServerConnection, request: Request) -> Response | None:
        parsed = urlsplit(request.path)
        if parsed.path == "/euthervox/oauth/start":
            user = request.headers.get("X-Euther-User", "")
            if not self.youtube.can_use(user):
                return self._html(HTTPStatus.FORBIDDEN, "En verifierad EutherOxide-användare krävs.")
            try:
                return self._response(HTTPStatus.FOUND, b"", {"Location": self.youtube.authorization_url(user)})
            except Exception as error:
                return self._html(HTTPStatus.SERVICE_UNAVAILABLE, f"YouTube-kopplingen kan inte startas: {error}")
        if parsed.path == "/euthervox/oauth/callback":
            user = request.headers.get("X-Euther-User", "")
            if not self.youtube.can_use(user):
                return self._html(HTTPStatus.FORBIDDEN, "En verifierad EutherOxide-användare krävs.")
            query = parse_qs(parsed.query)
            if query.get("error"):
                return self._html(HTTPStatus.BAD_REQUEST, f"Google nekade kopplingen: {query['error'][0]}")
            try:
                await self.youtube.complete_authorization(query.get("code", [""])[0], query.get("state", [""])[0], user)
                return self._html(HTTPStatus.OK, "YouTube är kopplat. Du kan återvända till EutherVox.")
            except Exception as error:
                LOG.exception("youtube_oauth_failed")
                return self._html(HTTPStatus.BAD_REQUEST, f"YouTube-kopplingen misslyckades: {error}")
        if parsed.path == "/euthervox/oauth/status":
            allowed = self.youtube.can_use(request.headers.get("X-Euther-User", ""))
            user = request.headers.get("X-Euther-User", "")
            body = json.dumps({"configured": self.youtube.configured, "authorized": self.youtube.authorized(user) if allowed else False}).encode()
            return self._response(HTTPStatus.OK, body, {"Content-Type": "application/json", "Cache-Control": "no-store"})
        return None

    def _html(self, status: HTTPStatus, message: str) -> Response:
        escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        body = f"<!doctype html><meta charset=utf-8><title>EutherVox</title><body style='font-family:sans-serif;padding:2rem;background:#f2ebdd;color:#254c3a'><h1>EutherVox</h1><p>{escaped}</p></body>".encode()
        return self._response(status, body, {"Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store"})

    @staticmethod
    def _response(status: HTTPStatus, body: bytes, extra_headers: dict[str, str]) -> Response:
        headers = Headers(extra_headers)
        headers["Content-Length"] = str(len(body))
        return Response(status.value, status.phrase, headers, body)


async def run(config: GatewayConfig) -> None:
    engines = build_engines(config)
    youtube = YouTubePlaylistService(config.youtube_settings, config.config_dir)
    playlists = TomlPlaylistStore(config.playlist_settings, config.config_dir)
    cast = CastService(config.cast_settings)
    wikipedia = WikipediaService(config.wikipedia_settings)
    lights = MagicHomeLightService(config.light_settings, config.config_dir)
    television = NecTvService(config.television_settings, config.config_dir)
    eutherpump = EutherPumpService(config.eutherpump_settings)
    eutherwash = EutherWashService(config.eutherwash_settings)
    washer_notifications = WasherCompletionMonitor(eutherwash, config.eutherwash_settings, config.config_dir)
    configure_device_hotwords(engines[0], lights, television, eutherpump, eutherwash)
    tool_registry = EutherVoxToolRegistry(cast, lights, television, eutherpump, eutherwash)
    tool_planner = None
    if bool(config.mcp_settings.get("enabled", False)) and config.llm_provider == "ollama":
        tool_planner = OllamaToolPlanner(
            tool_registry,
            base_url=str(config.llm_settings.get("base_url", "http://127.0.0.1:11434")),
            model=str(config.llm_settings.get("model", "qwen3:4b-instruct")),
            timeout_seconds=float(config.mcp_settings.get("planner_timeout_seconds", 8.0)),
        )
    for name, engine in (("stt", engines[0]), ("llm", engines[1])):
        warmup = getattr(engine, "warmup", None)
        if warmup:
            await warmup()
            LOG.info("engine_warm name=%s", name)
    LOG.info(
        "engines_ready stt=%s llm=%s tts=%s output_sample_rate=%d",
        config.stt_provider,
        config.llm_provider,
        config.tts_provider,
        engines[2].sample_rate,
    )
    oauth_http = OAuthHttpHandler(youtube)
    await washer_notifications.start()
    try:
        async with serve(
            lambda socket: handle_connection(socket, config, engines, youtube, playlists, cast, tool_planner, wikipedia, lights, television, eutherpump, eutherwash, washer_notifications),
            config.host,
            config.port,
            max_size=2**20,
            process_request=oauth_http,
        ):
            LOG.info("listening ws://%s:%d", config.host, config.port)
            await asyncio.get_running_loop().create_future()
    finally:
        await washer_notifications.stop()


def cli() -> None:
    parser = argparse.ArgumentParser(description="EutherVox local gateway")
    parser.add_argument("--config", default="config.toml")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    try:
        asyncio.run(run(load_config(args.config)))
    except KeyboardInterrupt:
        LOG.info("gateway stopped")


if __name__ == "__main__":
    cli()
