from __future__ import annotations

import asyncio
import json
from uuid import UUID

import httpx
import pytest

from gateway.cast import CastService
from gateway.mcp_server import build_mcp_server
from gateway.tool_planner import OllamaToolPlanner
from gateway.tools import EutherVoxToolRegistry, ToolValidationError
from gateway.lighting import MagicHomeLightService
from gateway.television import NecTvService
from gateway.eutherpump import EutherPumpService
from gateway.eutherwash import EutherWashService
from pathlib import Path


def make_registry(tmp_path: Path | None = None) -> EutherVoxToolRegistry:
    cast = CastService({
        "enabled": True,
        "rooms": {
            "köket": {
                "friendly_name": "Kök 2",
                "host": "192.0.2.5",
                "port": 8009,
                "uuid": str(UUID(int=1)),
                "model_name": "Google Nest Mini",
            }
        },
    })
    root = tmp_path or Path("/tmp/euthervox-tool-tests")
    lights = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, root)
    lights.upsert(name="Fönster", room="köket", host="192.168.1.20", mac="AABBCCDDEE20", model="AK001-ZJ200")
    television = NecTvService({"enabled": True, "config_file": "tvs.toml", "scan_network": "192.168.1.0/24"}, root)
    television.upsert(name="Stora TV:n", room="vardagsrummet", host="192.168.1.40")
    eutherpump = EutherPumpService({
        "enabled": True,
        "base_url": "http://127.0.0.1:8794",
        "pumps": [{"id": "vardagsrum", "name": "Värmepumpen", "room": "vardagsrummet"}],
    })
    eutherwash = EutherWashService({
        "enabled": True,
        "base_url": "http://127.0.0.1:8801",
        "alias": "tvattmaskinen",
    })
    return EutherVoxToolRegistry(cast, lights, television, eutherpump, eutherwash)


def test_registry_creates_allowlisted_music_and_confirmed_playlist_actions():
    registry = make_registry()

    music = registry.create_action("music_play", {"query": " mörk   synth ", "output_room": "Kök 2"}, "pixel")
    playlist = registry.create_action("playlist_create", {"description": "cyberpunk"}, "pixel")
    wikipedia = registry.create_action(
        "wikipedia_lookup",
        {"query": "Skinnskatteberg", "mode": "summary"},
        "pixel",
    )

    assert music.name == "media.play"
    assert music.arguments == {"provider": "youtube_music", "query": "mörk synth", "output_room": "köket"}
    assert music.target_node == "pixel"
    assert playlist.name == "playlist.create"
    assert playlist.requires_confirmation is True
    assert wikipedia.name == "knowledge.wikipedia"
    assert wikipedia.arguments == {"query": "Skinnskatteberg", "mode": "summary"}


def test_registry_rejects_unknown_tools_rooms_and_arguments():
    registry = make_registry()

    for name, arguments in (
        ("shell", {"query": "id"}),
        ("music_play", {"query": "ambient", "output_room": "serverrummet"}),
        ("music_play", {"query": "ambient", "host": "192.0.2.9"}),
    ):
        try:
            registry.create_action(name, arguments, "pixel")
            assert False, (name, arguments)
        except ToolValidationError:
            pass


def test_registry_creates_only_toml_targeted_light_actions(tmp_path: Path):
    registry = make_registry(tmp_path)

    color = registry.create_action(
        "light_set", {"target": "köket", "color": "#7a18c4", "brightness": 35}, "pixel"
    )
    effect = registry.create_action(
        "light_effect", {"target": "Fönster", "effect": "rainbow_fade", "speed": 40}, "pixel"
    )

    assert color.name == "lights.set"
    assert color.arguments == {"target": "Fönster", "color": "#7A18C4", "brightness": 35}
    assert effect.name == "lights.effect"
    assert "host" not in color.arguments
    try:
        registry.create_action("light_set", {"target": "garaget", "power": True}, "pixel")
        assert False
    except ToolValidationError:
        pass


def test_registry_creates_only_allowlisted_toml_targeted_tv_actions(tmp_path: Path):
    registry = make_registry(tmp_path)
    action = registry.create_action("tv_control", {"target": "vardagsrummet", "command": "input_hdmi2"}, "pixel")
    assert action.name == "tv.control"
    assert action.arguments == {"target": "Stora TV:n", "command": "input_hdmi2"}
    assert "host" not in action.arguments
    for arguments in ({"target": "Stora TV:n", "command": "raw_hex"}, {"target": "okänd", "command": "power_on"}):
        try:
            registry.create_action("tv_control", arguments, "pixel")
            assert False
        except ToolValidationError:
            pass


