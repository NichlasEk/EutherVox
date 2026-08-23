from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from gateway.eutherpump import EutherPumpService


def settings(**overrides: object) -> dict:
    result = {
        "enabled": True,
        "base_url": "http://127.0.0.1:8794",
        "timeout_seconds": 2,
        "pumps": [
            {"id": "vardagsrum", "name": "Värmepumpen", "room": "vardagsrummet"}
        ],
    }
    result.update(overrides)
    return result


def test_rejects_public_or_credential_bearing_service_urls() -> None:
    with pytest.raises(ValueError, match="lokala nätet"):
        EutherPumpService(settings(base_url="https://8.8.8.8:8794"))
    with pytest.raises(ValueError, match="enkel lokal"):
        EutherPumpService(settings(base_url="http://user:secret@127.0.0.1:8794"))


def test_reads_only_the_allowlisted_pump_and_formats_swedish_status() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/v1/pumps/vardagsrum/state"
        return httpx.Response(200, json={
            "pump_id": "vardagsrum",
            "name": "Värmepumpen",
            "room": "vardagsrummet",
            "online": True,
            "power": True,
            "mode": "heat",
            "target_temperature": 21.5,
            "room_temperature": 19.75,
            "fan_mode": "auto",
            "swing": "off",
        })

    async def scenario() -> None:
        service = EutherPumpService(
            settings(), transport=httpx.MockTransport(handler)
        )
        assert service.list_public() == [{
            "id": "vardagsrum",
            "name": "Värmepumpen",
            "room": "vardagsrummet",
        }]
        assert await service.status_text("vardagsrummet") == (
            "Värmepumpen i vardagsrummet är på, det är 19.75 grader i rummet, "
            "börvärdet är 21.5 grader."
        )
        with pytest.raises(ValueError, match="Ingen konfigurerad"):
            await service.state("garaget")

    asyncio.run(scenario())


def test_rejects_state_for_a_different_pump_identity() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=json.dumps({"pump_id": "annan"}))

    async def scenario() -> None:
        service = EutherPumpService(
            settings(), transport=httpx.MockTransport(handler)
        )
        with pytest.raises(RuntimeError, match="fel pumpidentitet"):
            await service.state("Värmepumpen")

    asyncio.run(scenario())


def test_control_sends_only_requested_changes_and_requires_matching_readback() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "PATCH"
        assert json.loads(request.content) == {"power": True, "target_temperature": 21}
        return httpx.Response(200, json={
            "status": "completed",
            "state": {"pump_id": "vardagsrum", "online": True, "power": True, "target_temperature": 21},
        })

    async def scenario() -> None:
        service = EutherPumpService(settings(), transport=httpx.MockTransport(handler))
        state = await service.control("Värmepumpen", {"power": True, "target_temperature": 21})
        assert state["target_temperature"] == 21

    asyncio.run(scenario())


def test_voice_control_resolves_relative_temperature_and_fan_under_lock() -> None:
    requests: list[tuple[str, dict[str, object] | None]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content) if request.content else None
        requests.append((request.method, payload))
        if request.method == "GET":
            return httpx.Response(200, json={
                "pump_id": "vardagsrum", "online": True,
                "target_temperature": 22, "fan_mode": "2",
            })
        assert payload == {"target_temperature": 24, "fan_mode": "3"}
        return httpx.Response(200, json={
            "status": "completed",
            "state": {
                "pump_id": "vardagsrum", "online": True,
                "target_temperature": 24, "fan_mode": "3",
            },
        })

    async def scenario() -> None:
        service = EutherPumpService(settings(), transport=httpx.MockTransport(handler))
        state = await service.control_voice(
            "Värmepumpen", {"temperature_delta": 2, "fan_delta": 1}
        )
        assert state["target_temperature"] == 24
        assert requests == [
            ("GET", None),
            ("PATCH", {"target_temperature": 24, "fan_mode": "3"}),
        ]

    asyncio.run(scenario())


def test_relative_fan_handles_auto_and_limits() -> None:
    assert EutherPumpService._relative_fan_mode("auto", 1) == "3"
    assert EutherPumpService._relative_fan_mode("auto", -1) == "quiet"
    assert EutherPumpService._relative_fan_mode("5", 1) == "5"
    assert EutherPumpService._relative_fan_mode("quiet", -1) == "quiet"
