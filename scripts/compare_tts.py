from __future__ import annotations

import argparse
import asyncio
from dataclasses import replace
from pathlib import Path
import sys
import time
import wave


ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "server"))

from gateway.adapters import TomlCharacterProvider  # noqa: E402
from gateway.config import load_config  # noqa: E402
from gateway.tts import build_routed_tts  # noqa: E402


DEFAULT_TEXT = (
    "EutherVox använder AI för att söka på Wikipedia. "
    "Järnstaven vilar bredvid VAIO-brädan i Skinnskatteberg."
)


async def render(config_path: Path, output_dir: Path, text: str) -> None:
    config = load_config(config_path)
    tts = build_routed_tts(config.tts_settings)
    character = TomlCharacterProvider(config.profile_dir).get(config.default_character)
    voice_ids = getattr(tts, "voice_ids", (character.voice_id,))
    output_dir.mkdir(parents=True, exist_ok=True)
    for voice_id in voice_ids:
        selected = replace(character, voice_id=voice_id)
        started = time.monotonic()
        pcm = bytearray()
        first_frame_ms = None
        async for frame in tts.synthesize(text, selected, tts.sample_rate):
            if first_frame_ms is None:
                first_frame_ms = int((time.monotonic() - started) * 1000)
            pcm.extend(frame)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        target = output_dir / f"{voice_id}.wav"
        with wave.open(str(target), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(tts.sample_rate)
            wav.writeframes(pcm)
        audio_ms = len(pcm) * 1000 // (tts.sample_rate * 2)
        print(
            f"{voice_id}: first_frame={first_frame_ms} ms total={elapsed_ms} ms "
            f"audio={audio_ms} ms file={target}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Render the same Swedish phrase with every EutherVox voice")
    parser.add_argument("--config", type=Path, default=ROOT / "config.real-beta.example.toml")
    parser.add_argument("--output", type=Path, default=Path("/tmp/euthervox-tts-comparison"))
    parser.add_argument("--text", default=DEFAULT_TEXT)
    args = parser.parse_args()
    asyncio.run(render(args.config, args.output, args.text))


if __name__ == "__main__":
    main()
