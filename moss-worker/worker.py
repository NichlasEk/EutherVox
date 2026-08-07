from __future__ import annotations

import argparse
import logging
import os
from pathlib import Path
import threading
import time

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
import numpy as np
import soxr
import uvicorn

from app_onnx import OnnxNanoTTSServiceAdapter


LOG = logging.getLogger("euthervox.moss")


class SynthesisRequest(BaseModel):
    text: str
    language_id: str = "sv"
    voice_id: str = ""


class MossRuntime:
    def __init__(
        self,
        model_dir: Path,
        output_dir: Path,
        reference_audio: Path | None,
        voice: str,
        threads: int,
        output_sample_rate: int,
        reference_profiles: dict[str, Path] | None = None,
    ) -> None:
        self.engine = OnnxNanoTTSServiceAdapter(
            model_dir=model_dir,
            output_dir=output_dir,
            cpu_threads=threads,
            execution_provider="cpu",
            max_new_frames=375,
        )
        voices = [str(item["voice"]) for item in self.engine.runtime.list_builtin_voices()]
        self.voice = voice if voice in voices else voices[0]
        self.reference_audio = reference_audio if reference_audio and reference_audio.is_file() else None
        self.reference_profiles = {
            name: path for name, path in (reference_profiles or {}).items() if path.is_file()
        }
        self.output_sample_rate = output_sample_rate
        self._lock = threading.Lock()
        LOG.info(
            "moss_ready provider=onnx_cpu threads=%d voice=%s reference=%s native_rate=%d output_rate=%d voices=%s",
            threads,
            self.voice,
            self.reference_audio or "builtin",
            int(self.engine.runtime.codec_meta["codec_config"]["sample_rate"]),
            output_sample_rate,
            ",".join(voices),
        )

    def synthesize(self, text: str, voice_id: str = ""):
        started = time.monotonic()
        first_audio_ms: int | None = None
        reference_audio = self.reference_profiles.get(voice_id, self.reference_audio)
        profile_name = voice_id if voice_id in self.reference_profiles else "default"
        with self._lock:
            stream = self.engine.synthesize_stream(
                text=text,
                mode="voice_clone",
                voice=self.voice,
                prompt_audio_path=str(reference_audio) if reference_audio else None,
                max_new_frames=375,
                voice_clone_max_text_tokens=75,
                attn_implementation="fixed",
                do_sample=True,
                text_temperature=1.0,
                text_top_p=1.0,
                text_top_k=50,
                audio_temperature=0.8,
                audio_top_p=0.95,
                audio_top_k=25,
                audio_repetition_penalty=1.2,
                seed=None,
            )
            resampler: soxr.ResampleStream | None = None
            for event in stream:
                if event.get("type") != "audio":
                    continue
                waveform = np.asarray(event["waveform_numpy"], dtype=np.float32)
                if waveform.ndim == 2:
                    waveform = waveform.mean(axis=1)
                native_rate = int(event["sample_rate"])
                if resampler is None:
                    resampler = soxr.ResampleStream(
                        native_rate,
                        self.output_sample_rate,
                        1,
                        dtype="float32",
                        quality="HQ",
                    )
                output = resampler.resample_chunk(waveform, last=False)
                if output.size:
                    if first_audio_ms is None:
                        first_audio_ms = int((time.monotonic() - started) * 1000)
                        LOG.info(
                            "moss_first_audio_ms=%d text_chars=%d profile=%s reference=%s",
                            first_audio_ms,
                            len(text),
                            profile_name,
                            reference_audio.stem if reference_audio else "builtin",
                        )
                    yield self._pcm16(output)
            if resampler is not None:
                tail = resampler.resample_chunk(np.zeros(0, dtype=np.float32), last=True)
                if tail.size:
                    yield self._pcm16(tail)
        LOG.info(
            "moss_complete elapsed_ms=%d first_audio_ms=%s text_chars=%d profile=%s",
            int((time.monotonic() - started) * 1000),
            first_audio_ms,
            len(text),
            profile_name,
        )

    @staticmethod
    def _pcm16(audio: np.ndarray) -> bytes:
        return np.round(np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()


def build_app(runtime: MossRuntime) -> FastAPI:
    app = FastAPI(title="EutherVox MOSS-TTS-Nano worker")

    @app.get("/health")
    async def health() -> dict[str, object]:
        return {
            "status": "ready",
            "backend": "moss-tts-nano-100m-onnx-cpu",
            "sample_rate": runtime.output_sample_rate,
            "voice": runtime.voice,
            "reference_audio": runtime.reference_audio is not None,
            "reference_name": runtime.reference_audio.stem if runtime.reference_audio else "builtin",
            "reference_profiles": sorted(runtime.reference_profiles),
        }

    @app.post("/synthesize")
    async def synthesize(request: SynthesisRequest) -> StreamingResponse:
        text = request.text.strip()
        if not text:
            raise HTTPException(status_code=400, detail="text must not be empty")
        if request.language_id != "sv":
            raise HTTPException(status_code=400, detail="only Swedish is enabled")
        return StreamingResponse(
            runtime.synthesize(text, request.voice_id),
            media_type="application/octet-stream",
            headers={
                "X-Sample-Rate": str(runtime.output_sample_rate),
                "X-Channels": "1",
                "X-Sample-Format": "pcm_s16le",
                "X-Voice-Profile": request.voice_id if request.voice_id in runtime.reference_profiles else "default",
            },
        )

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Isolated MOSS-TTS-Nano ONNX worker")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--threads", type=int, default=max(4, min(12, os.cpu_count() or 4)))
    parser.add_argument("--sample-rate", type=int, default=22_050)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("/tmp/euthervox-moss-output"))
    parser.add_argument("--reference-audio", type=Path)
    parser.add_argument(
        "--reference-profile",
        action="append",
        default=[],
        metavar="NAME=PATH",
        help="Allowlisted named reference profile; may be repeated",
    )
    parser.add_argument("--voice", default="Junhao")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    reference_profiles: dict[str, Path] = {}
    for entry in args.reference_profile:
        name, separator, path = entry.partition("=")
        if not separator or not name.strip() or not path.strip():
            parser.error("--reference-profile must use NAME=PATH")
        reference_profiles[name.strip()] = Path(path.strip()).resolve()
    runtime = MossRuntime(
        args.model_dir.resolve(),
        args.output_dir.resolve(),
        args.reference_audio.resolve() if args.reference_audio else None,
        args.voice,
        args.threads,
        args.sample_rate,
        reference_profiles,
    )
    uvicorn.run(build_app(runtime), host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
