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
    _VACUUM_KEYS = {
        "available", "online", "state", "battery_percent", "fault_code",
        "cleaning_time_minutes", "cleaning_area_m2", "fan_mode", "water_flow",
        "mop_attached", "main_brush_percent", "main_brush_hours_left",
        "side_brush_percent", "side_brush_hours_left", "filter_percent",
        "filter_hours_left", "total_cleaning_minutes", "total_cleaning_count",
        "total_cleaning_area_m2", "map_available", "multiple_maps_enabled",
        "do_not_disturb_enabled", "voice_language", "volume_percent",
        "auto_empty_enabled", "dust_station_status", "maintenance_required",
        "system_messages", "raw_device_status", "raw_operating_mode",
        "raw_charging_state", "raw_task_status", "raw_relocation_status",
        "updated_at",
    }
    _VACUUM_COMMANDS = {
        "start", "pause", "stop", "return-to-dock", "start-fast-mapping",
    }
    _VACUUM_CONFIRMATION_REQUIRED = {"start", "start-fast-mapping"}

    def __init__(self, settings: dict, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.enabled = bool(settings.get("enabled", False))
        self.base_url = str(settings.get("base_url", "http://127.0.0.1:8801")).rstrip("/")
        self.alias = str(settings.get("alias", "tvattmaskinen"))
        self.vacuum_alias = str(settings.get("vacuum_alias", "dammsugaren"))
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

    async def vacuum_status(self) -> dict[str, object]:
        if not self.enabled:
            raise RuntimeError("EutherWash är inte aktiverad")
        encoded = quote(self.vacuum_alias, safe="")
        status = await self._get(f"/v1/vacuums/{encoded}/status")
        if not isinstance(status, dict):
            raise RuntimeError("EutherWash svarade med ogiltig dammsugardata")
        return {key: status.get(key) for key in self._VACUUM_KEYS}

    async def vacuum_maps(self) -> dict[str, object]:
        """Return only bounded display geometry from the fixed local archive."""
        if not self.enabled:
            raise RuntimeError("EutherWash är inte aktiverad")
        encoded = quote(self.vacuum_alias, safe="")
        payload = await self._get(f"/v1/vacuums/{encoded}/maps")
        if not isinstance(payload, dict):
            raise RuntimeError("EutherWash svarade med ogiltig kartdata")
        sanitized_maps: list[dict[str, object]] = []
        raw_maps = payload.get("maps")
        if not isinstance(raw_maps, list):
            raw_maps = []
        for raw_map in raw_maps[:3]:
            if not isinstance(raw_map, dict):
                continue
            runs = []
            for run in raw_map.get("runs", [])[:50_000] if isinstance(raw_map.get("runs"), list) else []:
                if isinstance(run, dict):
                    runs.append({key: run.get(key) for key in ("x", "y", "length", "kind", "room_id")})
            rooms = []
            for room in raw_map.get("rooms", [])[:63] if isinstance(raw_map.get("rooms"), list) else []:
                if isinstance(room, dict):
                    rooms.append({key: room.get(key) for key in ("id", "name")})
            def point(name: str) -> dict[str, object] | None:
                value = raw_map.get(name)
                return {key: value.get(key) for key in ("x", "y", "angle")} if isinstance(value, dict) else None
            sanitized_maps.append({
                "index": raw_map.get("index"),
                "selected": raw_map.get("selected"),
                "name": raw_map.get("name"),
                "width": raw_map.get("width"),
                "height": raw_map.get("height"),
                "cell_size_mm": raw_map.get("cell_size_mm"),
                "rotation": raw_map.get("rotation"),
                "runs": runs,
                "rooms": rooms,
                "robot": point("robot"),
                "charger": point("charger"),
            })
        return {
            "available": payload.get("available") is True,
            "offline_ready": payload.get("offline_ready") is True,
            "updated_at": payload.get("updated_at"),
            "maps": sanitized_maps,
        }

    async def vacuum_status_text(self) -> str:
        status = await self.vacuum_status()
        if not status.get("available") or not status.get("online"):
            return "Robotdammsugaren går inte att nå just nu."
        state = {
            "idle": "är redo",
            "cleaning": "städar",
            "paused": "är pausad",
            "returning": "är på väg till laddaren",
            "charging": "laddar",
            "error": "har ett fel",
        }.get(str(status.get("state")), "svarar men har ett oklart läge")
        parts = [f"Robotdammsugaren {state}."]
        battery = status.get("battery_percent")
        if isinstance(battery, int):
            parts.append(f"Batteriet är på {battery} procent.")
        messages = status.get("system_messages")
        if isinstance(messages, list):
            parts.extend(str(message) for message in messages[:4] if isinstance(message, str))
        if status.get("map_available") is True:
            parts.append("En huskarta finns sparad lokalt i roboten.")
        return " ".join(parts)

    async def vacuum_command(self, command: str, *, confirmed: bool = False) -> dict[str, object]:
        if not self.enabled or not self.control_enabled or not self._control_token:
            raise RuntimeError("Dammsugarstyrning är inte aktiverad")
        if command not in self._VACUUM_COMMANDS:
            raise ValueError("Okänt dammsugarkommando")
        if command in self._VACUUM_CONFIRMATION_REQUIRED and not confirmed:
            raise ValueError("Kommandot kräver uttrycklig bekräftelse")
        encoded = quote(self.vacuum_alias, safe="")
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(self.control_timeout, connect=min(1.0, self.control_timeout)),
                trust_env=False,
                transport=self.transport,
            ) as client:
                response = await client.post(
                    f"{self.base_url}/v1/vacuums/{encoded}/commands/{command}",
                    headers={"Authorization": f"Bearer {self._control_token}"},
                    json={"confirmed": confirmed},
                )
                if response.status_code == 409:
                    detail = response.json().get("detail", "command_rejected")
                    messages = {
                        "vacuum_busy": "Robotdammsugaren måste vara stilla innan snabbkartläggningen startar.",
                        "battery_too_low": "Robotdammsugaren behöver minst 15 procent batteri.",
                        "remove_mop_before_mapping": "Ta bort moppen innan snabb kartläggning.",
                        "multiple_maps_enable_rejected": "Robotdammsugaren kunde inte aktivera flerkartsläget.",
                        "invalid_state_for_command": "Kommandot kan inte användas i robotens nuvarande läge.",
                        "already_at_dock": "Robotdammsugaren är redan vid laddaren.",
                        "command_rejected": "Robotdammsugaren avvisade kommandot.",
                    }
                    raise RuntimeError(messages.get(str(detail), "Robotdammsugaren avvisade kommandot."))
                response.raise_for_status()
                payload = response.json()
        except RuntimeError:
            raise
        except (httpx.HTTPError, ValueError) as error:
            raise RuntimeError("EutherWash svarar inte på dammsugarkommandot") from error
        status = payload.get("status") if isinstance(payload, dict) else None
        if not isinstance(status, dict) or payload.get("accepted") is not True:
            raise RuntimeError("EutherWash bekräftade inte dammsugarkommandot")
        return {key: status.get(key) for key in self._VACUUM_KEYS}

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
        if not self._ALIAS.fullmatch(self.vacuum_alias):
            raise ValueError("EutherWash vacuum_alias är ogiltigt")
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
