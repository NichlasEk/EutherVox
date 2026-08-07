from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import logging
from pathlib import Path
import sys
import threading
import time
from types import ModuleType

import numpy as np


LOG = logging.getLogger("euthervox.matcha")


def load_renderer_module(path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location("euthervox_graphene_matcha", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load Matcha renderer from {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class MatchaRuntime:
    def __init__(self, assets_dir: Path, renderer_path: Path) -> None:
        module = load_renderer_module(renderer_path)
        self.sample_rate = int(module.SAMPLE_RATE)
        self.renderer = module.MatchaRenderer(assets_dir)
        self.assets_dir = assets_dir
        self.renderer_path = renderer_path
        self._lock = threading.Lock()
        LOG.info(
            "matcha_ready sample_rate=%d assets=%s renderer=%s",
            self.sample_rate,
            assets_dir,
            renderer_path,
        )

    def synthesize(self, text: str, speed: float) -> tuple[bytes, int]:
        started = time.monotonic()
        length_scale = 1.0 / max(0.5, min(1.5, speed))
        with self._lock:
            audio, _ = self.renderer.render(text, length_scale=length_scale)
        pcm = np.round(np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
        elapsed_ms = int((time.monotonic() - started) * 1000)
        LOG.info(
            "matcha_complete elapsed_ms=%d text_chars=%d audio_ms=%d speed=%.2f",
            elapsed_ms,
            len(text),
            len(pcm) * 500 // self.sample_rate,
            speed,
        )
        return pcm, elapsed_ms


def build_handler(runtime: MatchaRuntime) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "EutherVoxMatcha/1"

        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_error(404)
                return
            self._send_json(
                200,
                {
                    "status": "ready",
                    "backend": "grapheneos-matcha-en",
                    "sample_rate": runtime.sample_rate,
                    "language": "en",
                    "assets_dir": str(runtime.assets_dir),
                },
            )

        def do_POST(self) -> None:
            if self.path != "/synthesize":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                request = json.loads(self.rfile.read(length))
                text = str(request.get("text", "")).strip()
                language = str(request.get("language_id", "en"))
                speed = float(request.get("speed", 1.0))
                if not text:
                    self._send_json(400, {"detail": "text must not be empty"})
                    return
                if language != "en":
                    self._send_json(400, {"detail": "only English is enabled"})
                    return
                pcm, elapsed_ms = runtime.synthesize(text, speed)
            except (ValueError, TypeError, json.JSONDecodeError) as error:
                self._send_json(400, {"detail": str(error)})
                return
            except Exception as error:
                LOG.exception("matcha_synthesis_failed")
                self._send_json(500, {"detail": str(error)})
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(pcm)))
            self.send_header("X-Sample-Rate", str(runtime.sample_rate))
            self.send_header("X-Channels", "1")
            self.send_header("X-Sample-Format", "pcm_s16le")
            self.send_header("X-Render-Milliseconds", str(elapsed_ms))
            self.end_headers()
            self.wfile.write(pcm)

        def log_message(self, format: str, *args: object) -> None:
            LOG.info("http " + format, *args)

        def _send_json(self, status: int, body: dict[str, object]) -> None:
            encoded = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="Persistent GrapheneOS Matcha English TTS worker")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8793)
    parser.add_argument("--assets-dir", type=Path, required=True)
    parser.add_argument("--renderer", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    runtime = MatchaRuntime(args.assets_dir.resolve(), args.renderer.resolve())
    server = ThreadingHTTPServer((args.host, args.port), build_handler(runtime))
    LOG.info("matcha_listening http://%s:%d", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
