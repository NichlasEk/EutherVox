import asyncio
from dataclasses import replace
import json
import stat
import tomllib
import pytest
from gateway.appearance import AppearanceStore
from gateway.session import ProtocolError
from test_session import make_session, start_message


def test_preferences_are_private_isolated_and_durable(tmp_path):
    store = AppearanceStore(tmp_path)
    store.write('Alice', 'system_regis')
    store.write('Bob', 'classic')
    assert AppearanceStore(tmp_path).read('alice') == 'system_regis'
    assert store.read('Bob') == 'classic'
    assert tomllib.loads(store.path('Alice').read_text()) == {'appearance': {'theme': 'system_regis'}}
    assert stat.S_IMODE(store.path('Alice').stat().st_mode) == 0o600
    with pytest.raises(ValueError): store.write('Alice', 'unknown')
    assert store.read('Alice') == 'system_regis'
    assert store.path('../../outside').parent == store.root


def test_session_uses_authenticated_identity_and_returns_saved_theme(tmp_path):
    async def scenario():
        session, sent = make_session()
        session.config = replace(session.config, config_dir=tmp_path)
        session.authenticated_user = 'Alice'
        with pytest.raises(ProtocolError):
            await session.handle_text(json.dumps({'type':'appearance.set','theme':'system_regis'}))
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({'type':'appearance.set','theme':'system_regis','username':'Bob'}))
        assert sent[-1] == {'type':'appearance.saved','theme':'system_regis'}
        assert AppearanceStore(tmp_path).read('Alice') == 'system_regis'
        assert AppearanceStore(tmp_path).read('Bob') == 'classic'
        session, sent = make_session()
        session.config = replace(session.config, config_dir=tmp_path)
        session.authenticated_user = 'Alice'
        await session.handle_text(start_message())
        ready = [x for x in sent if isinstance(x,dict) and x.get('type')=='session.ready'][-1]
        assert ready['appearance_theme']=='system_regis'
        session.authenticated_user = ''
        with pytest.raises(ProtocolError):
            await session.handle_text(json.dumps({'type':'appearance.set','theme':'classic'}))
    asyncio.run(scenario())
