from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any
from uuid import uuid4

from .actions import DeviceAction
from .cast import CastService
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
    )

    def __init__(self, cast: CastService | None = None, lights: MagicHomeLightService | None = None, television: NecTvService | None = None):
        self.cast = cast
        self.lights = lights
        self.television = television

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