def test_registry_creates_only_allowlisted_read_only_pump_status_actions(tmp_path: Path):
    registry = make_registry(tmp_path)
    action = registry.create_action(
        "heat_pump_status", {"target": "vardagsrummet"}, "pixel"
    )
    assert action.name == "pump.status"
    assert action.arguments == {"target": "Värmepumpen"}
    assert "base_url" not in action.arguments
    with pytest.raises(ToolValidationError):
        registry.create_action(
            "heat_pump_status", {"target": "garaget"}, "pixel"
        )


def test_registry_validates_allowlisted_pump_control_actions(tmp_path: Path):
    registry = make_registry(tmp_path)
    action = registry.create_action(
        "heat_pump_control",
        {"target": "vardagsrummet", "power": True, "mode": "heat", "target_temperature": 23},
        "pixel",
    )
    assert action.name == "pump.control"
    assert action.arguments == {
        "target": "Värmepumpen", "power": True, "mode": "heat", "target_temperature": 23,
    }
    assert "base_url" not in action.arguments
    for arguments in (
        {"target": "Värmepumpen", "target_temperature": 31},
        {"target": "Värmepumpen", "temperature_delta": 0},
        {"target": "Värmepumpen", "target_temperature": 22, "temperature_delta": 1},
        {"target": "Värmepumpen", "fan_mode": "turbo"},
        {"target": "Värmepumpen", "host": "192.168.32.186"},
    ):
        with pytest.raises(ToolValidationError):
            registry.create_action("heat_pump_control", arguments, "pixel")


def test_mcp_server_exposes_only_safe_tools_and_hides_cast_network_details():
    async def scenario():
        registry = make_registry()
        server = build_mcp_server(registry)
        tools = await server.list_tools()

        assert {tool.name for tool in tools} == {
            "cast_list_targets", "lights_list", "light_set", "light_effect",
            "tvs_list", "tvs_discover", "tv_control", "music_play", "playlist_create", "wikipedia_lookup",
            "heat_pumps_list", "heat_pump_status",
        }
        target = registry.list_cast_targets()[0]
        assert target == {"room": "köket", "display_name": "Kök 2", "model": "Google Nest Mini"}
        assert "host" not in target

    asyncio.run(scenario())


def test_ollama_tool_planner_translates_one_tool_call_to_validated_action():
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert request.url.path == "/api/chat"
        assert payload["think"] is False
        assert {tool["function"]["name"] for tool in payload["tools"]} == {
            "music_play", "playlist_create", "wikipedia_lookup", "light_set", "light_effect", "tv_control",
            "heat_pump_status", "heat_pump_control",
            "washer_status",
        }
        return httpx.Response(200, json={
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "function": {
                        "name": "music_play",
                        "arguments": {"query": "mörk cyberpunk", "output_room": "köket"},
                    }
                }],
            },
            "done": True,
        })

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(),
            "http://ollama.test",
            "qwen-test",
            transport=httpx.MockTransport(handler),
        )
        action = await planner.plan("Jag är sugen på mörk cyberpunk i köket", "pixel")
        assert action is not None
        assert action.name == "media.play"
        assert action.arguments["output_room"] == "köket"

    asyncio.run(scenario())


@pytest.mark.parametrize(("transcript", "expected"), [
    ("Höj temperaturen till 24 grader", {"target_temperature": 24}),
    ("Sänk temperaturen med två grader", {"temperature_delta": -2}),
    ("Jag fryser", {"temperature_delta": 1}),
    ("Jag svettas", {"temperature_delta": -1}),
    ("Slå på värmen", {"power": True, "mode": "heat"}),
    ("Slå på kyla", {"power": True, "mode": "cool"}),
    ("Sätt på pumpen", {"power": True}),
    ("Stäng av värmepumpen", {"power": False}),
    ("Mer fläkt", {"fan_delta": 1}),
    ("Mindre fläkt", {"fan_delta": -1}),
    ("Ställ fläkten på 4", {"fan_mode": "4"}),
    ("Kan du fixa luften?", {"fan_delta": 1}),
])
def test_deterministic_pump_phrases(transcript: str, expected: dict[str, object]):
    planner = OllamaToolPlanner(make_registry(), "http://ollama.test", "qwen-test")
    action = planner.plan_deterministic(transcript, "pixel")
    assert action is not None
    assert action.name == "pump.control"
    assert action.arguments == {"target": "Värmepumpen", **expected}


