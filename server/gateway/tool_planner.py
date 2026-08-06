from __future__ import annotations

import json
import logging
import re
from typing import Any

import httpx

from .actions import DeviceAction
from .tools import EutherVoxToolRegistry, ToolValidationError


LOG = logging.getLogger("euthervox.tools")


class OllamaToolPlanner:
    """Turns natural language into one validated action through Ollama tool calls."""

    _ACTION_HINT = re.compile(
        r"\b(spela|lyssna|höra|musik|låt|låtar|artist|album|spell?ista|lista|mix|stämning|sugen|önskar|vill\s+ha|ge\s+mig|köket|kök\s*2|högtalare|sätt\s+på|dra\s+igång)\b",
        re.IGNORECASE,
    )

    def __init__(
        self,
        registry: EutherVoxToolRegistry,
        base_url: str,
        model: str,
        timeout_seconds: float = 8.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.registry = registry
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    async def plan(self, transcript: str, node_name: str) -> DeviceAction | None:
        if not self._ACTION_HINT.search(transcript):
            return None
        rooms = ", ".join(item["room"] for item in self.registry.list_cast_targets()) or "inga"
        payload = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Du väljer EutherVox-verktyg. Anropa exakt ett verktyg endast när användaren faktiskt ber "
                        "att spela musik eller skapa en spellista. Frågor och vanlig konversation får inget verktygsanrop. "
                        "Indirekta önskemål som 'jag är sugen på mörk cyberpunk i köket' betyder att musiken ska spelas nu. "
                        "Önskemål om en bestämd låt, till exempel 'jag vill höra November Rain', ska anropa music_play "
                        "och behålla låttitel och eventuell artist exakt i query. "
                        "Ord som spellista, lista eller mix betyder playlist_create när användaren ber att få en sådan sparad. "
                        f"Konfigurerade rum: {rooms}. Hitta inte på rum. Behåll genre och stämning i query eller description."
                    ),
                },
                {"role": "user", "content": transcript},
            ],
            "tools": self.registry.ollama_tools,
            "options": {"temperature": 0, "num_ctx": 2048, "num_predict": 96},
        }
        timeout = httpx.Timeout(self.timeout_seconds, connect=min(5.0, self.timeout_seconds))
        try:
            async with httpx.AsyncClient(timeout=timeout, trust_env=False, transport=self.transport) as client:
                response = await client.post(f"{self.base_url}/api/chat", json=payload)
                response.raise_for_status()
            message = response.json().get("message", {})
            calls = message.get("tool_calls") or []
            if len(calls) != 1:
                return None
            function = calls[0].get("function", {})
            arguments: Any = function.get("arguments", {})
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            action = self.registry.create_action(str(function.get("name", "")), arguments, node_name)
            LOG.info("tool_planned name=%s action=%s", function.get("name"), action.name)
            return action
        except (httpx.HTTPError, json.JSONDecodeError, KeyError, TypeError, ToolValidationError) as error:
            LOG.warning("tool_planning_skipped error=%s", error)
            return None
