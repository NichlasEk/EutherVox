from __future__ import annotations

import asyncio
import ctypes
from dataclasses import dataclass
import importlib.util
import json
import math
from pathlib import Path
import re
import struct
import tomllib
from typing import AsyncIterator, Protocol

import httpx


@dataclass(frozen=True)
class Character:
    name: str
    display_name: str
    description: str
    voice_id: str
    speed: float
    pitch: float
    max_initial_sentence_words: int
    music_acknowledgements: tuple[str, ...]
    music_control_acknowledgements: dict[str, str]


class SpeechToTextEngine(Protocol):
    async def transcribe(self, pcm: bytes, sample_rate: int) -> str: ...


class TextGenerationEngine(Protocol):
    async def generate(self, transcript: str, character: Character) -> AsyncIterator[str]: ...


class TextToSpeechEngine(Protocol):
    sample_rate: int
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
            music_acknowledgements=tuple(str(item) for item in data.get("music", {}).get("acknowledgements", [])),
            music_control_acknowledgements={
                str(key): str(value) for key, value in data.get("music", {}).get("control_acknowledgements", {}).items()
            },
        )


def render_music_acknowledgement(character: Character, query: str, room: str) -> str:
    templates = character.music_acknowledgements or ("{query}. Jag spelar den i {room}.",)
    selector = sum(query.casefold().encode("utf-8")) % len(templates)
    return templates[selector].replace("{query}", query).replace("{room}", room)


def render_music_control_acknowledgement(character: Character, command: str, room: str) -> str:
    defaults = {
        "pause": "Jag pausar musiken i {room}.",
        "resume": "Jag fortsätter musiken i {room}.",
        "stop": "Jag stoppar musiken i {room}.",
    }
    template = character.music_control_acknowledgements.get(command, defaults[command])
    return template.replace("{room}", room)


class MockSpeechToTextEngine:
    initial_partial = "var ligger min"

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

    sample_rate = 24000

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


class FasterWhisperSpeechToTextEngine:
    """Multilingual local STT. Language is pinned to Swedish to prevent translation."""

    def __init__(
        self,
        model: str = "base",
        device: str = "cpu",
        compute_type: str = "int8",
        language: str = "sv",
        cpu_threads: int = 8,
        download_root: str | None = None,
        hotwords: str = "",
    ):
        if device == "cuda":
            _preload_cuda_runtime()
        from faster_whisper import WhisperModel

        self.language = language
        self.hotwords = hotwords
        self._lock = asyncio.Lock()
        model_source = _cached_whisper_snapshot(model, download_root) or model
        self.model = WhisperModel(
            model_source,
            device=device,
            compute_type=compute_type,
            cpu_threads=cpu_threads,
            download_root=download_root,
        )

    async def transcribe(self, pcm: bytes, sample_rate: int) -> str:
        if sample_rate != 16000:
            raise ValueError(f"faster-whisper adapter expects 16000 Hz, got {sample_rate}")
        async with self._lock:
            return await asyncio.to_thread(self._transcribe_sync, pcm)

    async def warmup(self) -> None:
        await self.transcribe(bytes(3200), 16000)

    def _transcribe_sync(self, pcm: bytes) -> str:
        import numpy as np

        waveform = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _ = self.model.transcribe(
            waveform,
            language=self.language,
            task="transcribe",
            beam_size=1,
            best_of=1,
            temperature=0,
            condition_on_previous_text=False,
            without_timestamps=True,
            hotwords=self.hotwords or None,
        )
        return " ".join(segment.text.strip() for segment in segments).strip()


def _preload_cuda_runtime() -> None:
    """Expose pip-installed CUDA 12 libraries to CTranslate2 without system changes."""
    nvidia = importlib.util.find_spec("nvidia")
    if nvidia is None or not nvidia.submodule_search_locations:
        return
    root = Path(next(iter(nvidia.submodule_search_locations)))
    libraries = [
        root / "cublas/lib/libcublasLt.so.12",
        root / "cublas/lib/libcublas.so.12",
        root / "cudnn/lib/libcudnn.so.9",
    ]
    for library in libraries:
        if library.exists():
            ctypes.CDLL(str(library), mode=ctypes.RTLD_GLOBAL)


def _cached_whisper_snapshot(model: str, download_root: str | None) -> str | None:
    """Use an already downloaded Hugging Face snapshot without a network probe."""
    if not download_root or "/" in model or Path(model).exists():
        return None
    repository = Path(download_root) / f"models--Systran--faster-whisper-{model}"
    reference = repository / "refs/main"
    if not reference.is_file():
        return None
    snapshot = repository / "snapshots" / reference.read_text(encoding="utf-8").strip()
    return str(snapshot) if (snapshot / "model.bin").is_file() else None


