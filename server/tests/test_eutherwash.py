import asyncio
import json

import httpx
import pytest
from pathlib import Path

from gateway.eutherwash import EutherWashService


def settings(**overrides):
    return {"enabled": True, "base_url": "http://192.168.32.186:8801", "alias": "tvattmaskinen", **overrides}


def test_rejects_public_or_credentialed_urls():
    with pytest.raises(ValueError):
        EutherWashService(settings(base_url="https://8.8.8.8:8801"))
    with pytest.raises(ValueError):
        EutherWashService(settings(base_url="http://user:secret@192.168.32.186:8801"))


def test_reads_only_fixed_routes_and_drops_unknown_fields():
    paths = []
    def handler(request: httpx.Request):
        paths.append(request.url.path)
        if request.url.path.endswith("/status"):
            return httpx.Response(200, json={"online": True, "state": "idle", "updated_at": "2030-01-01T00:00:00Z", "device_uuid": "forbidden"})
        return httpx.Response(200, json={"samples_24h": 2, "cycles_completed_7d": 1, "raw": "forbidden"})
    service = EutherWashService(settings(), transport=httpx.MockTransport(handler))
    report = asyncio.run(service.report())
    assert paths == ["/v1/washers/tvattmaskinen/status", "/v1/washers/tvattmaskinen/statistics"]
    assert report["status"]["state"] == "idle"
    assert "device_uuid" not in report["status"]
    assert "raw" not in report["statistics"]


def test_renders_running_report_for_speech():
    def handler(request: httpx.Request):
        if request.url.path.endswith("/status"):
            return httpx.Response(200, json={
                "available": True, "online": True, "state": "running",
                "program": "cotton_40", "progress_percent": 61,
                "remaining_seconds": 29 * 60,
            })
        return httpx.Response(200, json={"cycles_completed_7d": 4, "running_minutes_7d": 330})
    service = EutherWashService(settings(), transport=httpx.MockTransport(handler))
    spoken = asyncio.run(service.status_text())
    assert "bomull" not in spoken
    assert "cotton 40" in spoken
    assert "61 procent" in spoken
    assert "29 minuter" in spoken
    assert "4 färdiga tvättar" in spoken


def test_control_uses_fixed_command_token_and_confirmation(tmp_path: Path):
    token_file = tmp_path / "control-token"
    token_file.write_text("synthetic-control-token-at-least-32-characters", encoding="utf-8")
    token_file.chmod(0o600)
    requests = []

    def handler(request: httpx.Request):
        requests.append(request)
        return httpx.Response(200, json={
            "command": "start",
            "confirmed": True,
            "status": {"available": True, "online": True, "state": "running", "remote_control_enabled": True},
        })

    service = EutherWashService(settings(
        control_enabled=True,
        control_token_file=str(token_file),
    ), transport=httpx.MockTransport(handler))
    status = asyncio.run(service.command("start", confirmed=True))

    assert requests[0].url.path == "/v1/washers/tvattmaskinen/commands/start"
    assert requests[0].headers["authorization"] == "Bearer synthetic-control-token-at-least-32-characters"
    assert status["state"] == "running"


def test_control_rejects_unconfirmed_start_before_network(tmp_path: Path):
    token_file = tmp_path / "control-token"
    token_file.write_text("synthetic-control-token-at-least-32-characters", encoding="utf-8")
    token_file.chmod(0o600)
    service = EutherWashService(settings(
        control_enabled=True,
        control_token_file=str(token_file),
    ), transport=httpx.MockTransport(lambda _request: pytest.fail("must not call network")))
    with pytest.raises(ValueError, match="bekräftelse"):
        asyncio.run(service.command("start"))


def test_vacuum_control_uses_only_allowlisted_commands_and_confirmation(tmp_path: Path):
    token_file = tmp_path / "control-token"
    token_file.write_text("synthetic-control-token-at-least-32-characters", encoding="utf-8")
    token_file.chmod(0o600)
    requests = []

    def handler(request: httpx.Request):
        requests.append(request)
        command = request.url.path.rsplit("/", 1)[-1]
        return httpx.Response(200, json={
            "command": command,
            "confirmed": request.content == b'{"confirmed":true}',
            "accepted": True,
            "status": {"available": True, "online": True, "state": "idle"},
        })

    service = EutherWashService(settings(
        control_enabled=True,
        control_token_file=str(token_file),
    ), transport=httpx.MockTransport(handler))
    for command in ("pause", "stop", "return-to-dock"):
        asyncio.run(service.vacuum_command(command))
    asyncio.run(service.vacuum_command("start", confirmed=True))
    asyncio.run(service.vacuum_command("start-fast-mapping", confirmed=True))

    assert [request.url.path.rsplit("/", 1)[-1] for request in requests] == [
        "pause", "stop", "return-to-dock", "start", "start-fast-mapping",
    ]
    assert [request.content for request in requests] == [
        b'{"confirmed":false}', b'{"confirmed":false}', b'{"confirmed":false}',
        b'{"confirmed":true}', b'{"confirmed":true}',
    ]


def test_vacuum_control_rejects_unknown_and_unconfirmed_motion(tmp_path: Path):
    token_file = tmp_path / "control-token"
    token_file.write_text("synthetic-control-token-at-least-32-characters", encoding="utf-8")
    token_file.chmod(0o600)
    service = EutherWashService(settings(
        control_enabled=True,
        control_token_file=str(token_file),
    ), transport=httpx.MockTransport(lambda _request: pytest.fail("must not call network")))

    with pytest.raises(ValueError, match="Okänt"):
        asyncio.run(service.vacuum_command("drive-anywhere", confirmed=True))
    for command in ("start", "start-fast-mapping"):
        with pytest.raises(ValueError, match="bekräftelse"):
            asyncio.run(service.vacuum_command(command))


def test_vacuum_maps_uses_fixed_route_and_drops_cloud_metadata():
    paths = []
    def handler(request: httpx.Request):
        paths.append(request.url.path)
        return httpx.Response(200, json={
            "available": True, "offline_ready": True, "updated_at": "2026-08-24T20:00:00Z",
            "object_name": "forbidden-cloud-object",
            "maps": [{
                "index": 1, "selected": True, "name": "Hemma", "width": 20, "height": 10,
                "cell_size_mm": 50, "rotation": 0,
                "runs": [{"x": 1, "y": 2, "length": 4, "kind": "floor", "room_id": 1, "raw": "forbidden"}],
                "rooms": [{"id": 1, "name": "Kök", "secret": "forbidden"}],
                "robot": {"x": 3.5, "y": 4.5, "angle": 90, "device_id": "forbidden"},
                "charger": None, "md5": "forbidden",
            }],
        })
    service = EutherWashService(settings(), transport=httpx.MockTransport(handler))
    maps = asyncio.run(service.vacuum_maps())
    assert paths == ["/v1/vacuums/dammsugaren/maps"]
    assert maps["maps"][0]["rooms"] == [{"id": 1, "name": "Kök"}]
    assert maps["maps"][0]["robot"] == {"x": 3.5, "y": 4.5, "angle": 90}
    assert "object_name" not in maps and "md5" not in maps["maps"][0]
