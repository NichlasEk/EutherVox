from __future__ import annotations

import asyncio
from dataclasses import replace
from pathlib import Path

import httpx

from gateway.adapters import TomlCharacterProvider
from gateway.config import load_config
from gateway.tts import HttpPcmTextToSpeechEngine, RoutedTextToSpeechEngine, SwedishTextNormalizer


ROOT = Path(__file__).parents[2]


class RecordingTts:
    sample_rate = 22_050

    def __init__(self, frames: tuple[bytes, ...] = (b"voice",), error: Exception | None = None):
        self.frames = frames
        self.error = error
        self.texts: list[str] = []

    async def synthesize(self, text, character, sample_rate):
        self.texts.append(text)
        if self.error:
            raise self.error
        for frame in self.frames:
            yield frame


def character():
    config = load_config(ROOT / "config.example.toml")
    return TomlCharacterProvider(config.profile_dir).get("skinnskattaren")


def test_swedish_normalizer_uses_character_pronunciation_dictionary():
    normalized = SwedishTextNormalizer().normalize(
        "EutherVox använder AI, TTS och en VAIO.", character()
    )

    assert normalized == "Euter Vox använder A I, T T S och en vajo."


def test_router_selects_requested_voice_and_normalizes_text():
    async def scenario():
        nst = RecordingTts((b"nst",))
        lisa = RecordingTts((b"lisa",))
        router = RoutedTextToSpeechEngine(
            {"piper-nst": nst, "piper-lisa": lisa}, "piper-nst", "piper-nst"
        )

        frames = [
            frame
            async for frame in router.synthesize(
                "AI i EutherVox", replace(character(), voice_id="piper-lisa"), 22_050
            )
        ]

        assert frames == [b"lisa"]
        assert not nst.texts
        assert lisa.texts == ["A I i Euter Vox"]

    asyncio.run(scenario())


def test_router_falls_back_if_quality_voice_fails_before_audio():
    async def scenario():
        nst = RecordingTts((b"fallback",))
        chatterbox = RecordingTts(error=RuntimeError("worker unavailable"))
        router = RoutedTextToSpeechEngine(
            {"piper-nst": nst, "chatterbox": chatterbox}, "piper-nst", "piper-nst"
        )

        frames = [
            frame
            async for frame in router.synthesize(
                "Skogen väntar.", replace(character(), voice_id="chatterbox"), 22_050
            )
        ]

        assert frames == [b"fallback"]
        assert chatterbox.texts == ["Skogen väntar."]
        assert nst.texts == ["Skogen väntar."]

    asyncio.run(scenario())


def test_router_can_leave_quality_voice_text_unmodified():
    async def scenario():
        nst = RecordingTts()
        chatterbox = RecordingTts()
        router = RoutedTextToSpeechEngine(
            {"piper-nst": nst, "chatterbox": chatterbox},
            "piper-nst",
            "piper-nst",
            normalized_voices={"piper-nst"},
        )

        async for _frame in router.synthesize(
            "AI i EutherVox", replace(character(), voice_id="chatterbox"), 22_050
        ):
            pass

        assert chatterbox.texts == ["AI i EutherVox"]

    asyncio.run(scenario())


def test_router_rejects_unknown_voice():
    router = RoutedTextToSpeechEngine(
        {"piper-nst": RecordingTts()}, "piper-nst", "piper-nst"
    )

    try:
        router.resolve_voice("okänd")
        assert False, "ValueError expected"
    except ValueError as error:
        assert "Okänd röst" in str(error)


def test_http_pcm_engine_frames_a_streamed_response_without_buffering_whole_audio():
    frame_bytes = 22_050 * 2 * 20 // 1000
    requests: list[dict] = []

    class TwoChunkStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"a" * frame_bytes
            yield b"b" * 17

    async def handler(request: httpx.Request) -> httpx.Response:
        import json

        requests.append(json.loads(request.content))
        return httpx.Response(
            200,
            headers={"X-Sample-Rate": "22050"},
            stream=TwoChunkStream(),
        )

    async def scenario():
        engine = HttpPcmTextToSpeechEngine(
            "http://moss.test",
            22_050,
            profile="christian",
            transport=httpx.MockTransport(handler),
        )
        return [
            frame
            async for frame in engine.synthesize(
                "Skogen väntar.", replace(character(), voice_id="moss-nano"), 22_050
            )
        ]

    frames = asyncio.run(scenario())
    assert frames[0] == b"a" * frame_bytes
    assert frames[1] == b"b" * 17 + bytes(frame_bytes - 17)
    assert requests == [{"text": "Skogen väntar.", "language_id": "sv", "voice_id": "christian"}]


def test_christian_is_a_separate_character_with_his_own_voice():
    config = load_config(ROOT / "config.real-beta.example.toml")
    christian = TomlCharacterProvider(config.profile_dir).get("christian-grosshandlare")

    assert christian.display_name == "Christian Grosshandlare"
    assert christian.voice_id == "moss-christian"
    assert "dansk grosshandlare" in christian.description