@pytest.mark.parametrize(("transcript", "expected_name"), [
    ("Ge mig en tvättrapport", "washer.status"),
    ("Hur går tvätten?", "washer.status"),
    ("Vad säger värmepumpen?", "pump.status"),
    ("Ge mig en värmepumpsrapport", "pump.status"),
])
def test_deterministic_spoken_reports(transcript: str, expected_name: str):
    planner = OllamaToolPlanner(make_registry(), "http://ollama.test", "qwen-test")
    action = planner.plan_deterministic(transcript, "pixel")
    assert action is not None
    assert action.name == expected_name


def test_ollama_tool_planner_skips_non_actionable_conversation_without_request():
    async def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("Ollama must not be called for ordinary conversation")

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(),
            "http://ollama.test",
            "qwen-test",
            transport=httpx.MockTransport(handler),
        )
        assert await planner.plan("Hur mår du i dag?", "pixel") is None

    asyncio.run(scenario())


def test_ollama_tool_planner_translates_wikipedia_request_to_read_only_action():
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert "wikipedia_lookup" in {tool["function"]["name"] for tool in payload["tools"]}
        return httpx.Response(200, json={
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "function": {
                        "name": "wikipedia_lookup",
                        "arguments": {"query": "Skinnskatteberg", "mode": "summary"},
                    }
                }],
            },
            "done": True,
        })

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(),
            "http://ollama.test",
            "qwen-test",
            transport=httpx.MockTransport(handler),
        )
        action = await planner.plan("Berätta om Skinnskatteberg", "pixel")

        assert action is not None
        assert action.name == "knowledge.wikipedia"
        assert action.arguments == {"query": "Skinnskatteberg", "mode": "summary"}

    asyncio.run(scenario())


def test_ollama_tool_planner_maps_natural_light_request_to_toml_target(tmp_path: Path):
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert "Fönster i köket" in payload["messages"][0]["content"]
        return httpx.Response(200, json={
            "message": {
                "role": "assistant",
                "content": "",
                "tool_calls": [{
                    "function": {
                        "name": "light_set",
                        "arguments": {"target": "köket", "color": "#6B20A8", "brightness": 30},
                    }
                }],
            },
            "done": True,
        })

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(tmp_path), "http://ollama.test", "qwen-test",
            transport=httpx.MockTransport(handler),
        )
        action = await planner.plan("Jag vill ha dovt lila sken i köket på trettio procent", "pixel")
        assert action is not None
        assert action.name == "lights.set"
        assert action.arguments == {"target": "Fönster", "color": "#6B20A8", "brightness": 30}

    asyncio.run(scenario())


def test_tv_planner_handles_joined_nec_tv_stt_variant_without_ollama(tmp_path: Path):
    async def fail_if_called(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("deterministic TV commands must not call Ollama")

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(tmp_path), "http://ollama.test", "qwen-test",
            transport=httpx.MockTransport(fail_if_called),
        )
        action = await planner.plan("Kan du sätta på Necteven?", "pixel")
        assert action is not None
        assert action.name == "tv.control"
        assert action.arguments == {"target": "Stora TV:n", "command": "power_on"}

    asyncio.run(scenario())


