from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
import ipaddress
import json
from pathlib import Path
import re
import tomllib
from uuid import uuid4


EFFECTS = {
    "rainbow_fade": 0x25,
    "red_fade": 0x26,
    "green_fade": 0x27,
    "blue_fade": 0x28,
    "purple_fade": 0x2B,
    "rainbow_strobe": 0x30,
    "red_strobe": 0x31,
    "green_strobe": 0x32,
    "blue_strobe": 0x33,
    "purple_strobe": 0x36,
    "white_strobe": 0x37,
    "rainbow_jump": 0x38,
}

STROBE_COLORS = {
    "red_strobe": ((255, 0, 0),),
    "green_strobe": ((0, 255, 0),),
    "blue_strobe": ((0, 0, 255),),
    "purple_strobe": ((160, 0, 255),),
    "white_strobe": ((255, 255, 255),),
    "rainbow_strobe": (
        (255, 0, 0), (255, 128, 0), (255, 255, 0), (0, 255, 0),
        (0, 255, 255), (0, 0, 255), (160, 0, 255), (255, 0, 160),
    ),
}


@dataclass(frozen=True)
class ConfiguredLight:
    light_id: str
    name: str
    room: str
    host: str
    mac: str
    model: str

    def public(self, include_network: bool = False) -> dict[str, object]:
        result: dict[str, object] = {
            "id": self.light_id,
            "name": self.name,
            "room": self.room,
            "model": self.model,
        }
        if include_network:
            result.update({"host": self.host, "mac": self.mac})
        return result


