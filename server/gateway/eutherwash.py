from __future__ import annotations

import ipaddress
import re
from urllib.parse import quote, urlsplit

import httpx


class EutherWashService:
    """Read-only client for one fixed EutherWash washer."""

    _ALIAS = re.compile(r"^[a-z0-9][a-z0-9_-]{0,62}$")
    _STATUS_KEYS = {
        "available", "online", "state", "phase", "progress_percent",
        "remaining_seconds", "program", "water_temperature_c", "spin_rpm",
        "rinse_cycles", "instantaneous_power_w", "cumulative_energy_kwh",
        "updated_at",
    }
    _STAT_KEYS = {
        "samples_24h", "samples_7d", "availability_percent_24h",
        "cycles_started_7d", "cycles_completed_7d", "running_minutes_7d",
        "energy_used_kwh_7d", "last_completed_at", "generated_at",
    }

    def __init__(self, settings: dict, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.enabled = bool(settings.get("enabled", False))
        self.base_url = str(settings.get("base_url", "http://127.0.0.1:8801")).rstrip("/")
        self.alias = str(settings.get("alias", "tvattmaskinen"))
        self.timeout = float(settings.get("timeout_seconds", 2.0))
        self.transport = transport
        self._validate()

    async def report(self) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherWash är inte aktiverad")
        encoded = quote(self.alias, safe="")
        status = await self._get(f"/v1/washers/{encoded}/status")
        statistics = await self._get(f"/v1/washers/{encoded}/statistics")
        if not isinstance(status, dict) or not isinstance(statistics, dict):
            raise RuntimeError("EutherWash svarade med ogiltiga data")
        return {
            "status": {key: status.get(key) for key in self._STATUS_KEYS},
            "statistics": {key: statistics.get(key) for key in self._STAT_KEYS},
        }

    async def _get(self, path: str) -> object:
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(self.timeout, connect=min(1.0, self.timeout)),
                trust_env=False,
                transport=self.transport,
            ) as client:
                response = await client.get(f"{self.base_url}{path}")
                response.raise_for_status()
                return response.json()
        except (httpx.HTTPError, ValueError) as error:
            raise RuntimeError("EutherWash svarar inte") from error

    def _validate(self) -> None:
        parsed = urlsplit(self.base_url)
        if (
            parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
        ):
            raise ValueError("EutherWash base_url måste vara en enkel lokal HTTP-adress")
        try:
            address = ipaddress.ip_address(parsed.hostname)
        except ValueError as error:
            raise ValueError("EutherWash måste använda en privat numerisk IP-adress") from error
        if not (address.is_private or address.is_loopback):
            raise ValueError("EutherWash måste finnas på det lokala nätet")
        if not self._ALIAS.fullmatch(self.alias):
            raise ValueError("EutherWash alias är ogiltigt")
        if not 0 < self.timeout <= 10:
            raise ValueError("EutherWash timeout måste vara mellan 0 och 10 sekunder")
