from __future__ import annotations

import asyncio
from dataclasses import replace
from pathlib import Path

from gateway.adapters import TomlCharacterProvider
from gateway.config import load_config
from gateway.tts import RoutedTextToSpeechEngine, SwedishTextNormalizer


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
