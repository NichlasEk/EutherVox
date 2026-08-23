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
