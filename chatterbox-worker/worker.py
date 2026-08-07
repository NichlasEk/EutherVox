from __future__ import annotations

import argparse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
from pathlib import Path
from threading import Lock
import time

import torch
import torchaudio
from chatterbox.mtl_tts import ChatterboxMultilingualTTS


LOG = logging.getLogger("euthervox.chatterbox")


class ChatterboxRuntime:
    def __init__(self, device: str, output_sample_rate: int, reference_audio: str):
        self.device = device
        self.output_sample_rate = output_sample_rate
        self.reference_audio = Path(reference_audio).expanduser() if reference_audio else None
        if self.reference_audio and not self.reference_audio.is_file():
            raise FileNotFoundError(f"Reference audio does not exist: {self.reference_audio}")
        LOG.info("loading model device=%s version=v3", device)
        self.model = ChatterboxMultilingualTTS.from_pretrained(device=device, t3_model="v3")
        self.lock = Lock()
        LOG.info("model_ready native_sample_rate=%d", self.model.sr)

    def synthesize(self, text: str, language_id: str) -> bytes:
        kwargs: dict[str, object] = {"language_id": language_id}
        if self.reference_audio:
            kwargs["audio_prompt_path"] = str(self.reference_audio)
        started = time.monotonic()
        with self.lock, torch.inference_mode():
            audio = self.model.generate(text, **kwargs).detach().cpu()
        if audio.ndim == 1:
            audio = audio.unsqueeze(0)
        if self.model.sr != self.output_sample_rate:
            audio = torchaudio.functional.resample(audio, self.model.sr, self.output_sample_rate)
        pcm = (
            audio.squeeze(0)
            .clamp(-1.0, 1.0)
            .mul(32767.0)
            .round()
            .to(torch.int16)
            .numpy()
            .tobytes()
        )
        LOG.info(
            "synthesized chars=%d audio_ms=%d elapsed_ms=%d",
            len(text),
            len(pcm) * 1000 // (self.output_sample_rate * 2),
            int((time.monotonic() - started) * 1000),
        )
        return pcm


def handler(runtime: ChatterboxRuntime):
    class Handler(BaseHTTPRequestHandler):
        server_version = "EutherVoxChatterbox/0.1"

        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self._json(HTTPStatus.OK, {"status": "ready", "sample_rate": runtime.output_sample_rate})

        def do_POST(self) -> None:
            if self.path != "/synthesize":
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                text = " ".join(str(payload.get("text", "")).split())
                language_id = str(payload.get("language_id", "sv"))
                if not text:
                    raise ValueError("text is required")
                if language_id != "sv":
                    raise ValueError("only Swedish is enabled")
                pcm = runtime.synthesize(text, language_id)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "audio/L16")
                self.send_header("X-Sample-Rate", str(runtime.output_sample_rate))
                self.send_header("Content-Length", str(len(pcm)))
                self.end_headers()
                self.wfile.write(pcm)
            except (ValueError, json.JSONDecodeError) as error:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
            except Exception as error:
                LOG.exception("synthesis_failed")
                self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(error)})

        def log_message(self, format: str, *args: object) -> None:
            LOG.info("http " + format, *args)

        def _json(self, status: HTTPStatus, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="Isolated Chatterbox V3 worker for EutherVox")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8790)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--sample-rate", type=int, default=22050)
    parser.add_argument("--reference-audio", default=os.environ.get("EUTHERVOX_CHATTERBOX_REFERENCE", ""))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    runtime = ChatterboxRuntime(args.device, args.sample_rate, args.reference_audio)
    server = ThreadingHTTPServer((args.host, args.port), handler(runtime))
    LOG.info("listening http://%s:%d", args.host, args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
