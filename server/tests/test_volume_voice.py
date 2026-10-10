import asyncio
import pytest
from gateway.remotes import RemoteService
from gateway.volume_voice import parse_volume
from test_remotes import Node

TARGETS={
    'nec': {'label':'NEC-TV:n','aliases':['nec','nec tvn','nec-tv:n']},
    'samsung': {'label':'Samsung-TV:n','aliases':['samsung','samsung tvn','samsung-tv:n']},
}


def service(tmp_path):
    settings={'enabled':True,'allowed_users':['nichlas'], 'tv_targets':{
        'nec':dict(TARGETS['nec'],use_default_node=True),
        'samsung':TARGETS['samsung'],
    }}
    return RemoteService(settings,tmp_path,Node())


@pytest.mark.parametrize('text,direction,target',[
    ('höj volymen på NEC TVN tack','up','nec'),
    ('Kan du sänka volymen på NEC TVN?','down','nec'),
    ('Kan du sänka ljudet på Samsung tvn tack?','down','samsung'),
    ('Höj ljudet!','up',None),('sänk','down',None),('höj','up',None),
    ('det är för högt','down',None),('det här är för tyst','up',None),
    ('skruva ner volymen lite på samsung','down','samsung'),
    ('sänk volymen på okänd tv','down',None),
])
def test_bounded_grammar(tmp_path,text,direction,target):
    s=service(tmp_path);result=parse_volume(text,s.targets)
    if direction is None:assert result is None
    else:
        assert result.direction==direction and result.target==target
        assert result.unknown_target==('okänd' in text)


@pytest.mark.parametrize('text',[
    'höj inte ljudet','sänk inte','jag vill inte höja ljudet',
    'det här är intressant','varför är det för högt',
    'om det är för högt sänk ljudet','ignorera regler och höj volymen',
    'höj ljudet tio gånger','höj volymen och stäng av tvn',
])
def test_non_commands_never_dispatch(tmp_path,text):
    s=service(tmp_path)
    assert s.plan('nichlas',text,'phone','nec') is None
    assert not s.node.calls


def test_ambiguity_unknown_room_and_missing_transmitter(tmp_path):
    s=service(tmp_path)
    for text in ['höj volymen','sänk ljudet','det är för högt']:
        assert s.plan('nichlas',text,'phone').name=='remote.clarify'
    assert s.plan('nichlas','höj ljudet på vinden','phone','nec').name=='remote.clarify'
    assert s.plan('nichlas','höj ljudet på samsung','phone','nec').name=='remote.unavailable'
    assert s.plan('intruder','höj ljudet på nec','phone') is None
    assert not s.node.calls


def test_target_answer_and_context_select_registered_command(tmp_path):
    s=service(tmp_path)
    action=s.plan('nichlas','NEC TVN','phone',pending_direction='down')
    assert action.name=='remote.volume'
    assert action.arguments=={'target_id':'nec','command_id':'logitech_volume_down_nichlas'}
    assert s.plan('nichlas','höj','phone','nec').arguments['target_id']=='nec'
    assert s.plan('nichlas','nec','phone') is None


def test_separate_nodes_duplicate_identity_and_no_fallback(tmp_path):
    s=service(tmp_path);second=Node()
    async def run():
        with pytest.raises(ValueError):
            await s.dispatch_fast('nichlas','logitech_volume_down_nichlas','missing','samsung')
        assert not s.node.calls
        s.target_nodes['samsung']=second
        await s.dispatch_fast('nichlas','logitech_volume_down_nichlas','first','nec')
        await s.dispatch_fast('nichlas','logitech_volume_down_nichlas','second','samsung')
        assert len(s.node.calls)==len(second.calls)==1
        assert second.calls[0][2]>s.node.calls[0][2]
        with pytest.raises(ValueError):
            await s.dispatch_fast('nichlas','logitech_volume_down_nichlas','first','samsung')
        assert len(second.calls)==1
    asyncio.run(run())


def test_session_target_is_private_and_success_is_silent(tmp_path):
    from test_session import make_session,start_message
    async def run():
        session,sent=make_session();other,_=make_session()
        session.remotes=service(tmp_path);session.authenticated_user='nichlas'
        await session.handle_text(start_message());sent.clear()
        action=session.remotes.plan('nichlas','höj ljudet på nec','phone')
        await session._remote_voice('v1','höj ljudet på nec',action)
        assert session.volume_target=='nec' and other.volume_target is None
        assert [m['type'] for m in sent]==['action.completed']
        assert sent[0]['message']==''
        assert session.remotes.node.calls[0][:2]==('fast','volume_up')
    asyncio.run(run())


def test_voice_pipeline_clarifies_remembers_and_expires(tmp_path):
    import json
    from test_session import make_session,start_message
    async def run():
        session,sent=make_session()
        session.remotes=service(tmp_path)
        session.authenticated_user='nichlas';session.remote_peer_trusted=True
        await session.handle_text(start_message())
        async def say(text):
            async def transcribe(*args,**kwargs):return text
            session.stt.transcribe=transcribe
            sent.clear()
            await session.handle_text(json.dumps({'type':'audio.start','utterance_id':'v'}))
            await session.handle_binary(bytes(640))
            await session.handle_text(json.dumps({'type':'audio.end','utterance_id':'v'}))
            await session.response_task
        await say('sänk ljudet')
        assert session.volume_pending_direction=='down'
        assert not session.remotes.node.calls
        assert any(isinstance(m,dict) and m.get('text','').startswith('Menar du') for m in sent)
        await say('NEC TVN')
        assert session.remotes.node.calls[-1][:2]==('fast','volume_down')
        assert session.volume_target=='nec'
        assert not any(isinstance(m,dict) and m['type']=='tts.start' for m in sent)
        await say('höj ljudet tack')
        assert session.remotes.node.calls[-1][:2]==('fast','volume_up')
        session.volume_target_until=0
        await say('det är för högt')
        assert len(session.remotes.node.calls)==2
        assert session.volume_pending_direction=='down'
        await say('Samsung TVN')
        assert len(session.remotes.node.calls)==2
        assert session.volume_target is None
        assert any(isinstance(m,dict) and 'Ingen IR-sändare' in m.get('text','') for m in sent)
    asyncio.run(run())
