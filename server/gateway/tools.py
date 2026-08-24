from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any
from uuid import uuid4

from .actions import DeviceAction
from .cast import CastService
from .eutherpump import EutherPumpService
from .eutherwash import EutherWashService
from .lighting import EFFECTS, MagicHomeLightService
from .television import INPUTS, NecTvService


class ToolValidationError(ValueError):
    pass


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    input_schema: dict[str, Any]

    def for_ollama(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.input_schema,
            },
        }


class EutherVoxToolRegistry:
    """One allowlisted tool surface shared by Ollama and the MCP server."""

    definitions = (
        ToolDefinition(
            name="music_play",
            description="Spela musik som matchar en svensk beskrivning, på telefonen eller i ett konfigurerat rum.",
            input_schema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Musik, artist, genre eller stämning att spela."},
                    "output_room": {"type": "string", "description": "Rum att spela i. Utelämna för telefonen."},
                },
                "required": ["query"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="playlist_create",
            description="Föreslå en privat spellista utifrån en genre eller stämning. Kräver alltid användarbekräftelse.",
            input_schema={
                "type": "object",
                "properties": {
                    "description": {"type": "string", "description": "Genre, stämning eller önskemål för spellistan."},
                    "output_room": {"type": "string", "description": "Rum där listan även ska börja spelas."},
                },
                "required": ["description"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="wikipedia_lookup",
            description="Slå upp en offentlig sak, person, plats eller händelse på svenska Wikipedia.",
            input_schema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Ämnet eller artikelns namn."},
                    "mode": {
                        "type": "string",
                        "enum": ["summary", "introduction"],
                        "description": "summary för en kort sammanfattning, introduction för att läsa artikelinledningen.",
                    },
                },
                "required": ["query"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="light_set",
            description="Tänd, släck eller ställ exakt färg och ljusstyrka på en konfigurerad lampa eller ett rum.",
            input_schema={
                "type": "object",
                "properties": {
                    "target": {"type": "string", "description": "Ett konfigurerat lampnamn eller rum."},
                    "power": {"type": "boolean", "description": "true för tänd, false för släck."},
                    "color": {"type": "string", "pattern": "^#[0-9A-Fa-f]{6}$", "description": "Exakt RGB-färg som #7A18C4."},
                    "brightness": {"type": "integer", "minimum": 1, "maximum": 100},
                },
                "required": ["target"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="light_effect",
            description="Starta ett tillåtet färgskifte eller blinkmönster på en konfigurerad lampa eller i ett rum.",
            input_schema={
                "type": "object",
                "properties": {
                    "target": {"type": "string", "description": "Ett konfigurerat lampnamn eller rum."},
                    "effect": {"type": "string", "enum": sorted(EFFECTS)},
                    "speed": {"type": "integer", "minimum": 1, "maximum": 100},
                },
                "required": ["target", "effect"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="tv_control",
            description="Slå på eller av en namngiven NEC-TV och välj en tillåten bildingång.",
            input_schema={
                "type": "object",
                "properties": {
                    "target": {"type": "string", "description": "TV-namn eller rum från tvs.toml."},
                    "command": {
                        "type": "string",
                        "enum": ["power_on", "power_off", *[f"input_{key}" for key in INPUTS]],
                    },
                },
                "required": ["target", "command"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="heat_pump_status",
            description="Läs aktuell status och temperatur från en konfigurerad lokal värmepump.",
            input_schema={
                "type": "object",
                "properties": {
                    "target": {
                        "type": "string",
                        "description": "Pumpnamn eller rum från EutherPump-konfigurationen.",
                    },
                },
                "required": ["target"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="heat_pump_control",
            description=(
                "Styr en konfigurerad lokal värmepump: ström, värme/kyla, "
                "exakt eller relativ temperatur samt fläkthastighet."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "target": {
                        "type": "string",
                        "description": "Pumpnamn eller rum från EutherPump-konfigurationen.",
                    },
                    "power": {"type": "boolean"},
                    "mode": {
                        "type": "string",
                        "enum": ["auto", "cool", "heat", "dry", "fan"],
                    },
                    "target_temperature": {
                        "type": "integer",
                        "minimum": 16,
                        "maximum": 30,
                    },
                    "temperature_delta": {
                        "type": "integer",
                        "minimum": -3,
                        "maximum": 3,
                        "description": "Relativ ändring i hela grader; aldrig 0.",
                    },
                    "fan_mode": {
                        "type": "string",
                        "enum": ["auto", "quiet", "1", "2", "3", "4", "5"],
                    },
                    "fan_delta": {
                        "type": "integer",
                        "enum": [-1, 1],
                        "description": "-1 för mindre och 1 för mer fläkt.",
                    },
                },
                "required": ["target"],
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="washer_status",
            description="Läs aktuell status och sjudagarsrapport från husets fasta tvättmaskin.",
            input_schema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        ToolDefinition(
            name="vacuum_status",
            description="Läs status, karta, underhåll och systemmeddelanden från husets robotdammsugare.",
            input_schema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
    )

    def __init__(
        self,
        cast: CastService | None = None,
        lights: MagicHomeLightService | None = None,
        television: NecTvService | None = None,
        eutherpump: EutherPumpService | None = None,
        eutherwash: EutherWashService | None = None,
    ):
        self.cast = cast
        self.lights = lights
        self.television = television
        self.eutherpump = eutherpump
        self.eutherwash = eutherwash

    @property
    def ollama_tools(self) -> list[dict[str, Any]]:
        return [definition.for_ollama() for definition in self.definitions]

    def list_cast_targets(self) -> list[dict[str, str]]:
        if not self.cast or not self.cast.enabled:
            return []
        return [
            {"room": target.room, "display_name": target.friendly_name, "model": target.model_name}
            for target in self.cast.targets.values()
        ]

    def list_light_targets(self) -> list[dict[str, object]]:
        if not self.lights or not self.lights.enabled:
            return []
        return self.lights.list_public()

    def list_tv_targets(self) -> list[dict[str, object]]:
        if not self.television or not self.television.enabled:
            return []
        return self.television.list_public()

    def list_pump_targets(self) -> list[dict[str, str]]:
        if not self.eutherpump or not self.eutherpump.enabled:
            return []
        return self.eutherpump.list_public()

    def create_action(self, tool_name: str, arguments: dict[str, Any], node_name: str) -> DeviceAction:
        if not isinstance(arguments, dict):
            raise ToolValidationError("Verktygsargument måste vara ett objekt")
        allowed = next((item for item in self.definitions if item.name == tool_name), None)
        if allowed is None:
            raise ToolValidationError(f"Okänt verktyg: {tool_name}")
        accepted_keys = set(allowed.input_schema["properties"])
        unexpected = set(arguments) - accepted_keys
        if unexpected:
            raise ToolValidationError(f"Otillåtna argument: {', '.join(sorted(unexpected))}")

        if tool_name == "washer_status":
            if not self.eutherwash or not self.eutherwash.enabled:
                raise ToolValidationError("EutherWash är inte konfigurerad")
            return DeviceAction(
                action_id=str(uuid4()),
                name="washer.status",
                target_node=node_name,
                arguments={},
                acknowledgement="Jag läser tvättrapporten.",
            )

        if tool_name == "vacuum_status":
            if not self.eutherwash or not self.eutherwash.enabled:
                raise ToolValidationError("EutherWash är inte konfigurerad")
            return DeviceAction(
                action_id=str(uuid4()),
                name="vacuum.status",
                target_node=node_name,
                arguments={},
                acknowledgement="Jag läser dammsugarrapporten.",
            )

        if tool_name in {"light_set", "light_effect"}:
            if not self.lights or not self.lights.enabled:
                raise ToolValidationError("Ljustjänsten är inte konfigurerad")
            target = self._clean_text(arguments.get("target"), "target")
            try:
                matches = self.lights.store.resolve(target)
            except ValueError as error:
                raise ToolValidationError(str(error)) from error
            canonical_target = matches[0].name if len(matches) == 1 else matches[0].room
            if tool_name == "light_effect":
                effect = str(arguments.get("effect", ""))
                if effect not in EFFECTS:
                    raise ToolValidationError("Otillåtet ljusmönster")
                speed = self._percentage(arguments.get("speed", 50), "speed")
                return DeviceAction(
                    action_id=str(uuid4()),
                    name="lights.effect",
                    target_node=node_name,
                    arguments={"target": canonical_target, "effect": effect, "speed": speed},
                    acknowledgement=f"Jag väcker {effect.replace('_', ' ')} i {canonical_target}.",
                )
            result: dict[str, object] = {"target": canonical_target}
            if "power" in arguments:
                if not isinstance(arguments["power"], bool):
                    raise ToolValidationError("power måste vara sant eller falskt")
                result["power"] = arguments["power"]
            if "color" in arguments:
                color = str(arguments["color"])
                if not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
                    raise ToolValidationError("color måste vara #RRGGBB")
                result["color"] = color.upper()
            if "brightness" in arguments:
                result["brightness"] = self._percentage(arguments["brightness"], "brightness")
            if len(result) == 1:
                raise ToolValidationError("Ange av/på, färg eller ljusstyrka")
            return DeviceAction(
                action_id=str(uuid4()),
                name="lights.set",
                target_node=node_name,
                arguments=result,
                acknowledgement=f"Jag ställer ljuset i {canonical_target}.",
            )

        if tool_name == "tv_control":
            if not self.television or not self.television.enabled:
                raise ToolValidationError("TV-tjänsten är inte konfigurerad")
            target = self._clean_text(arguments.get("target"), "target")
            command = str(arguments.get("command", ""))
            allowed_commands = {"power_on", "power_off", *[f"input_{key}" for key in INPUTS]}
            if command not in allowed_commands:
                raise ToolValidationError("Otillåtet TV-kommando")
            try:
                tv = self.television.store.resolve(target)
            except ValueError as error:
                raise ToolValidationError(str(error)) from error
            description = {
                "power_on": "slår på",
                "power_off": "stänger av",
                **{f"input_{key}": f"byter till {label}" for key, (label, _payload) in INPUTS.items()},
            }[command]
            return DeviceAction(
                action_id=str(uuid4()),
                name="tv.control",
                target_node=node_name,
                arguments={"target": tv.name, "command": command},
                acknowledgement=f"Jag {description} {tv.name} i {tv.room}.",
            )

        if tool_name in {"heat_pump_status", "heat_pump_control"}:
            if not self.eutherpump or not self.eutherpump.enabled:
                raise ToolValidationError("EutherPump är inte konfigurerad")
            target = self._clean_text(arguments.get("target"), "target")
            try:
                pump = self.eutherpump.resolve(target)
            except ValueError as error:
                raise ToolValidationError(str(error)) from error
            if tool_name == "heat_pump_status":
                return DeviceAction(
                    action_id=str(uuid4()),
                    name="pump.status",
                    target_node=node_name,
                    arguments={"target": pump.name},
                    acknowledgement=f"Jag läser av {pump.name} i {pump.room}.",
                )
            result: dict[str, object] = {"target": pump.name}
            if "power" in arguments:
                if not isinstance(arguments["power"], bool):
                    raise ToolValidationError("power måste vara sant eller falskt")
                result["power"] = arguments["power"]
            if "mode" in arguments:
                mode = str(arguments["mode"])
                if mode not in {"auto", "cool", "heat", "dry", "fan"}:
                    raise ToolValidationError("Otillåtet pumpläge")
                result["mode"] = mode
            for field in ("target_temperature", "temperature_delta", "fan_delta"):
                if field not in arguments:
                    continue
                value = arguments[field]
                if isinstance(value, bool) or not isinstance(value, int):
                    raise ToolValidationError(f"{field} måste vara ett heltal")
                if field == "target_temperature" and not 16 <= value <= 30:
                    raise ToolValidationError("Temperaturen måste vara 16–30 grader")
                if field == "temperature_delta" and (value == 0 or not -3 <= value <= 3):
                    raise ToolValidationError("Relativ temperaturändring måste vara 1–3 grader")
                if field == "fan_delta" and value not in {-1, 1}:
                    raise ToolValidationError("Relativ fläktändring måste vara -1 eller 1")
                result[field] = value
            if "fan_mode" in arguments:
                fan_mode = str(arguments["fan_mode"])
                if fan_mode not in {"auto", "quiet", "1", "2", "3", "4", "5"}:
                    raise ToolValidationError("Otillåten fläkthastighet")
                result["fan_mode"] = fan_mode
            if "target_temperature" in result and "temperature_delta" in result:
                raise ToolValidationError("Ange exakt eller relativ temperatur, inte båda")
            if "fan_mode" in result and "fan_delta" in result:
                raise ToolValidationError("Ange exakt eller relativ fläkt, inte båda")
            if len(result) == 1:
                raise ToolValidationError("Ange en pumpinställning")
            return DeviceAction(
                action_id=str(uuid4()),
                name="pump.control",
                target_node=node_name,
                arguments=result,
                acknowledgement=self._pump_acknowledgement(pump.name, pump.room, result),
            )

        query_key = "description" if tool_name == "playlist_create" else "query"
        query = self._clean_text(arguments.get(query_key), query_key)
        if tool_name == "wikipedia_lookup":
            mode = str(arguments.get("mode", "summary"))
            if mode not in {"summary", "introduction"}:
                raise ToolValidationError("mode måste vara summary eller introduction")
            return DeviceAction(
                action_id=str(uuid4()),
                name="knowledge.wikipedia",
                target_node=node_name,
                arguments={"query": query, "mode": mode},
                acknowledgement=f"Jag slår upp {query} på svenska Wikipedia.",
            )
        room = self._normalize_room(arguments.get("output_room", ""))
        if room and (not self.cast or not self.cast.configured(room)):
            raise ToolValidationError(f"Rummet {room} är inte en konfigurerad Cast-mottagare")

        if tool_name == "music_play":
            action_arguments: dict[str, object] = {"provider": "youtube_music", "query": query}
            if room:
                action_arguments["output_room"] = room
            return DeviceAction(
                action_id=str(uuid4()),
                name="media.play",
                target_node=node_name,
                arguments=action_arguments,
                acknowledgement=f"Jag spelar {query} i {room}." if room else f"Jag skickar {query} till YouTube Music.",
            )

        action_arguments = {"provider": "euthervox", "query": query}
        if room:
            action_arguments["output_room"] = room
        return DeviceAction(
            action_id=str(uuid4()),
            name="playlist.create",
            target_node=node_name,
            arguments=action_arguments,
            acknowledgement=(
                f"Jag kan skapa en privat lista med {query} och spela den i {room}. Bekräfta i appen."
                if room
                else f"Jag kan skapa en privat lista med {query}. Bekräfta i appen."
            ),
            requires_confirmation=True,
        )

    @staticmethod
    def _pump_acknowledgement(
        name: str, room: str, arguments: dict[str, object]
    ) -> str:
        if arguments.get("power") is False:
            return f"Jag stänger av {name} i {room}."
        mode = arguments.get("mode")
        if mode == "heat":
            return f"Jag slår på värmen med {name} i {room}."
        if mode == "cool":
            return f"Jag slår på kylan med {name} i {room}."
        if arguments.get("power") is True and len(arguments) == 2:
            return f"Jag slår på {name} i {room}."
        if "target_temperature" in arguments:
            return f"Jag ställer {name} på {arguments['target_temperature']} grader."
        if "temperature_delta" in arguments:
            verb = "höjer" if int(arguments["temperature_delta"]) > 0 else "sänker"
            return f"Jag {verb} temperaturen med {abs(int(arguments['temperature_delta']))} grad."
        if "fan_mode" in arguments:
            mode_text = "tyst" if arguments["fan_mode"] == "quiet" else str(arguments["fan_mode"])
            return f"Jag ställer fläkten på {mode_text}."
        if "fan_delta" in arguments:
            direction = "mer" if int(arguments["fan_delta"]) > 0 else "mindre"
            return f"Jag ordnar {direction} fläkt."
        return f"Jag justerar {name} i {room}."

    @staticmethod
    def _clean_text(value: object, field: str) -> str:
        if not isinstance(value, str):
            raise ToolValidationError(f"{field} måste vara text")
        cleaned = " ".join(value.strip().split())
        if not cleaned:
            raise ToolValidationError(f"{field} får inte vara tomt")
        if len(cleaned) > 160:
            raise ToolValidationError(f"{field} är för långt")
        return cleaned

    @staticmethod
    def _normalize_room(value: object) -> str:
        if value in (None, ""):
            return ""
        if not isinstance(value, str):
            raise ToolValidationError("output_room måste vara text")
        room = " ".join(value.strip().casefold().split())
        aliases = {"kök 2": "köket", "kök": "köket", "köket": "köket"}
        return aliases.get(room, room)

    @staticmethod
    def _percentage(value: object, field: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value not in range(1, 101):
            raise ToolValidationError(f"{field} måste vara 1–100")
        return value
