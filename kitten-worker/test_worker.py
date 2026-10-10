import importlib.util
from pathlib import Path
import json
import subprocess
from unittest.mock import patch

import numpy as np
import pytest
import soundfile as sf

spec = importlib.util.spec_from_file_location(
    "kitten_worker", Path(__file__).with_name("worker.py")
)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


@pytest.mark.parametrize(
    "payload",
    [{}, [], {"text": "x" * 601}, {"text": "hej", "language_id": "xx"}, {"text": 42}],
)
def test_invalid_requests_rejected(payload):
    with pytest.raises(ValueError):
        worker.validate_request(payload)


def runtime(tmp_path):
    binary = tmp_path / "kitten-tts"
    binary.touch()
    (tmp_path / "config.json").write_text("{}")
    return worker.Runtime(binary, tmp_path, "Bruno")


def test_native_audio_resampled_and_shell_not_used(tmp_path):
    engine = runtime(tmp_path)

    def run(args, **kwargs):
        assert kwargs["timeout"] == 75
        assert "--offline" in args and "--no-normalize" in args
        assert "shell" not in kwargs
        output = args[args.index("--output") + 1]
        report = args[args.index("--report") + 1]
        sf.write(output, np.sin(np.arange(24000) * 0.03) * 0.2, 24000, subtype="FLOAT")
        Path(report).write_text(json.dumps({"chunks": [{"terminated": True}]}))

    with patch.object(worker.subprocess, "run", run):
        pcm, _ = engine.synthesize("Hej, världen!")
    assert len(pcm) == 44100
    assert not engine.lock.locked()


def test_busy_and_timeout_are_bounded(tmp_path):
    engine = runtime(tmp_path)
    engine.lock.acquire()
    with pytest.raises(worker.BusyError):
        engine.synthesize("hej")
    engine.lock.release()
    with patch.object(
        worker.subprocess, "run", side_effect=subprocess.TimeoutExpired("kitten", 75)
    ):
        with pytest.raises(subprocess.TimeoutExpired):
            engine.synthesize("hej")
    assert not engine.lock.locked()


def test_unfinished_speech_is_not_returned_as_success(tmp_path):
    engine = runtime(tmp_path)

    def run(args, **kwargs):
        Path(args[args.index("--report") + 1]).write_text(
            json.dumps({"chunks": [{"terminated": False}]})
        )

    with patch.object(worker.subprocess, "run", run):
        with pytest.raises(ValueError, match="did not finish"):
            engine.synthesize("hej")
