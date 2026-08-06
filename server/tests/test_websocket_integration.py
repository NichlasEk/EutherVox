from __future__ import annotations

import asyncio
import json
from pathlib import Path

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve

from gateway.config import load_config
from gateway.main import handle_connection


ROOT = Path(__file__).parents[2]


def test_real_websocket_mock_round_trip():
    async def scenario():
        config = load_config(ROOT / "config.example.toml")
        async with serve(lambda socket: handle_connection(socket, config), "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with connect(f"ws://127.0.0.1:{port}") as socket:
                await socket.send(json.dumps({
                    "type": "session.start", "protocol_version": 1, "client_id": "test",
                    "node_name": "pytest", "room": "test", "character": "skinnskattaren",
                    "input_audio": {"codec": "pcm_s16le", "sample_rate": 16000, "channels": 1, "frame_ms": 20},
                }))
                ready = json.loads(await socket.recv())
                assert ready["type"] == "session.ready"
                await socket.send(json.dumps({"type": "audio.start", "utterance_id": "integration-1"}))
                await socket.send(bytes(640))
                await socket.send(bytes(640))
                await socket.send(json.dumps({"type": "audio.end", "utterance_id": "integration-1"}))

                control_types: list[str] = []
                binary_frames = 0
                while "tts.end" not in control_types:
                    message = await asyncio.wait_for(socket.recv(), timeout=2)
                    if isinstance(message, bytes):
                        binary_frames += 1
                    else:
                        control_types.append(json.loads(message)["type"])
                assert control_types[0:2] == ["stt.partial", "stt.final"]
                assert "assistant.text.final" in control_types
                assert binary_frames > 1
    asyncio.run(scenario())
