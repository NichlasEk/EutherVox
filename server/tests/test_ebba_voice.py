import asyncio
import json
import pytest
import httpx
from gateway.tool_planner import OllamaToolPlanner
from test_tools import make_registry
from test_session import make_session, start_message

class Vacuum:
    enabled = True
    control_enabled = True
    def __init__(self): self.calls = []
    async def vacuum_command(self, command, *, confirmed=False):
        self.calls.append((command, confirmed))
        return {"online": True, "state": "returning"}

def planner(vacuum):
    registry = make_registry()
    registry.eutherwash = vacuum
    return OllamaToolPlanner(registry, "http://unused.test", "test")

@pytest.mark.parametrize("phrase,command", [
    ("Starta Ebba", "start"), ("Ebba, städa!", "start"),
    ("Kan du pausa Ebba?", "pause"), ("Stoppa Ebba", "stop"),
    ("Skicka hem Ebba", "return-to-dock"), ("Ebba åk hem", "return-to-dock"),
])
def test_explicit_commands(phrase, command):
    action = planner(Vacuum()).plan_deterministic(phrase, "phone")
    assert action.name == "vacuum.control"
    assert action.arguments == {"command": command}

@pytest.mark.parametrize("phrase", ["Starta inte Ebba", "Om jag säger starta Ebba", "Starta Ebba i köket", "Ebba är rolig"])
def test_not_commands(phrase):
    assert planner(Vacuum()).plan_deterministic(phrase, "phone") is None

def test_ebba_status():
    assert planner(Vacuum()).plan_deterministic("Hur mår Ebba?", "phone").name == "vacuum.status"

def test_model_cannot_invent_motion():
    p = planner(Vacuum())
    p.transport = httpx.MockTransport(lambda req: httpx.Response(200, json={"message": {"tool_calls": [{"function": {"name": "vacuum_control", "arguments": {"command": "start"}}}]}}))
    assert asyncio.run(p.plan("Spela musik", "phone")) is None

@pytest.mark.parametrize("authenticated", [False, True])
def test_voice_pipeline_authenticates_and_calls_local_service(authenticated):
    class Stt:
        async def transcribe(self, pcm, sample_rate): return "Starta Ebba"
    async def scenario():
        session, sent = make_session()
        v = Vacuum()
        session.stt = Stt()
        session.eutherwash = v
        session.tool_planner = planner(v)
        session.authenticated_user = "tester" if authenticated else None
        await session.handle_text(start_message())
        await session.handle_text(json.dumps({"type": "audio.start", "utterance_id": "ebba"}))
        await session.handle_binary(bytes(640))
        await session.handle_text(json.dumps({"type": "audio.end", "utterance_id": "ebba"}))
        await session.response_task
        assert v.calls == ([("start", True)] if authenticated else [])
        events = [x for x in sent if isinstance(x, dict)]
        assert any(x.get("type") == "action.completed" and x.get("status") == ("completed" if authenticated else "failed") for x in events)
        if authenticated:
            assert any(x.get("type") == "tts.start" for x in events)
    asyncio.run(scenario())
