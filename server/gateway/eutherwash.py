from __future__ import annotations

import ipaddress
import os
import re
import stat
from pathlib import Path
from urllib.parse import quote, urlsplit

import httpx


class EutherWashService:
    """Narrow client for one fixed EutherWash washer."""

    _ALIAS = re.compile(r"^[a-z0-9][a-z0-9_-]{0,62}$")
    _STATUS_KEYS = {
        "available", "online", "state", "phase", "progress_percent",
        "remaining_seconds", "program", "water_temperature_c", "spin_rpm",
        "rinse_cycles", "remote_control_enabled", "instantaneous_power_w", "cumulative_energy_kwh",
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
        self.control_enabled = bool(settings.get("control_enabled", False))
        self.control_timeout = float(settings.get("control_timeout_seconds", 20.0))
        token_path = str(settings.get("control_token_file", "")).strip()
        self.control_token_file = Path(token_path) if token_path else None
        self.transport = transport
        self._validate()
        self._control_token = self._load_control_token() if self.control_enabled else None

    async def report(self) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherWash är inte aktiverad")
        status = await self.status()
        encoded = quote(self.alias, safe="")
        statistics = await self._get(f"/v1/washers/{encoded}/statistics")
        if not isinstance(status, dict) or not isinstance(statistics, dict):
            raise RuntimeError("EutherWash svarade med ogiltiga data")
        return {
            "status": {key: status.get(key) for key in self._STATUS_KEYS},
            "statistics": {key: statistics.get(key) for key in self._STAT_KEYS},
        }

    async def status(self) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherWash är inte aktiverad")
        encoded = quote(self.alias, safe="")
        status = await self._get(f"/v1/washers/{encoded}/status")
        if not isinstance(status, dict):
            raise RuntimeError("EutherWash svarade med ogiltiga data")
        return {key: status.get(key) for key in self._STATUS_KEYS}

    async def status_text(self) -> str:
        """Render the fixed washer report as concise, speakable Swedish."""
        report = await self.report()
        status = report["status"]
        statistics = report["statistics"]
        if not status.get("available") or not status.get("online"):
            return "Tvättmaskinen går inte att nå just nu."

        state = str(status.get("state") or "unknown").casefold()
        program = self._spoken_program(status.get("program"))
        if state == "running":
            parts = [f"Tvätten kör {program}." if program else "Tvätten kör."]
            progress = status.get("progress_percent")
            if isinstance(progress, (int, float)):
                parts.append(f"Den är ungefär {round(progress)} procent klar.")
            remaining = status.get("remaining_seconds")
            if isinstance(remaining, (int, float)) and remaining >= 0:
                minutes = max(1, round(remaining / 60))
                parts.append(f"Cirka {minutes} minuter återstår.")
        elif state == "paused":
            parts = ["Tvätten är pausad."]
        elif state in {"finished", "complete", "completed"}:
            parts = ["Tvätten är klar."]
        elif state in {"idle", "ready", "off"}:
            parts = ["Tvättmaskinen är ledig och ingen tvätt kör just nu."]
        else:
            parts = ["Tvättmaskinen svarar, men dess aktuella läge är oklart."]

        completed = statistics.get("cycles_completed_7d")
        minutes = statistics.get("running_minutes_7d")
        if isinstance(completed, int) and isinstance(minutes, int):
            hours = round(minutes / 60, 1)
            parts.append(
                f"De senaste sju dagarna har den gjort {completed} färdiga tvättar och gått i {hours:g} timmar."
            )
        return " ".join(parts)

    @staticmethod
    def _spoken_program(value: object) -> str:
        if not isinstance(value, str):
            return ""
        return " ".join(value.replace("_", " ").replace("-", " ").split()).casefold()

    async def command(self, command: str, *, confirmed: bool = False) -> dict[str, object]:
        if not self.enabled or not self.control_enabled or not self._control_token:
            raise RuntimeError("Tvättstyrning är inte aktiverad")
        if command not in {"start", "pause", "resume", "stop"}:
            raise ValueError("Okänt tvättkommando")
        if command in {"start", "stop"} and not confirmed:
            raise ValueError("Start och stopp kräver uttrycklig bekräftelse")
        encoded = quote(self.alias, safe="")
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(self.control_timeout, connect=min(1.0, self.control_timeout)),
                trust_env=False,
                transport=self.transport,
            ) as client:
                response = await client.post(
                    f"{self.base_url}/v1/washers/{encoded}/commands/{command}",
                    headers={"Authorization": f"Bearer {self._control_token}"},
                    json={"confirmed": confirmed},
                )
                if response.status_code == 409:
                    detail = response.json().get("detail", "command_rejected")
                    messages = {
                        "remote_control_required": "Slå på Smart Control på tvättmaskinen först.",
                        "washer_offline": "Tvättmaskinen är offline.",
                        "command_not_allowed_in_current_state": "Kommandot passar inte maskinens aktuella läge.",
                        "command_rejected": "Tvättmaskinen avvisade kommandot.",
                        "command_not_confirmed": "Maskinen bekräftade inte ändringen.",
                    }
                    raise RuntimeError(messages.get(str(detail), "Tvättmaskinen avvisade kommandot."))
                response.raise_for_status()
                payload = response.json()
        except RuntimeError:
            raise
        except (httpx.HTTPError, ValueError) as error:
            raise RuntimeError("EutherWash svarar inte på styrkommandot") from error
        status = payload.get("status") if isinstance(payload, dict) else None
        if not isinstance(status, dict):
            raise RuntimeError("EutherWash svarade med ogiltig styrbekräftelse")
        return {key: status.get(key) for key in self._STATUS_KEYS}

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
        if not 0 < self.control_timeout <= 25:
            raise ValueError("EutherWash control_timeout måste vara mellan 0 och 25 sekunder")
        if self.control_enabled and (self.control_token_file is None or not self.control_token_file.is_absolute()):
            raise ValueError("EutherWash control_token_file måste vara en absolut sökväg")

    def _load_control_token(self) -> str:
        assert self.control_token_file is not None
        try:
            info = self.control_token_file.lstat()
            token = self.control_token_file.read_text(encoding="utf-8").strip()
        except OSError:
            raise ValueError("EutherWash control-token kan inte läsas") from None
        if (
            stat.S_ISLNK(info.st_mode)
            or not stat.S_ISREG(info.st_mode)
            or stat.S_IMODE(info.st_mode) & 0o077
            or info.st_uid != os.geteuid()
            or len(token) < 32
            or any(character.isspace() for character in token)
        ):
            raise ValueError("EutherWash control-token är osäker")
        return token
