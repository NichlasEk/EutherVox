from __future__ import annotations

from dataclasses import dataclass
import asyncio
import math
from pathlib import Path
import struct
import tomllib
from typing import AsyncIterator, Protocol


@dataclass(frozen=True)
class Character:
    name: str
    display_name: str
    description: str
    voice_id: str
    speed: float
    pitch: float
    max_initial_sentence_words: int


class SpeechToTextEngine(Protocol):
    async def transcribe(self, pcm: bytes, sample_rate: int) -> str: ...


class TextGenerationEngine(Protocol):
    async def generate(self, transcript: str, character: Character) -> AsyncIterator[str]: ...


class TextToSpeechEngine(Protocol):
    async def synthesize(self, text: str, character: Character, sample_rate: int) -> AsyncIterator[bytes]: ...


class CharacterProvider(Protocol):
    def get(self, name: str) -> Character: ...


class TomlCharacterProvider:
    def __init__(self, profile_dir: Path):
        self.profile_dir = profile_dir

    def get(self, name: str) -> Character:
        safe_name = name.lower().replace("/", "").replace("..", "")
        with (self.profile_dir / f"{safe_name}.toml").open("rb") as source:
            data = tomllib.load(source)
        return Character(
            name=data["id"]["name"],
            display_name=data["id"]["display_name"],
            description=data["personality"]["description"].strip(),
            voice_id=data["voice"]["voice_id"],
            speed=float(data["voice"]["speed"]),
            pitch=float(data["voice"]["pitch"]),
            max_initial_sentence_words=int(data["behavior"]["max_initial_sentence_words"]),
        )


class MockSpeechToTextEngine:
    async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
        await asyncio.sleep(0.08)
        return "Var ligger min lödkolv?"


class MockTextGenerationEngine:
    async def generate(self, transcript: str, character: Character) -> AsyncIterator[str]:
        for token in ("Järnstaven ", "vilar i garaget, ", "bredvid VAIO-brädan."):
            await asyncio.sleep(0.035)
            yield token


class MockTextToSpeechEngine:
    """Streams a recognizable test tone; replace through the adapter interface."""

    async def synthesize(self, text: str, character: Character, sample_rate: int) -> AsyncIterator[bytes]:
        frame_samples = sample_rate // 50
        duration_frames = 35
        frequency = 330.0
        for frame_index in range(duration_frames):
            samples = bytearray()
            for offset in range(frame_samples):
                position = frame_index * frame_samples + offset
                envelope = min(1.0, position / (sample_rate * 0.03))
                value = int(6000 * envelope * math.sin(2 * math.pi * frequency * position / sample_rate))
                samples.extend(struct.pack("<h", value))
            yield bytes(samples)
            await asyncio.sleep(0.012)

