"""Replay original OmniVoice references through the existing MOSS CPU adapter."""

from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import logging
from pathlib import Path
import time
import wave


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--experiment", type=Path, required=True)
    parser.add_argument("--moss-model", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    data = json.loads((args.experiment / "design.json").read_text())
    if (args.experiment / "moss.json").exists():
        raise SystemExit(
            "MOSS results already exist; do not overwrite a listening experiment"
        )
    worker = Path(__file__).parents[1] / "moss-worker/worker.py"
    spec = importlib.util.spec_from_file_location("vox_moss_worker", worker)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    references = {}
    for row in data["candidates"]:
        path = (args.experiment / row["reference"]).resolve()
        if (
            path.parent != args.experiment.resolve()
            or hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]
        ):
            raise ValueError("Reference identity or checksum mismatch")
        references[row["name"]] = path
    started = time.monotonic()
    runtime = module.MossRuntime(
        args.moss_model,
        args.experiment / "moss-runtime",
        None,
        "",
        8,
        22050,
        references,
        {r["name"]: r["seed"] for r in data["candidates"]},
    )
    report = {
        "backend": "existing-vox-moss-onnx-cpu",
        "threads": 8,
        "load_seconds": round(time.monotonic() - started, 3),
        "test_text": data["test_text"],
        "candidates": [],
    }
    for row in data["candidates"]:
        output = args.experiment / f'{row["name"]}-moss.wav'
        if output.exists():
            raise ValueError("Output already exists")
        start = time.monotonic()
        first = None
        frames = []
        for frame in runtime.synthesize(data["test_text"], row["name"]):
            if first is None:
                first = time.monotonic() - start
            frames.append(frame)
        elapsed = time.monotonic() - start
        pcm = b"".join(frames)
        if not pcm or len(pcm) > 22050 * 2 * 60:
            raise ValueError("Empty or oversized output")
        with wave.open(str(output), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(22050)
            out.writeframes(pcm)
        item = {
            "name": row["name"],
            "file": output.name,
            "first_audio_seconds": round(first, 3),
            "wall_seconds": round(elapsed, 3),
            "audio_seconds": len(pcm) / 44100,
            "reference_sha256": row["sha256"],
        }
        report["candidates"].append(item)
        (args.experiment / "moss.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2)
        )
        print(json.dumps(item), flush=True)


if __name__ == "__main__":
    main()
