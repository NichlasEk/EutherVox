from __future__ import annotations

import asyncio
from dataclasses import dataclass
import ipaddress
import json
from pathlib import Path
import socket
import tomllib
from uuid import uuid4


NEC_PORT = 7142
INPUTS = {
    "hdmi1": ("HDMI 1", "0011"),
    "hdmi2": ("HDMI 2", "0012"),
    "hdmi3": ("HDMI 3", "0082"),
    "vga_rgb": ("VGA RGB", "0001"),
    "vga_component": ("VGA Component", "000C"),
    "av": ("A/V IN", "0005"),
}
POWER_PACKETS = {
    "on": bytes.fromhex("01304130413043024332303344363030303103730D"),
    "off": bytes.fromhex("01304130413043024332303344363030303403760D"),
}


@dataclass(frozen=True)
class ConfiguredTv:
    tv_id: str
    name: str
    room: str
    host: str
    port: int = NEC_PORT
    model: str = "NEC display"


class TomlTvStore:
    def __init__(self, settings: dict, config_dir: Path):
        path = Path(str(settings.get("config_file", "state/tvs.toml")))
        self.path = path if path.is_absolute() else config_dir / path

    def list(self) -> tuple[ConfiguredTv, ...]:
        if not self.path.exists():
            return ()
        with self.path.open("rb") as source:
            raw = tomllib.load(source)
        return tuple(self._from_raw(item) for item in raw.get("tvs", []))

    def upsert(self, *, name: str, room: str, host: str, port: int = NEC_PORT, model: str = "NEC display") -> ConfiguredTv:
        clean_name = self._label(name, "Namn")
        clean_room = self._label(room, "Rum")
        clean_host = self._host(host)
        clean_model = self._label(model, "Modell")
        if int(port) != NEC_PORT:
            raise ValueError("Endast NEC-port 7142 är tillåten")
        televisions = list(self.list())
        current = next((item for item in televisions if item.host == clean_host), None)
        duplicate = next((item for item in televisions if item.host != clean_host and item.name.casefold() == clean_name.casefold()), None)
        if duplicate:
            raise ValueError(f"TV-namnet {clean_name} används redan")
        updated = ConfiguredTv(current.tv_id if current else str(uuid4()), clean_name, clean_room, clean_host, NEC_PORT, clean_model)
        televisions = [updated if item.host == clean_host else item for item in televisions]
        if current is None:
            televisions.append(updated)
        self._write(tuple(sorted(televisions, key=lambda item: (item.room.casefold(), item.name.casefold()))))
        return updated

    def resolve(self, target: str) -> ConfiguredTv:
        selector = self._label(target, "Mål").casefold()
        named = [item for item in self.list() if item.name.casefold() == selector]
        if len(named) == 1:
            return named[0]
        room = [item for item in self.list() if item.room.casefold() == selector]
        if len(room) == 1:
            return room[0]
        if len(room) > 1:
            raise ValueError(f"Flera TV-apparater finns i {target}; säg TV-namnet")
        raise ValueError(f"Ingen konfigurerad TV heter eller finns i {target}")

    def _write(self, televisions: tuple[ConfiguredTv, ...]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.parent.chmod(0o700)
        temporary = self.path.with_suffix(".tmp")
        quote = lambda value: json.dumps(value, ensure_ascii=False)
        lines = ["schema_version = 1"]
        for tv in televisions:
            lines.extend(["", "[[tvs]]", f"id = {quote(tv.tv_id)}", f"name = {quote(tv.name)}", f"room = {quote(tv.room)}", f"host = {quote(tv.host)}", f"port = {tv.port}", f"model = {quote(tv.model)}"])
        temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(self.path)

    @staticmethod
    def _from_raw(raw: dict) -> ConfiguredTv:
        return ConfiguredTv(str(raw["id"]), str(raw["name"]), str(raw["room"]), TomlTvStore._host(raw["host"]), int(raw.get("port", NEC_PORT)), str(raw.get("model", "NEC display")))

    @staticmethod
    def _label(value: object, field: str) -> str:
        if not isinstance(value, str):
            raise ValueError(f"{field} måste vara text")
        cleaned = " ".join(value.strip().split())
        if not cleaned or len(cleaned) > 64:
            raise ValueError(f"{field} måste vara 1–64 tecken")
        return cleaned

    @staticmethod
    def _host(value: object) -> str:
        try:
            address = ipaddress.ip_address(str(value).strip())
        except ValueError as error:
            raise ValueError("Ogiltig TV-adress") from error
        if address.version != 4 or not address.is_private:
            raise ValueError("TV:n måste ha en privat IPv4-adress")
        return str(address)


class NecTvService:
    def __init__(self, settings: dict, config_dir: Path):
        self.enabled = bool(settings.get("enabled", False))
        self.timeout = float(settings.get("timeout_seconds", 1.5))
        self.scan_concurrency = max(1, min(64, int(settings.get("scan_concurrency", 32))))
        self.scan_network = str(settings.get("scan_network", ""))
        self.store = TomlTvStore(settings, config_dir)

    def list_public(self, include_network: bool = False) -> list[dict[str, object]]:
        result = []
        for tv in self.store.list():
            item: dict[str, object] = {"id": tv.tv_id, "name": tv.name, "room": tv.room, "model": tv.model}
            if include_network:
                item.update({"host": tv.host, "port": tv.port})
            result.append(item)
        return result

    def upsert(self, **fields: object) -> ConfiguredTv:
        if not self.enabled:
            raise RuntimeError("TV-tjänsten är inte aktiverad")
        return self.store.upsert(**fields)

    async def control(self, target: str, command: str) -> str:
        if not self.enabled:
            raise RuntimeError("TV-tjänsten är inte aktiverad")
        allowed = {"power_on", "power_off", *[f"input_{key}" for key in INPUTS]}
        if command not in allowed:
            raise ValueError("Otillåtet TV-kommando")
        tv = self.store.resolve(target)
        packet = self.packet(command)
        await self._exchange(tv.host, tv.port, packet)
        label = {"power_on": "på", "power_off": "av", **{f"input_{key}": value[0] for key, value in INPUTS.items()}}[command]
        return f"{tv.name} i {tv.room}: {label}."

    async def discover(self) -> list[dict[str, object]]:
        if not self.enabled:
            raise RuntimeError("TV-tjänsten är inte aktiverad")
        network = self._network()
        semaphore = asyncio.Semaphore(self.scan_concurrency)

        async def probe(address: ipaddress.IPv4Address) -> dict[str, object] | None:
            async with semaphore:
                found = await self._probe(str(address))
            return {"host": str(address), "port": NEC_PORT, "model": "NEC display"} if found else None

        found = await asyncio.gather(*(probe(address) for address in network.hosts()))
        return [item for item in found if item]

    def _network(self) -> ipaddress.IPv4Network:
        if self.scan_network:
            network = ipaddress.ip_network(self.scan_network, strict=False)
            if network.version != 4 or not network.is_private or network.prefixlen < 24:
                raise ValueError("TV-sökning måste begränsas till ett privat /24-nät")
            return network
        host = socket.gethostbyname(socket.gethostname())
        address = ipaddress.ip_address(host)
        if not address.is_private:
            raise ValueError("Kunde inte avgöra lokalt privat nät; ange television.scan_network")
        return ipaddress.ip_network(f"{address}/24", strict=False)

    async def _probe(self, host: str) -> bool:
        try:
            _reader, writer = await asyncio.wait_for(asyncio.open_connection(host, NEC_PORT), timeout=min(self.timeout, 0.35))
            writer.close()
            await writer.wait_closed()
            return True
        except (OSError, asyncio.TimeoutError):
            return False

    async def _exchange(self, host: str, port: int, packet: bytes) -> None:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=self.timeout)
        try:
            writer.write(packet)
            await writer.drain()
            try:
                await asyncio.wait_for(reader.read(4096), timeout=self.timeout)
            except asyncio.TimeoutError:
                pass
        finally:
            writer.close()
            await writer.wait_closed()

    @staticmethod
    def packet(command: str) -> bytes:
        if command.startswith("power_"):
            try:
                return POWER_PACKETS[command.removeprefix("power_")]
            except KeyError as error:
                raise ValueError("Otillåtet TV-kommando") from error
        input_key = command.removeprefix("input_")
        if input_key not in INPUTS:
            raise ValueError("Otillåtet TV-kommando")
        payload = INPUTS[input_key][1].encode("ascii")
        body = b"0A0E0A" + bytes((0x02,)) + b"0060" + payload + bytes((0x03,))
        checksum = 0
        for value in body:
            checksum ^= value
        return bytes((0x01,)) + body + bytes((checksum, 0x0D))