def test_light_planner_handles_saved_room_and_joined_stt_color_without_ollama(tmp_path: Path):
    async def fail_if_called(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("deterministic light commands must not call Ollama")

    async def scenario():
        registry = make_registry(tmp_path)
        registry.lights.upsert(
            name="Skrivbord", room="Sigrids rum", host="192.168.1.20",
            mac="AABBCCDDEE20", model="AK001-ZJ200",
        )
        planner = OllamaToolPlanner(
            registry, "http://ollama.test", "qwen-test",
            transport=httpx.MockTransport(fail_if_called),
        )

        action = await planner.plan("Kan du göra ljuset i Sigrids rumröt?", "pixel")

        assert action is not None
        assert action.name == "lights.set"
        assert action.arguments == {"target": "Skrivbord", "color": "#FF0000"}

    asyncio.run(scenario())


def test_light_planner_tolerates_small_stt_error_in_configured_room(tmp_path: Path):
    async def scenario():
        registry = make_registry(tmp_path)
        registry.lights.upsert(
            name="Skrivbord", room="Sigrids rum", host="192.168.1.20",
            mac="AABBCCDDEE20", model="AK001-ZJ200",
        )
        registry.lights.upsert(
            name="Bokhylla", room="Estrids rum", host="192.168.1.21",
            mac="AABBCCDDEE21", model="AK001-ZJ200",
        )
        planner = OllamaToolPlanner(registry, "http://ollama.test", "qwen-test")

        action = await planner.plan("Kan du göra ljuset i sigdidsrum rött?", "pixel")

        assert action is not None
        assert action.name == "lights.set"
        assert action.arguments == {"target": "Skrivbord", "color": "#FF0000"}

    asyncio.run(scenario())


def test_light_planner_recovers_expanded_room_name_from_weekend_stt_log(tmp_path: Path):
    async def fail_if_called(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("a confident configured target must not fall through to Ollama")

    async def scenario():
        registry = make_registry(tmp_path)
        registry.lights.upsert(
            name="Skrivbord", room="Sigrids rum", host="192.168.1.20",
            mac="AABBCCDDEE20", model="AK001-ZJ200",
        )
        registry.lights.upsert(
            name="Bokhylla", room="Estrids rum", host="192.168.1.21",
            mac="AABBCCDDEE21", model="AK001-ZJ200",
        )
        planner = OllamaToolPlanner(
            registry, "http://ollama.test", "qwen-test",
            transport=httpx.MockTransport(fail_if_called),
        )

        action = await planner.plan("Tänd lyset i Esterhilds.", "pixel")

        assert action is not None
        assert action.name == "lights.set"
        assert action.arguments == {"target": "Bokhylla", "power": True}

    asyncio.run(scenario())


def test_light_target_matcher_refuses_ambiguous_room_guess():
    targets = [
        {"name": "Första", "room": "Estrids rum"},
        {"name": "Andra", "room": "Astrids rum"},
    ]

    assert OllamaToolPlanner._match_light_target("Tänd lyset i Strids rum", targets) == ""


def test_light_planner_clarifies_an_ambiguous_spoken_room(tmp_path: Path):
    async def scenario():
        registry = make_registry(tmp_path)
        registry.lights.upsert(
            name="Första", room="Estrids rum", host="192.168.1.21",
            mac="AABBCCDDEE21", model="AK001-ZJ200",
        )
        registry.lights.upsert(
            name="Andra", room="Astrids rum", host="192.168.1.22",
            mac="AABBCCDDEE22", model="AK001-ZJ200",
        )
        planner = OllamaToolPlanner(registry, "http://ollama.test", "qwen-test")

        action = await planner.plan("Tänd ljuset i Strids rum", "pixel")

        assert action is not None
        assert action.name == "assistant.clarify"
        assert "Estrids rum" in action.acknowledgement
        assert "Astrids rum" in action.acknowledgement

    asyncio.run(scenario())


def test_light_planner_does_not_capture_an_unrelated_make_request(tmp_path: Path):
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"message": {"role": "assistant", "content": ""}, "done": True})

    async def scenario():
        planner = OllamaToolPlanner(
            make_registry(tmp_path), "http://ollama.test", "qwen-test",
            transport=httpx.MockTransport(handler),
        )

        assert await planner.plan("Gör en spellista med cyberpunk", "pixel") is None

    asyncio.run(scenario())


def test_light_planner_maps_room_brightness_and_effect_speed(tmp_path: Path):
    async def scenario():
        registry = make_registry(tmp_path)
        registry.lights.upsert(
            name="Skrivbord", room="Sigrids rum", host="192.168.1.20",
            mac="AABBCCDDEE20", model="AK001-ZJ200",
        )
        planner = OllamaToolPlanner(registry, "http://ollama.test", "qwen-test")

        dim = await planner.plan("Ställ Sigrids rum på lila och trettio procent", "pixel")
        blink = await planner.plan("Låt Sigrids rum blinka blått långsamt", "pixel")

        assert dim is not None
        assert dim.arguments == {"target": "Skrivbord", "color": "#7A18C4", "brightness": 30}
        assert blink is not None
        assert blink.name == "lights.effect"
        assert blink.arguments == {"target": "Skrivbord", "effect": "blue_strobe", "speed": 20}

    asyncio.run(scenario())
