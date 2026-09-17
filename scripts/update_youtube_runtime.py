#!/usr/bin/env python3
"""Stage stable yt-dlp, decode silent samples, then atomically promote it.

No robot calls, playback, gateway restart, repository edits or automatic git push.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'state/youtube-runtime'
WORKER = ROOT / 'server/gateway/youtube_audio_worker.py'
VIDEOS = ('EUrvY1XggAI', 'ouQmlz3I3v8')


def atomic_json(path, data):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    os.replace(temporary, path)


def swap_link(link: Path, target: Path):
    temporary = link.with_name(link.name + '.next')
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    os.replace(temporary, link)


def activate(state: Path, candidate: Path):
    current = state / 'current'
    if current.is_symlink():
        swap_link(state / 'previous', current.resolve(strict=True))
    swap_link(current, candidate.resolve(strict=True))


def smoke(runtime: Path):
    for video in VIDEOS:
        extracted = subprocess.run([str(runtime / 'bin/python'), str(WORKER), video],
                                   capture_output=True, text=True, timeout=30, check=True)
        data = json.loads(extracted.stdout)
        # Same trusted URL policy and decoder parameters used by EutherWash.
        from urllib.parse import urlsplit
        url = urlsplit(data['url'])
        if url.scheme != 'https' or not url.hostname or not url.hostname.endswith('.googlevideo.com'):
            raise ValueError('Untrusted media URL')
        command = ['ffmpeg', '-nostdin', '-v', 'error', '-rw_timeout', '8000000',
            '-protocol_whitelist', 'https,tls,tcp', '-i', data['url'], '-t', '2', '-vn',
            '-f', 's16le', '-acodec', 'pcm_s16le', '-ar', '16000', '-ac', '1', 'pipe:1']
        ssh_host = os.environ.get('EUTHERVOX_YOUTUBE_SMOKE_SSH', '')
        if ssh_host:
            # Decode where EutherWash actually runs. URL travels only over stdin.
            probe = "import json,sys,subprocess; args=json.load(sys.stdin); p=subprocess.run(args,capture_output=True,timeout=25); print(json.dumps({'returncode':p.returncode,'bytes':len(p.stdout)}))"
            decoded = subprocess.run(['ssh', '-o', 'ConnectTimeout=8', ssh_host,
                                      'python3 -c ' + shlex.quote(probe)],
                                     input=json.dumps(command), capture_output=True, text=True,
                                     start_new_session=True, timeout=35, check=True)
            result = json.loads(decoded.stdout)
            if result.get('returncode') != 0 or result.get('bytes') != 64000:
                raise ValueError('Server decoder rejected audio sample')
        else:
            decoded = subprocess.run(command, capture_output=True, timeout=25, check=True)
            if len(decoded.stdout) != 64000:
                raise ValueError('Incomplete decoded sample')



def update(state: Path = STATE, *, force: bool = False, rollback: bool = False):
    os.umask(0o077)
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'update.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {'status': 'already_running'}
        candidate = None
        phase = 'release_lookup'
        try:
            if rollback:
                previous = (state / 'previous').resolve(strict=True)
                phase = 'rollback_smoke'
                smoke(previous)
                activate(state, previous)
                result = {'status': 'rolled_back', 'runtime': previous.name}
            else:
                with urllib.request.urlopen('https://pypi.org/pypi/yt-dlp/json', timeout=20) as response:
                    version = json.load(response)['info']['version']
                if not re.fullmatch(r'\d{4}\.\d{1,2}\.\d{1,2}', version):
                    raise ValueError('Not a stable dated release')
                current = state / 'current'
                active_version = ''
                if current.is_dir():
                    active_version = json.loads((current / 'release.json').read_text())['version']
                if active_version == version and not force:
                    phase = 'current_smoke'
                    smoke(current)
                    result = {'status': 'current_verified', 'version': version}
                else:
                    phase = 'install_candidate'
                    releases = state / 'releases'; releases.mkdir(exist_ok=True)
                    candidate = Path(tempfile.mkdtemp(prefix=version + '-', dir=releases))
                    uv = shutil.which('uv')
                    if not uv: raise RuntimeError('uv is missing')
                    subprocess.run([uv, 'venv', '--python', sys.executable, str(candidate)],
                                   capture_output=True, timeout=60, check=True)
                    subprocess.run([uv, 'pip', 'install', '--python', str(candidate / 'bin/python'),
                                    '--index-url', 'https://pypi.org/simple', f'yt-dlp[default]=={version}'],
                                   capture_output=True, timeout=180, check=True)
                    atomic_json(candidate / 'release.json', {'version': version})
                    phase = 'candidate_smoke'
                    smoke(candidate)
                    # Capture installed dependency versions for repeatable recovery.
                    frozen = subprocess.run([uv, 'pip', 'freeze', '--python', str(candidate / 'bin/python')],
                                            capture_output=True, text=True, timeout=20, check=True)
                    (candidate / 'requirements.txt').write_text(frozen.stdout)
                    phase = 'activate'
                    activate(state, candidate)
                    result = {'status': 'activated', 'version': version, 'runtime': candidate.name}
            result['checked_at'] = datetime.now(timezone.utc).isoformat()
            atomic_json(state / 'status.json', result)
            return result
        except Exception as error:
            # Signed URLs and subprocess args must never appear in logs/status.
            failure = {'status': 'failed', 'phase': phase, 'error_type': type(error).__name__,
                       'checked_at': datetime.now(timezone.utc).isoformat(), 'previous_runtime_retained': True}
            atomic_json(state / 'status.json', failure)
            if candidate and phase != 'activate':
                shutil.rmtree(candidate)
            raise RuntimeError(json.dumps(failure)) from None


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--force', action='store_true', help='Rebuild/retest even the currently installed version')
    parser.add_argument('--rollback', action='store_true', help='Retest and restore the previous runtime')
    args = parser.parse_args()
    try:
        print(json.dumps(update(force=args.force, rollback=args.rollback)))
    except RuntimeError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
