from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
import logging
import re

import httpx

from .adapters import Character, PiperTextToSpeechEngine, TextToSpeechEngine


LOG = logging.getLogger("euthervox.tts")


class SwedishTextNormalizer:
    """Small, explicit pronunciation layer shared by all Swedish TTS backends."""

    _WHITESPACE = re.compile(r"\s+")

    def normalize(self, text: str, character: Character) -> str:
        normalized = text
        for written, spoken in sorted(
            character.pronunciations.items(), key=lambda item: len(item[0]), reverse=True
        ):
            normalized = re.sub(
                rf"(?<!\w){re.escape(written)}(?!\w)",
                lambda _match, replacement=spoken: replacement,
                normalized,
                flags=re.IGNORECASE,
            )
        return self._WHITESPACE.sub(" ", normalized).strip()


class HttpPcmTextToSpeechEngine:
    """Client for an isolated TTS worker returning mono signed 16-bit PCM."""

    def __init__(
        self,
        base_url: str,
        sample_rate: int,
        timeout_seconds: float = 60.0,
        profile: str = "",
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.sample_rate = sample_rate
        self.timeout_seconds = timeout_seconds
        self.profile = profile
        self.transport = transport

    async def synthesize(
        self, text: str, character: Character, sample_rate: int
    ) -> AsyncIterator[bytes]:
        if sample_rate != self.sample_rate:
            raise ValueError(f"HTTP TTS expects {self.sample_rate} Hz, got {sample_rate}")
        timeout = httpx.Timeout(self.timeout_seconds, connect=min(5.0, self.timeout_seconds))
        async with httpx.AsyncClient(
            timeout=timeout, trust_env=False, transport=self.transport
        ) as client:
            async with client.stream(
                "POST",
                f"{self.base_url}/synthesize",
                json={
                    "text": text,
                    "language_id": character.response_language,
                    "voice_id": self.profile,
                    "speed": character.speed,
                },
            ) as response:
                response.raise_for_status()
                returned_rate = int(response.headers.get("X-Sample-Rate", self.sample_rate))
                if returned_rate != self.sample_rate:
                    raise RuntimeError(
                        f"TTS worker returned {returned_rate} Hz, expected {self.sample_rate} Hz"
                    )
                frame_bytes = self.sample_rate * 2 * 20 // 1000
                buffered = bytearray()
                async for chunk in response.aiter_bytes():
                    buffered.extend(chunk)
                    while len(buffered) >= frame_bytes:
                        yield bytes(buffered[:frame_bytes])
                        del buffered[:frame_bytes]
                        await asyncio.sleep(0)
                if buffered:
                    yield bytes(buffered) + bytes(frame_bytes - len(buffered))


class RoutedTextToSpeechEngine:
    """Selects a configured voice per WebSocket session with a safe fallback."""

    def __init__(
        self,
        voices: dict[str, TextToSpeechEngine],
        default_voice: str,
        fallback_voice: str,
        normalizer: SwedishTextNormalizer | None = None,
        normalized_voices: set[str] | None = None,
    ):
        if default_voice not in voices:
            raise ValueError(f"Unknown default TTS voice: {default_voice}")
        if fallback_voice not in voices:
            raise ValueError(f"Unknown fallback TTS voice: {fallback_voice}")
        sample_rates = {engine.sample_rate for engine in voices.values()}
        if len(sample_rates) != 1:
            raise ValueError("All routed TTS voices must use the same output sample rate")
        self.voices = voices
        self.default_voice = default_voice
        self.fallback_voice = fallback_voice
        self.sample_rate = sample_rates.pop()
        self.normalizer = normalizer or SwedishTextNormalizer()
        self.normalized_voices = normalized_voices if normalized_voices is not None else set(voices)

    @property
    def voice_ids(self) -> tuple[str, ...]:
        return tuple(self.voices)

    def resolve_voice(self, requested: str) -> str:
        voice_id = requested or self.default_voice
        if voice_id not in self.voices:
            raise ValueError(f"Okänd röst: {voice_id}")
        return voice_id

    async def synthesize(
        self, text: str, character: Character, sample_rate: int
    ) -> AsyncIterator[bytes]:
        voice_id = self.resolve_voice(character.voice_id)
        selected_text = (
            self.normalizer.normalize(text, character)
            if voice_id in self.normalized_voices else text
        )
        emitted = False
        try:
            async for frame in self.voices[voice_id].synthesize(
                selected_text, character, sample_rate
            ):
                emitted = True
                yield frame
        except Exception as error:
            if emitted or voice_id == self.fallback_voice:
                raise
            LOG.warning(
                "tts_voice_failed voice=%s fallback=%s error=%s",
                voice_id,
                self.fallback_voice,
                error,
            )
            fallback_text = (
                self.normalizer.normalize(text, character)
                if self.fallback_voice in self.normalized_voices else text
            )
            async for frame in self.voices[self.fallback_voice].synthesize(
                fallback_text, character, sample_rate
            ):
                yield frame

    async def synthesize_fast(
        self, text: str, character: Character, sample_rate: int
    ) -> AsyncIterator[bytes]:
        """Use the low-latency fallback voice for short device acknowledgements."""
        selected_text = (
            self.normalizer.normalize(text, character)
            if self.fallback_voice in self.normalized_voices else text
        )
        async for frame in self.voices[self.fallback_voice].synthesize(
            selected_text, character, sample_rate
        ):
            yield frame


def build_routed_tts(settings: dict) -> RoutedTextToSpeechEngine:
    voices: dict[str, TextToSpeechEngine] = {}
    normalized_voices: set[str] = set()
    frame_ms = int(settings.get("frame_ms", 20))
    for voice_id, voice_settings in dict(settings.get("voices", {})).items():
        provider = str(voice_settings.get("provider", "piper"))
        if provider == "piper":
            voices[voice_id] = PiperTextToSpeechEngine(
                str(voice_settings["model_path"]), frame_ms
            )
        elif provider == "http_pcm":
            voices[voice_id] = HttpPcmTextToSpeechEngine(
                str(voice_settings["base_url"]),
                int(voice_settings.get("sample_rate", 22050)),
                float(voice_settings.get("timeout_seconds", 60)),
                str(voice_settings.get("profile", "")),
            )
        else:
            raise ValueError(f"Unsupported routed TTS provider: {provider}")
        if bool(voice_settings.get("normalize_pronunciation", provider == "piper")):
            normalized_voices.add(voice_id)
    default_voice = str(settings.get("default_voice", "piper-nst"))
    return RoutedTextToSpeechEngine(
        voices,
        default_voice,
        str(settings.get("fallback_voice", default_voice)),
        normalized_voices=normalized_voices,
    )
