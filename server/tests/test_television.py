from __future__ import annotations

import asyncio
from pathlib import Path

from gateway.television import NecTvService, TomlTvStore


def test_tv_store_persists_and_resolves_name_or_room(tmp_path: Path):
    store = TomlTvStore({"config_file": "tvs.toml"}, tmp_path)
    saved = store.upsert(name="Stora TV:n", room="vardagsrummet", host="192.168.32.40")
    assert store.resolve("Stora TV:n") == saved
    assert store.resolve("vardagsrummet") == saved
    assert "192.168.32.40" in (tmp_path / "tvs.toml").read_text()


def test_nec_packets_match_necfjarr_reference():
    assert NecTvService.packet("power_on").hex().upper() == "01304130413043024332303344363030303103730D"
    assert NecTvService.packet("power_off").hex().upper() == "01304130413043024332303344363030303403760D"
    assert NecTvService.packet("input_hdmi1").hex().upper() == "0130413045304102303036303030313103720D"


def test_control_uses_toml_target_and_fixed_packet(tmp_path: Path):
    service = NecTvService({"enabled": True, "config_file": "tvs.toml"}, tmp_path)
    service.upsert(name="TV", room="salongen", host="192.168.32.50")
    calls = []
    async def exchange(host, port, packet):
        calls.append((host, port, packet))
    service._exchange = exchange
    result = asyncio.run(service.control("salongen", "input_hdmi3"))
    assert calls == [("192.168.32.50", 7142, NecTvService.packet("input_hdmi3"))]
    assert "HDMI 3" in result
    try:
        asyncio.run(service.control("salongen", "on"))
        assert False
    except ValueError:
        pass
    assert len(calls) == 1
