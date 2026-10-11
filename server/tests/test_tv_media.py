import asyncio
import base64
import json
import pytest
from gateway.tv_media import plan_tv_media, search_audio
from test_session import make_session, start_message
from test_volume_voice import service

@pytest.mark.parametrize('text,room,target,title,provider', [
    ('Jag vill titta på Terminator på NEC tvn', '', 'nec', 'Terminator', ''),
    ('Jag vill se Terminator 2 på Netflix', 'uppe', 'samsung', 'Terminator 2', 'Netflix'),
    ('Sök efter filmen Wall-E på Disney+ på tv:n nere', 'uppe', 'nec', 'Wall-E', 'Disney+'),
    ('Jag vill se filmen Alien på Samsung på Prime Video', 'nere', 'samsung', 'Alien', 'Prime Video'),
])
def test_film_title_service_and_explicit_destination(text, room, target, title, provider):
    action = plan_tv_media(text, 'phone', room)
    assert action.name == 'beam.search'
    assert action.arguments == {'provider': 'eutherbeam', 'uri': 'android_tv_'+target, 'query': title, 'search_service': provider}


def test_status_clarification_and_non_commands():
    assert plan_tv_media('vad tittar jag på?', 'phone', 'nere').name == 'beam.status'
    assert plan_tv_media('vad tittar jag på nere', 'phone', 'uppe').arguments['uri'] == 'android_tv_nec'
    assert plan_tv_media('jag vill se Alien på NEC på Samsung', 'phone', 'uppe').name == 'assistant.clarify'
    assert plan_tv_media('jag vill se Terminator', 'phone').name == 'assistant.clarify'
    assert plan_tv_media('jag vill se Terminator på tvn i garaget', 'phone', 'uppe').name == 'assistant.clarify'
    for text in ['Jag vill inte se Terminator', 'Berätta om Terminator', 'Sök efter vädret', 'Jag vill se', 'stäng av lampan']:
        assert plan_tv_media(text, 'phone', 'nere') is None


def test_search_audio_is_bounded_mono_8khz_and_uses_search_phrase():
    class Tts:
        sample_rate = 16000
        async def synthesize(self, text, character, rate):
            assert text == 'Sök efter filmen Terminator på Netflix'
            yield b'\x01\x00' * 16000
    result = asyncio.run(search_audio(Tts(), None, 'Terminator', 'Netflix'))
    assert len(base64.b64decode(result)) == 16000


def test_search_pipeline_only_dispatches_for_authorized_trusted_user(tmp_path):
    async def run():
        session, sent = make_session()
        session.remotes = service(tmp_path)
        session.authenticated_user = 'nichlas'
        session.remote_peer_trusted = True
        start = json.loads(start_message()); start['capabilities'] = ['tv_media_v1']
        await session.handle_text(json.dumps(start))
        send = session.send_json
        async def respond(message):
            await send(message)
            if message.get('name') == 'beam.search':
                await session._action_result({'action_id': message['action_id'], 'status': 'completed', 'message': 'Sökningen är skickad.'})
        session.send_json = respond
        async def transcribe(*args, **kwargs): return 'Jag vill se Terminator'
        session.stt.transcribe = transcribe
        await session.handle_text(json.dumps({'type': 'audio.start', 'utterance_id': 'film', 'room': 'nere'}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({'type': 'audio.end', 'utterance_id': 'film'}))
        await session.response_task
        action = next(m for m in sent if isinstance(m, dict) and m.get('name') == 'beam.search')
        assert action['arguments']['query'] == 'Terminator'
        assert action['arguments']['uri'] == 'android_tv_nec'
        assert base64.b64decode(action['arguments']['search_audio'])
        assert not session.remotes.node.calls
    asyncio.run(run())


def test_old_app_gets_upgrade_instead_of_unsupported_action(tmp_path):
    async def run():
        session, sent = make_session()
        session.remotes = service(tmp_path)
        session.authenticated_user = 'nichlas'; session.remote_peer_trusted = True
        await session.handle_text(start_message())
        async def transcribe(*args, **kwargs): return 'Jag vill se Terminator'
        session.stt.transcribe = transcribe
        await session.handle_text(json.dumps({'type':'audio.start', 'utterance_id':'old', 'room':'nere'}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({'type':'audio.end', 'utterance_id':'old'}))
        await session.response_task
        assert not any(isinstance(m,dict) and m.get('type') == 'action.request' for m in sent)
        assert any(isinstance(m,dict) and 'Uppdatera EutherVox' in m.get('text','') for m in sent)
        assert not session.remotes.node.calls
    asyncio.run(run())


@pytest.mark.parametrize('trusted,user', [(False, 'nichlas'), (True, 'outsider')])
def test_untrusted_or_unlisted_user_cannot_dispatch_tv_media(tmp_path, trusted, user):
    async def run():
        session, sent = make_session()
        session.remotes = service(tmp_path)
        session.authenticated_user = user; session.remote_peer_trusted = trusted
        start = json.loads(start_message()); start['capabilities'] = ['tv_media_v1']
        await session.handle_text(json.dumps(start))
        async def transcribe(*args, **kwargs): return 'Jag vill se Terminator'
        session.stt.transcribe = transcribe
        await session.handle_text(json.dumps({'type':'audio.start', 'utterance_id':'blocked', 'room':'nere'}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({'type':'audio.end', 'utterance_id':'blocked'}))
        await session.response_task
        assert not any(isinstance(m,dict) and m.get('name','').startswith('beam.') for m in sent)
    asyncio.run(run())
