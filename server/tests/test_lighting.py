from __future__ import annotations

import asyncio
from pathlib import Path

from gateway.lighting import MagicHomeLightService, TomlLightStore


def test_toml_light_store_roundtrips_and_resolves_names_and_rooms(tmp_path: Path):
    store = TomlLightStore({"config_file": "lights.toml"}, tmp_path)
    first = store.upsert(name="Fönstret", room="Vardagsrummet", host="192.168.1.10", mac="AA:BB:CC:DD:EE:01", model="AK001-ZJ200")
    store.upsert(name="Bokhyllan", room="Vardagsrummet", host="192.168.1.11", mac="AABBCCDDEE02", model="AK001-ZJ200")
    renamed = store.upsert(name="TV-bänken", room="Vardagsrummet", host="192.168.1.10", mac="AABBCCDDEE01", model="AK001-ZJ200")

    assert renamed.light_id == first.light_id
    assert [light.name for light in store.resolve("vardagsrummet")] == ["Bokhyllan", "TV-bänken"]
    assert store.resolve("tv-bänken")[0].host == "192.168.1.10"
    assert store.path.stat().st_mode & 0o777 == 0o600


def test_protocol_packets_and_brightness_are_allowlisted(tmp_path: Path):
    service = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, tmp_path)

    assert service._power_packet(True) == bytes.fromhex("71 23 0f a3")
    assert service._color_packet(255, 0, 96) == bytes.fromhex("31 ff 00 60 00 f0 0f 8f")
    assert service._parse_color("#12aBcD") == (0x12, 0xAB, 0xCD)


def test_store_rejects_public_and_loopback_targets(tmp_path: Path):
    store = TomlLightStore({"config_file": "lights.toml"}, tmp_path)
    for host in ("127.0.0.1", "8.8.8.8", "::1"):
        try:
            store.upsert(name="X", room="Y", host=host, mac="AABBCCDDEE01", model="AK001-ZJ200")
            assert False, host
        except ValueError as error:
            assert "RFC1918" in str(error)


def test_service_sends_scaled_color_to_only_configured_target(tmp_path: Path):
    service = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, tmp_path)
    service.upsert(name="Fönstret", room="Sovrummet", host="192.168.1.10", mac="AABBCCDDEE01", model="AK001-ZJ200")
    packets = []

    async def capture(host: str, packet: bytes):
        packets.append((host, packet))

    service._send = capture
    message = asyncio.run(service.set_light("fönstret", color="#804020", brightness=50))

    assert packets == [("192.168.1.10", service._color_packet(64, 32, 16))]
    assert "50 procent" in message


def test_effect_turns_light_on_before_sending_allowlisted_pattern(tmp_path: Path):
    service = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, tmp_path)
    service.upsert(name="Fönstret", room="Sovrummet", host="192.168.1.10", mac="AABBCCDDEE01", model="AK001-ZJ200")
    packets = []

    async def capture(host: str, packet: bytes):
        packets.append((host, packet))

    service._send = capture
    asyncio.run(service.set_effect("Fönstret", "rainbow_fade", 40))

    assert packets == [
        ("192.168.1.10", service._power_packet(True)),
        ("192.168.1.10", bytes.fromhex("61 25 13 0f a8")),
    ]


def test_strobe_uses_symmetric_custom_effect_with_visible_speed(tmp_path: Path):
    service = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, tmp_path)
    service.upsert(name="Fönstret", room="Sovrummet", host="192.168.1.10", mac="AABBCCDDEE01", model="AK001-ZJ200")
    packets = []

    async def capture(host: str, packet: bytes):
        packets.append((host, packet))

    service._send = capture
    asyncio.run(service.set_effect("Fönstret", "red_strobe", 40))

    packet = packets[1][1]
    assert len(packet) == 70
    assert packet[:12] == bytes.fromhex("51 ff 00 00 00 00 00 00 00 ff 00 00")
    assert packet[-5:-1] == bytes.fromhex("13 3b ff 0f")
    assert packet[-1] == sum(packet[:-1]) & 0xFF


def test_brightness_only_preserves_current_hue(tmp_path: Path):
    service = MagicHomeLightService({"enabled": True, "config_file": "lights.toml"}, tmp_path)
    service.upsert(name="Fönstret", room="Sovrummet", host="192.168.1.10", mac="AABBCCDDEE01", model="AK001-ZJ200")
    packets = []

    async def query(_host: str) -> bytes:
        return bytes.fromhex("81 04 23 61 01 0f 40 20 10 00 05 00 f0 00")

    async def capture(host: str, packet: bytes):
        packets.append((host, packet))

    service._query = query
    service._send = capture
    asyncio.run(service.set_light("Fönstret", brightness=50))

    assert packets == [("192.168.1.10", service._color_packet(128, 64, 32))]
