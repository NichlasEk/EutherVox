"""EutherLink job client using the same synthetic Ebba recipe as EutherWash."""
from __future__ import annotations

import asyncio
import base64
from pathlib import Path
from urllib.parse import urljoin, urlsplit

import httpx


class EbbaTextToSpeechEngine:
    # Notifications must be rendered before the phone opens its audio stream.
    prebuffer = True

    def __init__(self, base_url, reference_file, sample_rate=22050, timeout_seconds=420, transport=None):
        self.base_url = base_url.rstrip('/')
        self.reference_file = Path(reference_file)
        self.sample_rate = sample_rate
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    def endpoint(self, path):
        url = urljoin(self.base_url+'/', path)
        if urlsplit(url)[:2] != urlsplit(self.base_url)[:2]:
            raise ValueError('Unexpected Ebba TTS API origin')
        return url

    async def _pcm(self, wav):
        process = await asyncio.create_subprocess_exec(
            'ffmpeg', '-v', 'error', '-i', 'pipe:0',
            '-af', 'highpass=f=80,loudnorm=I=-20:TP=-3:LRA=7',
            '-ar', str(self.sample_rate), '-ac', '1', '-f', 's16le', 'pipe:1',
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE)
        try:
            pcm, error = await asyncio.wait_for(process.communicate(wav), 20)
            if process.returncode or not pcm:
                raise RuntimeError('Kunde inte konvertera Ebbarösten')
            return pcm
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()

    async def synthesize(self, text, character, sample_rate):
        if sample_rate != self.sample_rate:
            raise ValueError('Unexpected Ebba sample rate')
        if not 1 <= len(text.strip()) <= 700:
            raise ValueError('Ebbarapporten måste innehålla 1–700 tecken')
        payload = dict(
            text=text,
            voice_instruction='An adult Swedish female naval commander, low warm confident voice, crisp Swedish pronunciation, restrained urgency, intimate and natural, dry intelligence. Serious and matter-of-fact delivery.',
            language='sv', model_backend='dots.tts-mf', output_format='wav',
            normalize=False, seed=30220701,
            reference_wav_base64=base64.b64encode(self.reference_file.read_bytes()).decode(),
            prompt_text='Karl, håll dig nära kajen. Flottan väntar ute i dimman. Jag vill ha alla tillbaka ombord innan gryningen. Vi har förlorat nog med folk för kungars gamla drömmar.',
            dots_num_steps=16)
        async with asyncio.timeout(self.timeout_seconds):
            async with httpx.AsyncClient(timeout=30, trust_env=False, transport=self.transport) as client:
                response = await client.post(self.endpoint('/v1/tts/jobs'), json=payload)
                response.raise_for_status()
                job = response.json()
                while True:
                    response = await client.get(self.endpoint(job['status_url']))
                    response.raise_for_status()
                    state = response.json()
                    if state['status'] == 'done':
                        break
                    if state['status'] in ('failed', 'cancelled'):
                        raise RuntimeError('Ebbaröstens generering misslyckades')
                    await asyncio.sleep(1)
                response = await client.get(self.endpoint(state['audio_url']))
                response.raise_for_status()
                pcm = await self._pcm(response.content)
        frame_bytes = self.sample_rate * 2 * 20 // 1000
        for offset in range(0, len(pcm), frame_bytes):
            yield pcm[offset:offset+frame_bytes]
