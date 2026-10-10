"""Bounded, CPU-only KittenTTS 2 adapter for Vox's existing PCM protocol."""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from pathlib import Path
import subprocess
import tempfile
import threading
import time

import numpy as np
import soundfile as sf
import soxr

LOG = logging.getLogger("euthervox.kitten")
SAMPLE_RATE = 22050


class BusyError(Exception):
    pass


def validate_request(payload):
    if not isinstance(payload, dict):
        raise ValueError("Expected an object")
    text = payload.get("text", "")
    if not isinstance(text, str) or not text.strip() or len(text) > 600:
        raise ValueError("Text must contain 1 to 600 characters")
    if payload.get("language_id", "sv") not in {"sv", "en"}:
        raise ValueError("Only Swedish and English are enabled")
    return text.strip()


class Runtime:
    def __init__(self, binary: Path, assets: Path, voice: str, threads=8, timeout=75):
        if not binary.is_file() or not (assets / "config.json").is_file():
            raise ValueError("Kitten binary or model assets are missing")
        self.binary, self.assets, self.voice = binary, assets, voice
        self.threads, self.timeout = threads, timeout
        self.lock = threading.Lock()

    def synthesize(self, text):
        if not self.lock.acquire(blocking=False):
            raise BusyError("Kitten is already rendering")
        started = time.monotonic()
        try:
            with tempfile.TemporaryDirectory(prefix="euthervox-kitten-") as directory:
                output, report = (
                    Path(directory) / "speech.wav",
                    Path(directory) / "report.json",
                )
                subprocess.run(
                    [
                        str(self.binary),
                        "--assets",
                        str(self.assets),
                        "--offline",
                        "--text",
                        text,
                        "--voice",
                        self.voice,
                        "--no-normalize",
                        "--threads",
                        str(self.threads),
                        "--decoder-threads",
                        str(self.threads),
                        "--preset",
                        "stable",
                        "--seed",
                        "1234",
                        "--max-tokens",
                        "800",
                        "--output",
                        str(output),
                        "--report",
                        str(report),
                    ],
                    check=True,
                    timeout=self.timeout,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                metrics = json.loads(report.read_text())
                if not metrics.get("chunks") or not all(
                    c.get("terminated") for c in metrics["chunks"]
                ):
                    raise ValueError("Kitten did not finish the requested text")
                if output.stat().st_size > 12_000_000:
                    raise ValueError("Audio exceeds the output limit")
                audio, rate = sf.read(output, dtype="float32")
                if (
                    rate != 24000
                    or audio.ndim != 1
                    or not len(audio)
                    or not np.isfinite(audio).all()
                ):
                    raise ValueError("Invalid Kitten audio")
                audio = soxr.resample(audio, rate, SAMPLE_RATE)
                pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()
                elapsed = int((time.monotonic() - started) * 1000)
                LOG.info(
                    "synthesized chars=%d elapsed_ms=%d audio_ms=%d",
                    len(text),
                    elapsed,
                    len(pcm) * 500 // SAMPLE_RATE,
                )
                return pcm, elapsed
        finally:
            self.lock.release()


def build_handler(runtime):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, payload):
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != "/health":
                self.send_error(404)
                return
            self.reply(
                200,
                {
                    "status": "ready",
                    "backend": "kitten-tts-2-cpp",
                    "device": "cpu",
                    "voice": runtime.voice,
                    "sample_rate": SAMPLE_RATE,
                    "busy": runtime.lock.locked(),
                },
            )

        def do_POST(self):
            if self.path != "/synthesize":
                self.send_error(404)
                return
            try:
                self.connection.settimeout(10)
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError("Invalid request length")
                text = validate_request(json.loads(self.rfile.read(length)))
                pcm, elapsed = runtime.synthesize(text)
            except BusyError:
                self.reply(429, {"detail": "Kitten is busy"})
                return
            except (ValueError, TypeError):
                self.reply(400, {"detail": "Invalid request or incomplete synthesis"})
                return
            except Exception:
                LOG.exception("kitten_synthesis_failed")
                self.reply(503, {"detail": "Kitten could not produce speech"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(pcm)))
            self.send_header("X-Sample-Rate", str(SAMPLE_RATE))
            self.send_header("X-Channels", "1")
            self.send_header("X-Sample-Format", "pcm_s16le")
            self.send_header("X-Render-Milliseconds", str(elapsed))
            self.end_headers()
            try:
                self.wfile.write(pcm)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, format, *args):
            LOG.info(format, *args)

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--voice", default="Bruno")
    parser.add_argument("--port", type=int, default=8794)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    runtime = Runtime(args.binary.resolve(), args.assets.resolve(), args.voice)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), build_handler(runtime))
    server.serve_forever()


if __name__ == "__main__":
    main()
