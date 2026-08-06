from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from .actions import DeviceAction
from .cast import CastService


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
    )

    def __init__(self, cast: CastService | None = None):
        self.cast = cast

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
