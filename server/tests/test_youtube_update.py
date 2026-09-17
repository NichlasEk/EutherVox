import importlib.util
import json
from pathlib import Path
from unittest.mock import Mock
import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('youtube_update', ROOT / 'scripts/update_youtube_runtime.py')
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


def test_failed_current_smoke_preserves_active_runtime(tmp_path, monkeypatch):
    runtime = tmp_path / 'working'; runtime.mkdir()
    (runtime / 'release.json').write_text('{"version":"2026.8.19"}')
    (tmp_path / 'current').symlink_to(runtime)
    response = Mock()
    response.__enter__ = Mock(return_value=response)
    response.__exit__ = Mock(return_value=False)
    response.read = Mock(return_value=b'{"info":{"version":"2026.8.19"}}')
    monkeypatch.setattr(updater.urllib.request, 'urlopen', lambda *a, **k: response)
    monkeypatch.setattr(updater, 'smoke', Mock(side_effect=RuntimeError('https://secret.example/token')))
    with pytest.raises(RuntimeError) as error: updater.update(tmp_path)
    assert (tmp_path / 'current').resolve() == runtime
    assert 'secret.example' not in str(error.value)
    assert json.loads((tmp_path / 'status.json').read_text())['phase'] == 'current_smoke'


def test_failed_candidate_never_replaces_working_runtime(tmp_path, monkeypatch):
    runtime = tmp_path / 'working'; runtime.mkdir()
    (runtime / 'release.json').write_text('{"version":"2026.7.4"}')
    (tmp_path / 'current').symlink_to(runtime)
    response = Mock()
    response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
    response.read = Mock(return_value=b'{"info":{"version":"2026.8.19"}}')
    monkeypatch.setattr(updater.urllib.request, 'urlopen', lambda *a, **k: response)
    monkeypatch.setattr(updater.shutil, 'which', lambda *a: '/usr/bin/uv')
    monkeypatch.setattr(updater.subprocess, 'run', Mock())
    monkeypatch.setattr(updater, 'smoke', Mock(side_effect=ValueError('bad PCM')))
    with pytest.raises(RuntimeError): updater.update(tmp_path)
    assert (tmp_path / 'current').resolve() == runtime
    assert list((tmp_path / 'releases').iterdir()) == []


def test_activation_keeps_previous_for_rollback(tmp_path):
    first = tmp_path / 'first'; first.mkdir()
    second = tmp_path / 'second'; second.mkdir()
    updater.activate(tmp_path, first)
    updater.activate(tmp_path, second)
    assert (tmp_path / 'current').resolve() == second
    assert (tmp_path / 'previous').resolve() == first
    updater.activate(tmp_path, first)
    assert (tmp_path / 'current').resolve() == first
    assert (tmp_path / 'previous').resolve() == second


def test_resolver_uses_concrete_runtime_and_hides_worker_errors(tmp_path, monkeypatch):
    from gateway.youtube_audio import YouTubeAudioResolver
    import gateway.youtube_audio as module
    runtime = tmp_path / 'release'; runtime.mkdir()
    current = tmp_path / 'current'; current.symlink_to(runtime)
    monkeypatch.setenv('EUTHERVOX_YOUTUBE_RUNTIME', str(current))
    run = Mock(return_value=Mock(stdout=json.dumps(dict(video_id='EUrvY1XggAI',url='https://r.googlevideo.com/a',content_type='audio/mp4',title='test',thumbnail=''))))
    monkeypatch.setattr(module.subprocess, 'run', run)
    assert YouTubeAudioResolver()._resolve_sync('EUrvY1XggAI').title == 'test'
    assert run.call_args.args[0][0] == str(runtime / 'bin/python')
    run.return_value.stdout = '{"url":"http://localhost/private"}'
    with pytest.raises(RuntimeError): YouTubeAudioResolver()._resolve_sync('EUrvY1XggAI')