class OllamaTextGenerationEngine:
    def __init__(self, base_url: str, model: str, timeout_seconds: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds

    async def warmup(self) -> None:
        timeout = httpx.Timeout(self.timeout_seconds, connect=5.0)
        payload = {"model": self.model, "keep_alive": "30m"}
        async with httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
            response = await client.post(f"{self.base_url}/api/generate", json=payload)
            response.raise_for_status()

    async def generate(self, transcript: str, character: Character) -> AsyncIterator[str]:
        system = (
            f"Du är {character.display_name}. {character.description}\n"
            "Svara alltid på tydlig svenska. Var stämningsfull men aldrig gåtfull när viktig information ges. "
            f"Första meningen får innehålla högst {character.max_initial_sentence_words} ord. "
            "Svara kort, konkret och utan metakommentarer. "
            "Du har ingen kunskap om användarens saker eller deras platser utöver det som står i frågan. "
            "Hitta aldrig på en sakuppgift eller plats. Om en plats saknas ska du uttryckligen säga att du inte vet var saken ligger ännu och be om relevant information."
        )
        payload = {
            "model": self.model,
            "stream": True,
            "keep_alive": "30m",
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": transcript},
            ],
            "options": {"temperature": 0.65, "num_ctx": 4096, "num_predict": 96},
        }
        timeout = httpx.Timeout(self.timeout_seconds, connect=5.0)
        async with httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
            async with client.stream("POST", f"{self.base_url}/api/chat", json=payload) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    message = json.loads(line)
                    piece = message.get("message", {}).get("content", "")
                    if piece:
                        yield piece
                    if message.get("done"):
                        break


class PiperTextToSpeechEngine:
    """Fast CPU TTS. Produces paced 20 ms PCM frames so Android can stream safely."""

    def __init__(self, model_path: str | Path, frame_ms: int = 20):
        self.model_path = Path(model_path)
        config_path = Path(f"{self.model_path}.json")
        with config_path.open("r", encoding="utf-8") as source:
            self.sample_rate = int(json.load(source)["audio"]["sample_rate"])
        self.frame_ms = frame_ms
        from piper import PiperVoice

        self._voice = PiperVoice.load(self.model_path)
        self._lock = asyncio.Lock()

    async def synthesize(self, text: str, character: Character, sample_rate: int) -> AsyncIterator[bytes]:
        frame_bytes = self.sample_rate * 2 * self.frame_ms // 1000
        sentences = [part.strip() for part in re.split(r"(?<=[.!?])\s+", text) if part.strip()]
        for sentence in sentences or [text]:
            async with self._lock:
                audio = await asyncio.to_thread(self._render_sync, sentence, character)
            for index, offset in enumerate(range(0, len(audio), frame_bytes)):
                frame = audio[offset : offset + frame_bytes]
                if len(frame) < frame_bytes:
                    frame += bytes(frame_bytes - len(frame))
                yield frame
                if index >= 5:
                    await asyncio.sleep(self.frame_ms / 1000)

    def _render_sync(self, text: str, character: Character) -> bytes:
        from piper import SynthesisConfig
        length_scale = 1.0 / max(0.5, character.speed)
        config = SynthesisConfig(length_scale=length_scale)
        return b"".join(chunk.audio_int16_bytes for chunk in self._voice.synthesize(text, config))


def build_engines(config):
    if config.stt_provider == "mock":
        stt = MockSpeechToTextEngine()
    elif config.stt_provider == "faster_whisper":
        settings = config.stt_settings
        stt = FasterWhisperSpeechToTextEngine(
            model=str(settings.get("model", "base")),
            device=str(settings.get("device", "cpu")),
            compute_type=str(settings.get("compute_type", "int8")),
            language=str(settings.get("language", "sv")),
            cpu_threads=int(settings.get("cpu_threads", 8)),
            download_root=settings.get("download_root"),
            hotwords=str(settings.get("hotwords", "")),
        )
    else:
        raise ValueError(f"Unsupported STT provider: {config.stt_provider}")

    if config.llm_provider == "mock":
        llm = MockTextGenerationEngine()
    elif config.llm_provider == "ollama":
        settings = config.llm_settings
        llm = OllamaTextGenerationEngine(
            base_url=str(settings.get("base_url", "http://127.0.0.1:11434")),
            model=str(settings["model"]),
            timeout_seconds=float(settings.get("timeout_seconds", 30)),
        )
    else:
        raise ValueError(f"Unsupported LLM provider: {config.llm_provider}")

    if config.tts_provider == "mock":
        tts = MockTextToSpeechEngine()
    elif config.tts_provider == "piper":
        settings = config.tts_settings
        tts = PiperTextToSpeechEngine(str(settings["model_path"]), int(settings.get("frame_ms", 20)))
    else:
        raise ValueError(f"Unsupported TTS provider: {config.tts_provider}")
    return stt, llm, tts
