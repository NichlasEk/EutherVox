"""Local OmniVoice PCM adapter with bounded GPU process lifetime."""

from __future__ import annotations

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import multiprocessing
from pathlib import Path
import select
import socket
import threading
import time

LOG = logging.getLogger("euthervox.omnivoice")
SAMPLE_RATE = 22050
PROFILES = {"siaren-djup", "siaren-klar"}


class BusyError(Exception):
    pass


def validate_request(payload):
    if not isinstance(payload, dict):
        raise ValueError("Expected an object")
    text = payload.get("text", "")
    if not isinstance(text, str) or not text.strip() or len(text) > 600:
        raise ValueError("Text must contain 1 to 600 characters")
    profile = payload.get("voice_id")
    if not isinstance(profile, str) or profile not in PROFILES:
        raise ValueError("Unknown voice")
    if payload.get("language_id", "sv") != "sv":
        raise ValueError("These references are Swedish")
    return text.strip(), profile


def load_profiles(directory):
    data = json.loads((directory / "manifest.json").read_text())
    if set(data) != PROFILES:
        raise ValueError("Both approved references are required")
    for row in data.values():
        path = (directory / row["file"]).resolve()
        if path.parent != directory.resolve() or not row["text"]:
            raise ValueError("Invalid reference")
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError("Reference checksum mismatch")
        row["path"] = str(path)
    return data


def render_process(connection, model_root, profiles):
    # Imported only in the disposable GPU process; the HTTP parent stays small.
    try:
        import numpy as np
        from scipy.signal import resample_poly
        import torch
        from omnivoice import OmniVoice
        from omnivoice.utils.common import fix_random_seed

        torch.set_num_threads(4)
        free, total = torch.cuda.mem_get_info()
        if free < 6 * 1024**3:
            raise RuntimeError("Insufficient free GPU memory")
        torch.cuda.set_per_process_memory_fraction(min(0.22, (free - 1024**3) / total))
        model = OmniVoice.from_pretrained(
            str(Path(model_root) / "weights"),
            device_map="cuda",
            dtype=torch.float16,
            attn_implementation="sdpa",
            load_asr=False,
            local_files_only=True,
        )
        while True:
            text, profile = connection.recv()
            row = profiles[profile]
            fix_random_seed(row["seed"])
            audio = model.generate(
                text=text,
                language="sv",
                ref_audio=row["path"],
                ref_text=row["text"],
                num_step=32,
                normalize_text=False,
            )[0]
            if (
                audio.ndim != 1
                or not np.isfinite(audio).all()
                or not 0 < len(audio) <= model.sampling_rate * 90
                or np.sqrt(np.mean(audio.astype(float) ** 2)) < 0.001
            ):
                raise RuntimeError("Invalid generated audio")
            audio = resample_poly(audio, SAMPLE_RATE, model.sampling_rate)
            connection.send(
                (True, (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())
            )
    except (EOFError, BrokenPipeError):
        pass
    except Exception:
        LOG.exception("omnivoice_render_failed")
        try:
            connection.send((False, b""))
        except (EOFError, BrokenPipeError):
            pass
    finally:
        connection.close()


class Runtime:
    def __init__(self, model_root, profiles, timeout=90, idle_seconds=120):
        self.model_root, self.profiles = model_root, profiles
        self.timeout, self.idle_seconds = timeout, idle_seconds
        self.lock = threading.Lock()
        self.process = self.connection = None
        self.last_used = 0.0
        self.closed = threading.Event()
        self.reaper = threading.Thread(target=self._reap, daemon=True)
        self.reaper.start()

    def _stop(self):
        if self.process is not None:
            self.process.terminate()
            self.process.join(2)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(2)
            self.process.close()
            self.process = None
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def expire_idle(self):
        if self.lock.acquire(blocking=False):
            try:
                if (
                    self.process is not None
                    and time.monotonic() - self.last_used >= self.idle_seconds
                ):
                    self._stop()
                    LOG.info("gpu_released reason=idle")
            finally:
                self.lock.release()

    def _reap(self):
        while not self.closed.wait(2):
            self.expire_idle()

    def close(self):
        self.closed.set()
        with self.lock:
            self._stop()

    def synthesize(self, text, profile, cancelled=lambda: False):
        if not self.lock.acquire(blocking=False):
            raise BusyError("OmniVoice is busy")
        started = time.monotonic()
        try:
            if self.closed.is_set():
                raise RuntimeError("Worker stopped")
            if self.process is None or not self.process.is_alive():
                self._stop()
                context = multiprocessing.get_context("spawn")
                self.connection, child = context.Pipe()
                self.process = context.Process(
                    target=render_process,
                    args=(child, self.model_root, self.profiles),
                    daemon=True,
                )
                self.process.start()
                child.close()
            self.connection.send((text, profile))
            while not self.connection.poll(0.1):
                if cancelled():
                    raise ConnectionAbortedError("Client disconnected")
                if time.monotonic() - started >= self.timeout:
                    raise TimeoutError("Rendering timed out")
                if not self.process.is_alive():
                    raise RuntimeError("Renderer exited")
            success, pcm = self.connection.recv()
            if not success:
                raise RuntimeError("Rendering failed")
            elapsed = round((time.monotonic() - started) * 1000)
            self.last_used = time.monotonic()
            LOG.info(
                "synthesized profile=%s chars=%d elapsed_ms=%d audio_ms=%d",
                profile,
                len(text),
                elapsed,
                len(pcm) * 500 // SAMPLE_RATE,
            )
            return pcm, elapsed
        except Exception:
            self._stop()
            raise
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
                    "backend": "omnivoice",
                    "device": "cuda",
                    "voices": sorted(PROFILES),
                    "sample_rate": SAMPLE_RATE,
                    "busy": runtime.lock.locked(),
                    "model_loaded": runtime.process is not None,
                    "idle_unload_seconds": runtime.idle_seconds,
                },
            )

        def disconnected(self):
            if select.select([self.connection], [], [], 0)[0]:
                return not self.connection.recv(
                    1, socket.MSG_PEEK | socket.MSG_DONTWAIT
                )
            return False

        def do_POST(self):
            if self.path != "/synthesize":
                self.send_error(404)
                return
            try:
                self.connection.settimeout(10)
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError("Invalid request length")
                text, profile = validate_request(json.loads(self.rfile.read(length)))
            except (ValueError, TypeError, TimeoutError):
                self.reply(400, {"detail": "Invalid request"})
                return
            try:
                pcm, elapsed = runtime.synthesize(text, profile, self.disconnected)
            except BusyError:
                self.reply(429, {"detail": "OmniVoice is busy"})
                return
            except (ConnectionAbortedError, ConnectionResetError, BrokenPipeError):
                return
            except Exception:
                LOG.exception("omnivoice_synthesis_failed")
                self.reply(503, {"detail": "OmniVoice could not produce speech"})
                return
            self.send_response(200)
            for key, value in {
                "Content-Type": "application/octet-stream",
                "Content-Length": str(len(pcm)),
                "X-Sample-Rate": str(SAMPLE_RATE),
                "X-Channels": "1",
                "X-Sample-Format": "pcm_s16le",
                "X-Render-Milliseconds": str(elapsed),
            }.items():
                self.send_header(key, value)
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
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8796)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    runtime = Runtime(args.model_root, load_profiles(args.model_root / "voices"))
    server = ThreadingHTTPServer(("127.0.0.1", args.port), build_handler(runtime))
    try:
        server.serve_forever()
    finally:
        runtime.close()
        server.server_close()


if __name__ == "__main__":
    main()
