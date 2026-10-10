import importlib.util
import json
import hashlib
from pathlib import Path
from unittest.mock import Mock, patch
import pytest

spec = importlib.util.spec_from_file_location(
    "omni_worker", Path(__file__).with_name("worker.py")
)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


@pytest.mark.parametrize(
    "payload",
    [
        [],
        {},
        {"text": "x", "voice_id": "../secret"},
        {"text": "x" * 601, "voice_id": "siaren-djup"},
        {"text": "x", "voice_id": "siaren-klar", "language_id": "en"},
    ],
)
def test_invalid_request(payload):
    with pytest.raises(ValueError):
        worker.validate_request(payload)


@pytest.mark.parametrize("voice", sorted(worker.PROFILES))
def test_profiles_accepted(voice):
    assert worker.validate_request({"text": " Hej! ", "voice_id": voice}) == (
        "Hej!",
        voice,
    )


def test_reference_integrity(tmp_path):
    ref = tmp_path / "reference.wav"
    ref.write_bytes(b"original")
    rows = {
        p: {
            "file": ref.name,
            "text": "Hej",
            "sha256": hashlib.sha256(ref.read_bytes()).hexdigest(),
            "seed": 1,
        }
        for p in worker.PROFILES
    }
    (tmp_path / "manifest.json").write_text(json.dumps(rows))
    assert set(worker.load_profiles(tmp_path)) == worker.PROFILES
    ref.write_bytes(b"changed")
    with pytest.raises(ValueError, match="checksum"):
        worker.load_profiles(tmp_path)


def runtime():
    engine = worker.Runtime(Path("/unused"), {}, timeout=0, idle_seconds=0)
    engine.process = Mock()
    engine.process.is_alive.return_value = True
    engine.connection = Mock()
    engine.connection.poll.return_value = False
    return engine


def test_timeout_releases_gpu_and_lock():
    engine = runtime()
    process = engine.process
    try:
        with pytest.raises(TimeoutError):
            engine.synthesize("Hej", "siaren-djup")
        process.terminate.assert_called_once()
        assert engine.process is None and not engine.lock.locked()
    finally:
        engine.close()


def test_disconnect_releases_gpu():
    engine = runtime()
    process = engine.process
    try:
        with pytest.raises(ConnectionAbortedError):
            engine.synthesize("Hej", "siaren-klar", lambda: True)
        process.terminate.assert_called_once()
    finally:
        engine.close()


def test_busy_not_queued_and_idle_does_not_kill_active_job():
    engine = runtime()
    process = engine.process
    try:
        engine.lock.acquire()
        with pytest.raises(worker.BusyError):
            engine.synthesize("Hej", "siaren-djup")
        engine.expire_idle()
        process.terminate.assert_not_called()
        engine.lock.release()
        engine.expire_idle()
        process.terminate.assert_called_once()
    finally:
        engine.close()
