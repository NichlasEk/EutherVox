"""Bounded public YouTube playlist metadata using the managed extractor runtime."""
import asyncio
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit, parse_qs

PLAYLIST_ID = re.compile(r'^[A-Za-z0-9_-]{10,100}$')
VIDEO_ID = re.compile(r'^[A-Za-z0-9_-]{11}$')
HOSTS = {'youtube.com', 'www.youtube.com', 'music.youtube.com', 'm.youtube.com', 'youtu.be'}


def playlist_id(query):
    url = urlsplit(query)
    if url.scheme not in ('http', 'https') or url.hostname not in HOSTS or url.username or url.password:
        return None
    value = parse_qs(url.query).get('list', [''])[0]
    if not value: return None
    if not PLAYLIST_ID.fullmatch(value): raise ValueError('Ogiltig spellistelänk')
    if value.startswith(('RD', 'WL', 'LL')): raise ValueError('Använd en vanlig delbar spellista, inte en personlig mix eller Gillade videor')
    return value


def extract(ident, factory):
    if not PLAYLIST_ID.fullmatch(ident): raise ValueError('Ogiltig spellista')
    with factory(dict(quiet=True, no_warnings=True, extract_flat='in_playlist',
                      skip_download=True, playlistend=50, socket_timeout=8, retries=1,
                      extractor_retries=1, ignoreerrors=True)) as downloader:
        info = downloader.extract_info('https://www.youtube.com/playlist?list=' + ident, download=False)
    tracks = []
    for item in (info or {}).get('entries') or []:
        if not item or not VIDEO_ID.fullmatch(str(item.get('id', ''))): continue
        if item.get('availability') in ('private', 'premium_only', 'subscriber_only', 'needs_auth'): continue
        tracks.append(dict(video_id=item['id'], title=str(item.get('title') or 'YouTube')[:160]))
        if len(tracks) == 50: break
    if not tracks: raise ValueError('Spellistan är tom eller inte tillgänglig')
    return dict(title=str(info.get('title') or 'Spellista')[:160], tracks=tracks)


async def resolve_playlist(ident):
    def run():
        runtime = os.environ.get('EUTHERVOX_YOUTUBE_RUNTIME')
        python = str(Path(runtime).resolve(strict=True) / 'bin/python') if runtime else sys.executable
        try:
            result = subprocess.run([python, str(Path(__file__).with_name('youtube_audio_worker.py')), '--playlist', ident],
                                    capture_output=True, text=True, timeout=30, check=True)
            data = json.loads(result.stdout)
            if not 1 <= len(data['tracks']) <= 50: raise ValueError('Invalid size')
            if not all(VIDEO_ID.fullmatch(t['video_id']) for t in data['tracks']): raise ValueError('Invalid tracks')
            return data
        except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
            raise RuntimeError('Kunde inte läsa spellistan. Använd en offentlig eller olistad YouTube-spellista.') from None
    return await asyncio.to_thread(run)
