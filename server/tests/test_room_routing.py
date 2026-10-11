import asyncio
import json
import pytest
from gateway.beam import plan_beam
from gateway.session import Phase, ProtocolError
from test_session import make_session, start_message

@pytest.mark.parametrize('room,target', [('uppe', 'samsung'), ('nere', 'nec')])
def test_room_power_and_linked_player(room, target):
    assert plan_beam('slå på tv:n', 'phone', room).arguments['uri'] == target
    assert plan_beam('stäng av tv', 'phone', room).arguments['uri'] == target
    assert plan_beam('pausa', 'phone', room).arguments['uri'] == 'android_tv_' + target
    assert plan_beam('hem på pucken', 'phone', room).arguments['uri'] == 'android_tv_' + target


def test_explicit_target_wins_and_unknown_never_defaults():
    assert plan_beam('slå på tv:n uppe', 'phone', 'nere').arguments['uri'] == 'samsung'
    assert plan_beam('pausa nere', 'phone', 'uppe').arguments['uri'] == 'android_tv_nec'
    assert plan_beam('slå på NEC', 'phone', 'uppe').arguments['uri'] == 'nec'
    for text in ('slå på tv:n i garaget', 'slå inte på tv:n', 'jag vill titta på Terminator', 'stäng av dammsugaren'):
        assert plan_beam(text, 'phone', 'nere') is None
    assert plan_beam('slå på tv:n', 'phone') is None


def test_room_is_per_session_and_changes_on_next_utterance():
    async def run():
        a, _ = make_session()
        b, _ = make_session()
        await a.handle_text(start_message())
        await b.handle_text(start_message())
        a.volume_target = 'samsung'
        await a._start_audio({'utterance_id': 'one', 'room': 'nere'})
        assert a.room == 'nere' and b.room == ''
        assert a.volume_target is None
        a.phase = Phase.READY
        await a._start_audio({'utterance_id': 'two', 'room': 'uppe'})
        assert a.room == 'uppe'
        a.phase = Phase.READY
        await a._start_audio({'utterance_id': 'three', 'room': ''})
        assert a.room == ''
    asyncio.run(run())


def test_invalid_room_does_not_start_recording():
    async def run():
        session, sent = make_session()
        await session.handle_text(start_message())
        with pytest.raises(ProtocolError, match='Välj Uppe eller Nere'):
            await session.handle_text(json.dumps({'type': 'audio.start', 'utterance_id': 'bad', 'room': 'garage'}))
        assert session.phase == Phase.READY
        assert session.room == ''
    asyncio.run(run())


def test_spoken_commands_follow_room_and_explicit_override(tmp_path):
    from test_volume_voice import service
    from test_remotes import Node
    async def run():
        session, sent = make_session()
        session.remotes = service(tmp_path)
        upstairs = Node()
        session.remotes.target_nodes['samsung'] = upstairs
        session.authenticated_user = 'nichlas'
        session.remote_peer_trusted = True
        await session.handle_text(start_message())
        async def say(text, room):
            async def transcribe(*args, **kwargs):
                return text
            session.stt.transcribe = transcribe
            sent.clear()
            await session.handle_text(json.dumps({'type': 'audio.start', 'utterance_id': 'v', 'room': room}))
            await session.handle_binary(bytes(640))
            await session.handle_text(json.dumps({'type': 'audio.end', 'utterance_id': 'v'}))
            await session.response_task
        await say('sänk volymen', 'nere')
        assert session.remotes.node.calls[-1][:2] == ('fast', 'volume_down')
        assert not upstairs.calls
        await say('höj volymen uppe', 'nere')
        assert upstairs.calls[-1][:2] == ('fast', 'volume_up')
        await say('sänk volymen', 'nere')
        assert len(session.remotes.node.calls) == 2 and len(upstairs.calls) == 1
        await say('sänk volymen', 'uppe')
        assert len(upstairs.calls) == 2
        await say('slå på tv:n', 'uppe')
        action = next(m for m in sent if isinstance(m, dict) and m.get('name') == 'beam.control')
        assert action['arguments']['uri'] == 'samsung'
        await say('pausa', 'nere')
        action = next(m for m in sent if isinstance(m, dict) and m.get('name') == 'beam.control')
        assert action['arguments']['uri'] == 'android_tv_nec'
    asyncio.run(run())
