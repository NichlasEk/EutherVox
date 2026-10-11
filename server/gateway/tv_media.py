"""Bounded TV search/status intents. Titles are data, never generated device commands."""
from __future__ import annotations
import asyncio
import base64
import re
from uuid import uuid4
from .actions import DeviceAction

ROOMS = {'uppe': 'samsung', 'nere': 'nec'}
SERVICES = ('Netflix', 'Disney+', 'Max', 'Prime Video', 'SVT Play', 'YouTube', 'Viaplay', 'TV4 Play', 'Kodi')
DESTINATION = re.compile(r'\s+(?:på|i)\s+(?P<target>(?:nec|samsung)(?:[- ]?tv(?::?n|n)?)?|(?:tv(?::?n|n)?|pucken)\s+(?:uppe|nere)|uppe|nere)\s*$', re.I)
UNKNOWN_DESTINATION = re.compile(r'\s+(?:på|i)\s+(?:tv(?::?n|n)?|pucken)\s+.+$', re.I)
STATUS = re.compile(r'^(?:vad (?:tittar jag på|visas|spelas)(?: just nu)?|vad är det för film)\s*[?.!]*$', re.I)
SEARCH = re.compile(r'^(?:(?:jag vill|jag skulle vilja)\s+(?:titta på|se)|(?:kan du\s+)?(?:sök efter|sök på|hitta)\s+(?:filmen|serien)|(?:kan du\s+)?(?:visa|starta)\s+(?:filmen|serien))\s+(.+)$', re.I)


def plan_tv_media(text: str, node_name: str, room: str = '') -> DeviceAction | None:
    value = ' '.join(text.strip().split()).rstrip('.!?')
    target = ROOMS.get(room)
    spoken_room = re.fullmatch(r'(vad tittar jag på|vad visas|vad spelas) (uppe|nere)', value, re.I)
    if spoken_room:
        value = spoken_room[1]
        target = ROOMS[spoken_room[2].casefold()]
    explicit_targets = set()
    # Strip known destination and service suffixes in either order.
    service = ''
    for _ in range(2):
        destination = DESTINATION.search(value)
        if destination:
            label = destination['target'].casefold()
            target = 'nec' if ('nec' in label or 'nere' in label) else 'samsung'
            explicit_targets.add(target)
            value = value[:destination.start()]
        for candidate in SERVICES:
            suffix = re.search(r'\s+(?:på|i)\s+' + re.escape(candidate) + r'$', value, re.I)
            if suffix:
                service = candidate
                value = value[:suffix.start()]
                break
    status = STATUS.fullmatch(value)
    search = SEARCH.fullmatch(value)
    if not status and not search:
        return None
    def clarify(message):
        return DeviceAction(str(uuid4()), 'assistant.clarify', node_name, {'domain': 'tv'}, message)
    if len(explicit_targets) > 1 or UNKNOWN_DESTINATION.search(value):
        return clarify('Vilken TV menar du, Uppe eller Nere?')
    if not target:
        return clarify('Välj TV-rum Uppe eller Nere, eller säg vilken TV du menar.')
    if status:
        return DeviceAction(str(uuid4()), 'beam.status', node_name,
                            {'provider': 'eutherbeam', 'uri': 'android_tv_' + target},
                            'Jag läser vad pucken rapporterar.')
    title = re.sub(r'^(?:filmen|serien)\s+', '', search[1], flags=re.I).strip(' ”"')
    if not 1 <= len(title) <= 160 or any(ord(c) < 32 for c in title):
        return clarify('Säg en kort filmtitel att söka efter.')
    return DeviceAction(str(uuid4()), 'beam.search', node_name,
                        {'provider': 'eutherbeam', 'uri': 'android_tv_' + target, 'query': title,
                         'search_service': service},
                        f'Jag skickar en sökning efter {title} till TV:n {"nere" if target == "nec" else "uppe"}.')


async def search_audio(tts, character, title: str, service: str) -> str:
    """Produce bounded PCM for the puck's own voice search, not playback on the phone."""
    phrase = f'Sök efter filmen {title}' + (f' på {service}' if service else '')
    async def render():
        pcm = bytearray()
        synthesize = getattr(tts, 'synthesize_fast', tts.synthesize)
        async for chunk in synthesize(phrase, character, tts.sample_rate):
            pcm.extend(chunk)
            if len(pcm) > tts.sample_rate * 2 * 25:
                raise ValueError('Sökfrasen blev för lång')
        if not pcm or len(pcm) % 2:
            raise ValueError('Sökfrasens ljud kunde inte skapas')
        process = await asyncio.create_subprocess_exec(
            'ffmpeg', '-v', 'error', '-f', 's16le', '-ar', str(tts.sample_rate), '-ac', '1',
            '-i', 'pipe:0', '-f', 's16le', '-ar', '8000', '-ac', '1', 'pipe:1',
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        try:
            output, _ = await process.communicate(bytes(pcm))
            if process.returncode or not output or len(output) > 400_000:
                raise ValueError('Sökfrasens ljud kunde inte konverteras')
            return base64.b64encode(output).decode('ascii')
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()
    return await asyncio.wait_for(render(), timeout=35)
