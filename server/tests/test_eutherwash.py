import asyncio
import json

import httpx
import pytest

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
