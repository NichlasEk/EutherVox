"""Create two original Swedish references, then clone them with OmniVoice."""

from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import time

REFERENCE_TEXT = "Jag är Siaren. Jag söker samband mellan systemen och berättar när något behöver din uppmärksamhet."
TEST_TEXT = "Det här är ett röstprov. Jag skiljer mellan det jag vet och det jag bara misstänker."
DESIGNS = [
    ("siaren-djup", "male, middle-aged, low pitch", 302041),
    ("siaren-klar", "female, middle-aged, moderate pitch", 302042),
]
MODEL_REVISION = "c5fdb5ccb189668d56333f77ba2629f4cd7535f4"


def validate_audio(audio, sample_rate):
    import numpy as np

    audio = np.asarray(audio)
    if audio.ndim != 1 or not np.isfinite(audio).all():
        raise ValueError("Invalid waveform")
    duration = len(audio) / sample_rate
    if not 2 <= duration <= 20:
        raise ValueError("Reference duration outside the expected range")
    if np.sqrt(np.mean(audio.astype(float) ** 2)) < 0.001:
        raise ValueError("Silent waveform")
    return audio


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", choices=["cuda", "cpu"], default="cuda")
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Output already exists; choose a new experiment directory")
    import torch
    import soundfile as sf
    from omnivoice import OmniVoice
    from omnivoice.utils.common import fix_random_seed

    torch.set_num_threads(4)
    if args.device == "cuda":
        free, total = torch.cuda.mem_get_info()
        if free < 6 * 1024**3:
            raise SystemExit(
                "Less than 6 GiB GPU memory free; no other service was stopped"
            )
        torch.cuda.set_per_process_memory_fraction(min(0.22, (free - 1024**3) / total))
    args.output.mkdir(parents=True)
    started = time.monotonic()
    model = OmniVoice.from_pretrained(
        str(args.model_root / "weights"),
        device_map=args.device,
        dtype=torch.float16 if args.device == "cuda" else torch.float32,
        attn_implementation="sdpa",
        load_asr=False,
        local_files_only=True,
    )
    result = {
        "model": "k2-fsa/OmniVoice",
        "model_revision": MODEL_REVISION,
        "device": args.device,
        "load_seconds": round(time.monotonic() - started, 3),
        "reference_text": REFERENCE_TEXT,
        "test_text": TEST_TEXT,
        "sample_rate": model.sampling_rate,
        "candidates": [],
    }
    for name, instructions, seed in DESIGNS:
        fix_random_seed(seed)
        start = time.monotonic()
        reference = model.generate(
            text=REFERENCE_TEXT,
            language="sv",
            instruct=instructions,
            duration=8.0,
            num_step=32,
            normalize_text=False,
        )[0]
        reference = validate_audio(reference, model.sampling_rate)
        reference_path = args.output / f"{name}-reference.wav"
        sf.write(reference_path, reference, model.sampling_rate, subtype="PCM_16")
        row = {
            "name": name,
            "instructions": instructions,
            "seed": seed,
            "reference": reference_path.name,
            "reference_seconds": len(reference) / model.sampling_rate,
            "design_seconds": round(time.monotonic() - start, 3),
            "sha256": hashlib.sha256(reference_path.read_bytes()).hexdigest(),
        }
        fix_random_seed(seed)
        start = time.monotonic()
        clone = model.generate(
            text=TEST_TEXT,
            language="sv",
            ref_audio=str(reference_path),
            ref_text=REFERENCE_TEXT,
            num_step=32,
            normalize_text=False,
        )[0]
        clone = validate_audio(clone, model.sampling_rate)
        clone_path = args.output / f"{name}-omnivoice.wav"
        sf.write(clone_path, clone, model.sampling_rate, subtype="PCM_16")
        row.update(
            omnivoice_clone=clone_path.name,
            clone_seconds=round(time.monotonic() - start, 3),
            clone_audio_seconds=len(clone) / model.sampling_rate,
        )
        result["candidates"].append(row)
        (args.output / "design.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2)
        )
        print(json.dumps(row, ensure_ascii=False), flush=True)
    if args.device == "cuda":
        result["gpu_peak_allocated_mib"] = round(
            torch.cuda.max_memory_allocated() / 1024**2
        )
    (args.output / "design.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2)
    )


if __name__ == "__main__":
    main()
