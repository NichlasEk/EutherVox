import asyncio
from types import SimpleNamespace
import pytest
from gateway.robot_music_queue import RobotMusicQueue
from gateway.youtube_playlist import extract, playlist_id

TRACKS = [{'video_id': 'EUrvY1XggAI', 'title': 'One'}, {'video_id': 'ouQmlz3I3v8', 'title': 'Two'}]

class Backend:
    def __init__(self):
        self.calls = []; self.serial = 0
        self.status = dict(state='stopped', ended=False, busy=False, playback_id='', volume=50)
    async def music(self, operation, payload):
        self.calls.append(operation)
        if operation == 'play':
            self.serial += 1
            self.status.update(state='playing', ended=False, playback_id=str(self.serial), volume=payload['volume'])
        elif operation == 'pause': self.status.update(state='paused')
        elif operation == 'resume': self.status.update(state='playing')
        elif operation == 'stop': self.status.update(state='stopped', ended=False, playback_id='')
        elif operation == 'volume': self.status.update(volume=payload['volume'])
        return dict(self.status)

class Resolver:
    def __init__(self): self.calls = []
    async def resolve(self, video):
        self.calls.append(video)
        return SimpleNamespace(url='https://a.googlevideo.com/audio', title=video)

async def setup():
    backend = Backend(); resolver = Resolver()
    queue = RobotMusicQueue(backend, resolver, poll_seconds=999)
    await queue.start(TRACKS, 'Test', await resolver.resolve(TRACKS[0]['video_id']), 50, False)
    return queue, backend, resolver


def test_auto_next_only_after_natural_end_and_cleanup():
    async def run():
        q, b, r = await setup()
        for state, ended, busy in [('paused', False, False), ('error', False, False), ('stopped', False, False), ('stopped', True, True)]:
            b.status.update(state=state, ended=ended, busy=busy)
            await q.tick()
            assert b.serial == 1
        b.status.update(state='stopped', ended=True, busy=False, volume=80)
        result = await q.tick()
        assert b.serial == 2 and result['queue_index'] == 2 and not result['has_next']
        assert result['volume'] == 80 and len(r.calls) == 2
        b.status.update(state='stopped', ended=True)
        await q.tick()
        assert b.serial == 2
    asyncio.run(run())


def test_stop_clears_pending_queue_and_manual_next_works_while_paused():
    async def run():
        q, b, r = await setup()
        await q.control('pause', {})
        result = await q.control('next', {})
        assert result['queue_index'] == 2 and b.serial == 2
        result = await q.control('stop', {})
        assert result['queue_count'] == 0
        b.status.update(ended=True)
        await q.tick()
        assert b.serial == 2
    asyncio.run(run())


def test_foreign_playback_invalidates_queue():
    async def run():
        q, b, r = await setup()
        b.status.update(playback_id='other', ended=True, state='stopped')
        result = await q.tick()
        assert result['queue_count'] == 0 and b.serial == 1
    asyncio.run(run())


def test_native_pause_during_resolution_prevents_auto_start():
    async def run():
        q, b, r = await setup()
        b.status.update(ended=True, state='stopped')
        async def resolve(video):
            b.status.update(state='paused')
            return SimpleNamespace(url='https://a.googlevideo.com/audio', title=video)
        r.resolve = resolve
        await q.tick()
        assert b.serial == 1
    asyncio.run(run())


def test_extraction_failure_keeps_track_index_and_allows_retry():
    async def run():
        q, b, r = await setup()
        old = r.resolve
        async def fail(video): raise RuntimeError('Unavailable')
        r.resolve = fail
        with pytest.raises(RuntimeError): await q.control('next', {})
        assert q.index == 0 and b.status['state'] == 'paused'
        r.resolve = old
        result = await q.control('next', {})
        assert result['queue_index'] == 2
    asyncio.run(run())


def test_shared_queue_advances_without_phone_requests():
    async def run():
        q, b, r = await setup()
        q.task.cancel()
        try: await q.task
        except asyncio.CancelledError: pass
        q.poll_seconds = .001
        b.status.update(ended=True, state='stopped')
        q.task = asyncio.create_task(q._watch())
        for _ in range(100):
            if b.serial == 2: break
            await asyncio.sleep(.002)
        assert b.serial == 2
    asyncio.run(run())


def test_playlist_url_validation():
    assert playlist_id('https://music.youtube.com/playlist?list=PLabcdefgh12345') == 'PLabcdefgh12345'
    assert playlist_id('https://youtube.com/watch?v=EUrvY1XggAI&list=PLabcdefgh12345') == 'PLabcdefgh12345'
    assert playlist_id('https://evil.invalid/?list=PLabcdefgh12345') is None
    with pytest.raises(ValueError): playlist_id('https://youtube.com/playlist?list=RDabcdefgh12345')


def test_playlist_extract_bounds_and_filters():
    captured = {}
    class Downloader:
        def __init__(self, options): captured.update(options)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def extract_info(self, url, download):
            assert url.startswith('https://www.youtube.com/playlist?list=') and not download
            return dict(title='List', entries=[None, {'id':'bad'}, {'id':'EUrvY1XggAI','availability':'private'}] + [{'id':'ouQmlz3I3v8', 'title':'Song'}] * 70)
    result = extract('PLabcdefgh12345', Downloader)
    assert len(result['tracks']) == 50 and captured['playlistend'] == 50


def test_stop_interrupts_automatic_extraction_before_next_play():
    async def run():
        q, b, r = await setup()
        q.task.cancel()
        try: await q.task
        except asyncio.CancelledError: pass
        resolving = asyncio.Event()
        async def slow(video):
            resolving.set()
            await asyncio.Event().wait()
        r.resolve = slow
        q.poll_seconds = .001
        b.status.update(ended=True, state='stopped')
        q.task = asyncio.create_task(q._watch())
        await asyncio.wait_for(resolving.wait(), 1)
        result = await asyncio.wait_for(q.control('stop', {}), 1)
        assert result['queue_count'] == 0 and b.serial == 1
    asyncio.run(run())
