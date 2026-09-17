"""One household robot queue, owned by the gateway rather than a phone session."""
import asyncio
import logging
from .youtube_audio import YouTubeAudioResolver

LOG = logging.getLogger(__name__)


class RobotMusicQueue:
    def __init__(self, service, resolver=None, poll_seconds=3):
        self.service = service
        self.resolver = resolver or YouTubeAudioResolver()
        self.poll_seconds = poll_seconds
        self.lock = asyncio.Lock()
        self.tracks = []
        self.index = 0
        self.title = ''
        self.playback_id = None
        self.volume = 50
        self.cleaning = False
        self.task = None
        self.error = ''

    def decorate(self, status):
        # A different playback (or a backend restart) invalidates the old queue.
        if self.playback_id and status.get('playback_id') != self.playback_id:
            self.tracks = []
            self.playback_id = None
            self.error = ''
        result = dict(status)
        result.update(queue_title=self.title if self.tracks else '', queue_count=len(self.tracks),
                      queue_index=self.index + 1 if self.tracks else 0,
                      has_next=self.index + 1 < len(self.tracks),
                      queue=[{'title': t['title']} for t in self.tracks])
        if self.error:
            result.update(state='error', message=self.error)
        return result

    async def start(self, tracks, title, audio, volume, cleaning):
        async with self.lock:
            # Keep the old queue intact if starting its replacement fails.
            status = await self.service.music('play', dict(url=audio.url, title=audio.title, volume=volume, cleaning=cleaning))
            self.tracks = tracks[:50]; self.index = 0; self.title = title
            self.volume = volume; self.cleaning = cleaning; self.error = ''
            self.playback_id = status.get('playback_id')
            if len(self.tracks) > 1 and not self.playback_id:
                self.tracks = []
                raise RuntimeError('Uppdatera EutherWash innan du spelar spellistor')
            if self.task is None or self.task.done():
                self.task = asyncio.create_task(self._watch())
            return self.decorate(status)

    async def _advance(self, *, automatic=False):
        next_index = self.index + 1
        audio = await asyncio.wait_for(self.resolver.resolve(self.tracks[next_index]['video_id']), 25)
        # Recheck after extraction: a native prompt, delivery, or stop wins the race.
        status = await self.service.music('status', {})
        if status.get('playback_id') != self.playback_id:
            return self.decorate(status)
        if automatic and not (status.get('ended') is True and status.get('state') == 'stopped' and not status.get('busy')):
            return self.decorate(status)
        status = await self.service.music('play', dict(url=audio.url, title=audio.title,
                                                     volume=status.get('volume', self.volume), cleaning=self.cleaning))
        self.index = next_index; self.playback_id = status.get('playback_id'); self.error = ''
        return self.decorate(status)

    async def control(self, operation, payload):
        # Stop/pause must not wait behind a slow automatic next-track extraction.
        if operation in {'stop', 'pause'} and self.task and not self.task.done():
            self.task.cancel()
            try:
                await self.task
            except asyncio.CancelledError:
                pass
            self.task = None
        async with self.lock:
            if operation == 'next':
                status = self.decorate(await self.service.music('status', {}))
                if not status['has_next']: raise ValueError('Inga fler låtar i kön')
                await self.service.music('pause', {})
                result = await self._advance()
                if self.tracks and (self.task is None or self.task.done()):
                    self.task = asyncio.create_task(self._watch())
                return result
            status = await self.service.music(operation, payload)
            if operation == 'stop':
                self.tracks = []; self.playback_id = None; self.error = ''
            if operation == 'resume':
                self.error = ''
                if self.tracks and (self.task is None or self.task.done()):
                    self.task = asyncio.create_task(self._watch())
            return self.decorate(status)

    async def tick(self):
        async with self.lock:
            status = self.decorate(await self.service.music('status', {}))
            if (self.tracks and not self.error and status.get('ended') is True
                    and status.get('state') == 'stopped' and not status.get('busy')):
                if self.index + 1 < len(self.tracks):
                    return await self._advance(automatic=True)
            return status

    async def _watch(self):
        while self.tracks:
            await asyncio.sleep(self.poll_seconds)
            try:
                status = await self.tick()
                if status.get('ended') and not status.get('has_next'):
                    return
            except (RuntimeError, ValueError, OSError, TimeoutError):
                # No blind retry/start after a control or extraction failure.
                self.error = 'Nästa låt kunde inte startas. Tryck Nästa låt för att försöka igen.'
                LOG.warning('robot_music_queue_advance_failed')
            except Exception:
                self.error = 'Spellistan kunde inte fortsätta. Prova Nästa låt.'
                LOG.warning('robot_music_queue_failed')
