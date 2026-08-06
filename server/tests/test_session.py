from __future__ import annotations

import asyncio
import json
from pathlib import Path

from gateway.adapters import MockSpeechToTextEngine, MockTextGenerationEngine, MockTextToSpeechEngine, TomlCharacterProvider
from gateway.config import load_config
from gateway.session import Phase, ProtocolError, VoiceSession


ROOT = Path(__file__).parents[2]


def make_session():
    sent: list[dict | bytes] = []

    async def send_json(message: dict):
        sent.append(message)

    async def send_binary(data: bytes):
        sent.append(data)

    config = load_config(ROOT / "config.example.toml")
    session = VoiceSession(
        config, MockSpeechToTextEngine(), MockTextGenerationEngine(), MockTextToSpeechEngine(),
        TomlCharacterProvider(config.profile_dir), send_json, send_binary,
    )
    return session, sent


def start_message():
    return json.dumps({
        "type": "session.start", "protocol_version": 1, "character": "skinnskattaren",
        "input_audio": {"codec": "pcm_s16le", "sample_rate": 16000, "channels": 1, "frame_ms": 20},
    })


def test_complete_mock_pipeline_streams_control_and_binary_audio():
    async def scenario():
        session, sent = make_session()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-1"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "u-1"}))
        await session.response_task
        types = [item["type"] for item in sent if isinstance(item, dict)]
        assert types == ["session.ready", "stt.partial", "stt.final", "assistant.text.delta", "assistant.text.delta", "assistant.text.delta", "assistant.text.final", "tts.start", "tts.end"]
        assert any(isinstance(item, bytes) and len(item) == 960 for item in sent)
        assert session.phase is Phase.READY
    asyncio.run(scenario())


def test_binary_audio_without_active_utterance_is_rejected():
    async def scenario():
        session, _ = make_session()
        await session.handle_text(start_message())
        try:
            await session.handle_binary(bytes(640))
            assert False, "ProtocolError expected"
        except ProtocolError as error:
            assert error.code == "UNEXPECTED_AUDIO"
    asyncio.run(scenario())


def test_second_utterance_is_rejected_while_recording():
    async def scenario():
        session, _ = make_session()
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-1"}))
        try:
            await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "u-2"}))
            assert False, "ProtocolError expected"
        except ProtocolError as error:
            assert error.code == "SESSION_BUSY"
    asyncio.run(scenario())
