from __future__ import annotations

import asyncio
from dataclasses import dataclass
import ipaddress
from urllib.parse import quote, urlsplit

import httpx


@dataclass(frozen=True)
class ConfiguredPump:
    pump_id: str
    name: str
    room: str

    def public(self) -> dict[str, str]:
        return {"id": self.pump_id, "name": self.name, "room": self.room}


class EutherPumpService:
    """Read-only client for explicitly allowlisted pumps in EutherPump."""

    def __init__(
        self,
        settings: dict,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.enabled = bool(settings.get("enabled", False))
        self.base_url = str(settings.get("base_url", "http://127.0.0.1:8794")).rstrip("/")
        self.timeout = float(settings.get("timeout_seconds", 2.0))
        self.transport = transport
        self._control_locks: dict[str, asyncio.Lock] = {}
        self._validate_base_url()
        self.targets = tuple(self._target(item) for item in settings.get("pumps", []))
        identities = {(item.name.casefold(), item.room.casefold()) for item in self.targets}
        if len(identities) != len(self.targets):
            raise ValueError("Varje EutherPump-mål måste ha ett unikt namn och rum")

    def list_public(self) -> list[dict[str, str]]:
        if not self.enabled:
            return []
        return [target.public() for target in self.targets]

    def resolve(self, selector: str) -> ConfiguredPump:
        cleaned = self._label(selector, "Pumpmål").casefold()
        names = [item for item in self.targets if item.name.casefold() == cleaned]
        if len(names) == 1:
            return names[0]
        rooms = [item for item in self.targets if item.room.casefold() == cleaned]
        if len(rooms) == 1:
            return rooms[0]
        if len(rooms) > 1:
            raise ValueError(f"Flera värmepumpar finns i {selector}; använd pumpens namn")
        raise ValueError(f"Ingen konfigurerad värmepump eller rum heter {selector}")

    async def state(self, selector: str) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherPump är inte aktiverad")
        target = self.resolve(selector)
        timeout = httpx.Timeout(self.timeout, connect=min(1.0, self.timeout))
        try:
            async with httpx.AsyncClient(
                timeout=timeout,
                trust_env=False,
                transport=self.transport,
            ) as client:
                response = await client.get(
                    f"{self.base_url}/v1/pumps/{quote(target.pump_id, safe='')}/state"
                )
                response.raise_for_status()
        except httpx.HTTPError as error:
            raise RuntimeError("EutherPump svarar inte") from error
        payload = response.json()
        if not isinstance(payload, dict) or payload.get("pump_id") != target.pump_id:
            raise RuntimeError("EutherPump svarade med fel pumpidentitet")
        return payload

    async def control(self, selector: str, changes: dict[str, object]) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherPump är inte aktiverad")
        target = self.resolve(selector)
        timeout = httpx.Timeout(max(self.timeout, 15.0), connect=min(1.0, self.timeout))
        try:
            async with httpx.AsyncClient(
                timeout=timeout,
                trust_env=False,
                transport=self.transport,
            ) as client:
                response = await client.patch(
                    f"{self.base_url}/v1/pumps/{quote(target.pump_id, safe='')}/state",
                    json=changes,
                )
                response.raise_for_status()
        except httpx.HTTPError as error:
            detail = ""
            if isinstance(error, httpx.HTTPStatusError):
                try:
                    detail = str(error.response.json().get("detail", ""))
                except (ValueError, AttributeError):
                    pass
            raise RuntimeError(detail or "EutherPump kunde inte genomföra kommandot") from error
        payload = response.json()
        state = payload.get("state") if isinstance(payload, dict) else None
        if not isinstance(state, dict) or state.get("pump_id") != target.pump_id:
            raise RuntimeError("EutherPump svarade med fel pumpidentitet")
        return state

    async def control_voice(
        self, selector: str, requested: dict[str, object]
    ) -> dict[str, object]:
        """Resolve relative voice changes atomically, then require server readback."""
        target = self.resolve(selector)
        allowed = {
            "power", "mode", "target_temperature", "temperature_delta",
            "fan_mode", "fan_delta",
        }
        unexpected = set(requested) - allowed
        if unexpected:
            raise ValueError(f"Otillåtna röstinställningar: {', '.join(sorted(unexpected))}")
        lock = self._control_locks.setdefault(target.pump_id, asyncio.Lock())
        async with lock:
            changes = dict(requested)
            temperature_delta = changes.pop("temperature_delta", None)
            fan_delta = changes.pop("fan_delta", None)
            if temperature_delta is not None or fan_delta is not None:
                current = await self.state(target.name)
                if current.get("online") is not True:
                    raise RuntimeError("Värmepumpen är offline")
                if temperature_delta is not None:
                    value = current.get("target_temperature")
                    if isinstance(value, bool) or not isinstance(value, (int, float)):
                        raise RuntimeError("Värmepumpen saknar aktuellt börvärde")
                    changes["target_temperature"] = max(
                        16, min(30, round(float(value) + int(temperature_delta)))
                    )
                if fan_delta is not None:
                    changes["fan_mode"] = self._relative_fan_mode(
                        current.get("fan_mode"), int(fan_delta)
                    )
            return await self.control(target.name, changes)

    @staticmethod
    def _relative_fan_mode(current: object, delta: int) -> str:
        if delta not in {-1, 1}:
            raise ValueError("Relativ fläktändring måste vara -1 eller 1")
        levels = ("quiet", "1", "2", "3", "4", "5")
        if current == "auto":
            return "3" if delta > 0 else "quiet"
        normalized = str(current)
        if normalized not in levels:
            raise RuntimeError("Värmepumpen saknar känd fläkthastighet")
        index = max(0, min(len(levels) - 1, levels.index(normalized) + delta))
        return levels[index]

    async def status_text(self, selector: str) -> str:
        target = self.resolve(selector)
        state = await self.state(target.name)
        if not state.get("online"):
            return f"{target.name} i {target.room} är offline."
        power = state.get("power")
        room_temperature = state.get("room_temperature")
        target_temperature = state.get("target_temperature")
        power_text = "på" if power is True else "av" if power is False else "i okänt läge"
        parts = [f"{target.name} i {target.room} är {power_text}"]
        if isinstance(room_temperature, (int, float)):
            parts.append(f"det är {room_temperature:g} grader i rummet")
        if power is True and isinstance(target_temperature, (int, float)):
            parts.append(f"börvärdet är {target_temperature:g} grader")
        return ", ".join(parts) + "."

    def _validate_base_url(self) -> None:
        parsed = urlsplit(self.base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("EutherPump base_url måste vara en enkel lokal HTTP-adress")
        host = parsed.hostname.casefold()
        if host == "localhost" or host.endswith(".local"):
            return
        try:
            address = ipaddress.ip_address(host)
        except ValueError as error:
            raise ValueError("EutherPump måste använda en privat IP- eller .local-adress") from error
        if not (address.is_private or address.is_loopback or address.is_link_local):
            raise ValueError("EutherPump måste finnas på det lokala nätet")

    @classmethod
    def _target(cls, raw: object) -> ConfiguredPump:
        if not isinstance(raw, dict):
            raise ValueError("EutherPump-mål måste vara TOML-tabeller")
        return ConfiguredPump(
            pump_id=cls._label(raw.get("id"), "Pump-ID"),
            name=cls._label(raw.get("name"), "Pumpnamn"),
            room=cls._label(raw.get("room"), "Rum"),
        )

    @staticmethod
    def _label(value: object, label: str) -> str:
        if not isinstance(value, str):
            raise ValueError(f"{label} måste vara text")
        cleaned = " ".join(value.strip().split())
        if not cleaned or len(cleaned) > 64 or any(ord(char) < 32 for char in cleaned):
            raise ValueError(f"Ogiltigt {label.lower()}")
        return cleaned