class TomlLightStore:
    """One atomically replaced TOML file for explicitly approved LAN lights."""

    def __init__(self, settings: dict, config_dir: Path):
        path = Path(str(settings.get("config_file", "state/lights.toml")))
        self.path = path if path.is_absolute() else config_dir / path

    def list(self) -> tuple[ConfiguredLight, ...]:
        if not self.path.exists():
            return ()
        with self.path.open("rb") as source:
            raw = tomllib.load(source)
        return tuple(self._from_raw(item) for item in raw.get("lights", []))

    def upsert(self, *, name: str, room: str, host: str, mac: str, model: str) -> ConfiguredLight:
        clean_name = self._clean_label(name, "Namn")
        clean_room = self._clean_label(room, "Rum")
        address = ipaddress.ip_address(host)
        private_lan = any(address in network for network in (
            ipaddress.ip_network("10.0.0.0/8"),
            ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"),
        ))
        if address.version != 4 or not private_lan:
            raise ValueError("Lampan måste ha en privat RFC1918-adress")
        clean_host = str(address)
        clean_mac = re.sub(r"[^0-9A-Fa-f]", "", mac).upper()
        if len(clean_mac) != 12:
            raise ValueError("Ogiltig MAC-adress")
        clean_model = self._clean_label(model, "Modell")
        lights = list(self.list())
        duplicate = next(
            (item for item in lights if item.mac != clean_mac and item.name.casefold() == clean_name.casefold()
             and item.room.casefold() == clean_room.casefold()),
            None,
        )
        if duplicate:
            raise ValueError(f"Namnet {clean_name} används redan i {clean_room}")
        current = next((item for item in lights if item.mac == clean_mac), None)
        updated = ConfiguredLight(
            light_id=current.light_id if current else str(uuid4()),
            name=clean_name,
            room=clean_room,
            host=clean_host,
            mac=clean_mac,
            model=clean_model,
        )
        lights = [updated if item.mac == clean_mac else item for item in lights]
        if current is None:
            lights.append(updated)
        self._write(tuple(sorted(lights, key=lambda item: (item.room.casefold(), item.name.casefold()))))
        return updated

    def resolve(self, target: str) -> tuple[ConfiguredLight, ...]:
        selector = self._clean_label(target, "Mål").casefold()
        exact_names = tuple(item for item in self.list() if item.name.casefold() == selector)
        if exact_names:
            return exact_names
        rooms = tuple(item for item in self.list() if item.room.casefold() == selector)
        if rooms:
            return rooms
        raise ValueError(f"Ingen konfigurerad lampa eller rum heter {target}")

    def _write(self, lights: tuple[ConfiguredLight, ...]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.parent.chmod(0o700)
        temporary = self.path.with_suffix(".tmp")
        lines = ["schema_version = 1"]
        quote = lambda value: json.dumps(value, ensure_ascii=False)
        for light in lights:
            lines.extend([
                "",
                "[[lights]]",
                f"id = {quote(light.light_id)}",
                f"name = {quote(light.name)}",
                f"room = {quote(light.room)}",
                f"host = {quote(light.host)}",
                f"mac = {quote(light.mac)}",
                f"model = {quote(light.model)}",
            ])
        temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(self.path)

    @staticmethod
    def _from_raw(raw: dict) -> ConfiguredLight:
        return ConfiguredLight(
            light_id=str(raw["id"]),
            name=str(raw["name"]),
            room=str(raw["room"]),
            host=str(raw["host"]),
            mac=str(raw["mac"]),
            model=str(raw["model"]),
        )

    @staticmethod
    def _clean_label(value: object, label: str) -> str:
        if not isinstance(value, str):
            raise ValueError(f"{label} måste vara text")
        cleaned = " ".join(value.strip().split())
        if not cleaned or len(cleaned) > 48 or any(ord(char) < 32 for char in cleaned):
            raise ValueError(f"Ogiltigt {label.lower()}")
        return cleaned


class MagicHomeLightService:
    def __init__(self, settings: dict, config_dir: Path):
        self.enabled = bool(settings.get("enabled", False))
        self.timeout = float(settings.get("timeout_seconds", 2.0))
        self.store = TomlLightStore(settings, config_dir)

    def list_public(self, include_network: bool = False) -> list[dict[str, object]]:
        return [light.public(include_network) for light in self.store.list()]

    def upsert(self, **fields: str) -> ConfiguredLight:
        if not self.enabled:
            raise RuntimeError("Ljustjänsten är inte aktiverad")
        return self.store.upsert(**fields)

    async def set_light(
        self,
        target: str,
        *,
        power: bool | None = None,
        color: str | None = None,
        brightness: int | None = None,
    ) -> str:
        lights = self._resolve(target)
        if power is None and color is None and brightness is None:
            raise ValueError("Ange av/på, färg eller ljusstyrka")
        level = self._percent(brightness if brightness is not None else 100, "Ljusstyrka")
        if power is False:
            packet = self._power_packet(False)
        elif color is None and brightness is not None:
            await self._set_brightness_preserving_color(lights, level)
            return f"Ställde ljusstyrkan i {self._describe(lights)} på {level} procent."
        elif color is not None or brightness is not None:
            rgb = self._parse_color(color or "#FFFFFF")
            scaled = tuple(round(channel * level / 100) for channel in rgb)
            packet = self._color_packet(*scaled)
        else:
            packet = self._power_packet(True)
        await self._send_all(lights, packet)
        if power is False:
            return f"Släckte {self._describe(lights)}."
        if color is not None:
            return f"Ställde {self._describe(lights)} på {color.upper()} med {level} procent."
        if brightness is not None:
            return f"Ställde ljusstyrkan i {self._describe(lights)} på {level} procent."
        return f"Tände {self._describe(lights)}."

    async def _set_brightness_preserving_color(self, lights: tuple[ConfiguredLight, ...], level: int) -> None:
        async def update(light: ConfiguredLight) -> None:
            state = await self._query(light.host)
            if len(state) < 9:
                raise RuntimeError(f"{light.name} gav ett okänt statussvar")
            current = tuple(state[index] for index in (6, 7, 8))
            peak = max(current)
            base = (255, 255, 255) if peak == 0 else tuple(round(channel * 255 / peak) for channel in current)
            scaled = tuple(round(channel * level / 100) for channel in base)
            await self._send(light.host, self._color_packet(*scaled))

        results = await asyncio.gather(*(update(light) for light in lights), return_exceptions=True)
        failures = [str(result) for result in results if isinstance(result, Exception)]
        if failures:
            raise RuntimeError("; ".join(failures))

    async def set_effect(self, target: str, effect: str, speed: int = 50) -> str:
        lights = self._resolve(target)
        if effect not in EFFECTS:
            raise ValueError(f"Okänt ljusmönster: {effect}")
        speed_percent = self._percent(speed, "Hastighet")
        delay = int(((100 - speed_percent) * 30) / 100) + 1
        packet = (
            self._custom_blink_packet(STROBE_COLORS[effect], delay)
            if effect in STROBE_COLORS
            else self._with_checksum(bytes((0x61, EFFECTS[effect], delay, 0x0F)))
        )
        await self._send_all(lights, self._power_packet(True))
        await self._send_all(lights, packet)
        return f"Startade {effect.replace('_', ' ')} i {self._describe(lights)} med {speed_percent} procents fart."

    def _resolve(self, target: str) -> tuple[ConfiguredLight, ...]:
        if not self.enabled:
            raise RuntimeError("Ljustjänsten är inte aktiverad")
        return self.store.resolve(target)

    async def _send_all(self, lights: tuple[ConfiguredLight, ...], packet: bytes) -> None:
        results = await asyncio.gather(*(self._send(light.host, packet) for light in lights), return_exceptions=True)
        failures = [str(result) for result in results if isinstance(result, Exception)]
        if failures:
            raise RuntimeError("; ".join(failures))

    async def _send(self, host: str, packet: bytes) -> None:
        _reader, writer = await asyncio.wait_for(asyncio.open_connection(host, 5577), timeout=self.timeout)
        try:
            writer.write(packet)
            await asyncio.wait_for(writer.drain(), timeout=self.timeout)
        finally:
            writer.close()
            await writer.wait_closed()

    async def _query(self, host: str) -> bytes:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, 5577), timeout=self.timeout)
        try:
            writer.write(bytes.fromhex("81 8a 8b 96"))
            await asyncio.wait_for(writer.drain(), timeout=self.timeout)
            return await asyncio.wait_for(reader.read(64), timeout=self.timeout)
        finally:
            writer.close()
            await writer.wait_closed()

    @staticmethod
    def _power_packet(on: bool) -> bytes:
        return MagicHomeLightService._with_checksum(bytes((0x71, 0x23 if on else 0x24, 0x0F)))

    @staticmethod
    def _color_packet(red: int, green: int, blue: int) -> bytes:
        return MagicHomeLightService._with_checksum(bytes((0x31, red, green, blue, 0x00, 0xF0, 0x0F)))

    @staticmethod
    def _with_checksum(payload: bytes) -> bytes:
        return payload + bytes((sum(payload) & 0xFF,))

    @staticmethod
    def _custom_blink_packet(colors: tuple[tuple[int, int, int], ...], delay: int) -> bytes:
        sequence: list[tuple[int, int, int]] = []
        while len(sequence) < 16:
            for color in colors:
                sequence.extend((color, (0, 0, 0)))
                if len(sequence) >= 16:
                    break
        payload = bytearray()
        for index, (red, green, blue) in enumerate(sequence[:16]):
            payload.extend((0x51 if index == 0 else 0x00, red, green, blue))
        payload.extend((0x00, delay, 0x3B, 0xFF, 0x0F))
        return MagicHomeLightService._with_checksum(bytes(payload))

    @staticmethod
    def _parse_color(color: str) -> tuple[int, int, int]:
        if not isinstance(color, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
            raise ValueError("Färg måste anges som #RRGGBB")
        return tuple(int(color[index:index + 2], 16) for index in (1, 3, 5))  # type: ignore[return-value]

    @staticmethod
    def _percent(value: int, label: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value not in range(1, 101):
            raise ValueError(f"{label} måste vara 1–100")
        return value

    @staticmethod
    def _describe(lights: tuple[ConfiguredLight, ...]) -> str:
        if len(lights) == 1:
            return f"{lights[0].name} i {lights[0].room}"
        return f"{lights[0].room} ({len(lights)} lampor)"
