from __future__ import annotations

import argparse
import asyncio
import json
import logging

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from .adapters import MockSpeechToTextEngine, MockTextGenerationEngine, MockTextToSpeechEngine, TomlCharacterProvider
from .config import GatewayConfig, load_config
from .session import ProtocolError, VoiceSession


LOG = logging.getLogger("euthervox.gateway")


async def handle_connection(socket: ServerConnection, config: GatewayConfig) -> None:
    async def send_json(message: dict) -> None:
        await socket.send(json.dumps(message, ensure_ascii=False, separators=(",", ":")))

    session = VoiceSession(
        config=config,
        stt=MockSpeechToTextEngine(),
        llm=MockTextGenerationEngine(),
        tts=MockTextToSpeechEngine(),
        characters=TomlCharacterProvider(config.profile_dir),
        send_json=send_json,
        send_binary=socket.send,
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
    except ConnectionClosed:
        pass
    finally:
        await session.close()
        LOG.info("session_closed session=%s", session.session_id)


async def run(config: GatewayConfig) -> None:
    async with serve(lambda socket: handle_connection(socket, config), config.host, config.port, max_size=2**20):
        LOG.info("listening ws://%s:%d", config.host, config.port)
        await asyncio.get_running_loop().create_future()


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
