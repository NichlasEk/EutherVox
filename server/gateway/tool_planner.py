from __future__ import annotations

import json
import logging
import re
from difflib import SequenceMatcher
from typing import Any
from uuid import uuid4

import httpx

from .actions import DeviceAction
from .tools import EutherVoxToolRegistry, ToolValidationError


LOG = logging.getLogger("euthervox.tools")


class OllamaToolPlanner:
    """Turns natural language into one validated action through Ollama tool calls."""

    _ACTION_HINT = re.compile(
        r"\b(spela|lyssna|höra|musik|låt|låtar|artist|album|spell?ista|lista|mix|stämning|sugen|önskar|vill\s+ha|ge\s+mig|köket|kök\s*2|högtalare|sätt\s+på|dra\s+igång|wikipedia|wiki|slå\s+upp|läs(?:a)?\s+(?:upp\s+)?(?:om|artikeln)|sammanfatta|vem\s+(?:är|var)|vad\s+är|berätta\s+om|tänd|släck|lampa|lampor|ljus|belysning|ljusstyrka|procent|färg|röd|grön|blå|gul|lila|orange|rosa|turkos|vit|blinka|blinkande|strobe|regnbåg|tv|teven|skärm|hdmi|vga|bildingång|värmepump|pumpen|rumstemperatur|inomhustemperatur)\b",
        re.IGNORECASE,
    )
    _LIGHT_INTENT = re.compile(
        r"\b(?:gör(?:a)?|ställ(?:a)?|sätt(?:a)?|ändra|tänd|släck|dimma|höj|sänk|"
        r"blinka|blinkande|skifta|låt)\b",
        re.IGNORECASE,
    )
    _LIGHT_REFERENCE = re.compile(
        r"\b(?:lyse(?:t)?|ljus(?:et)?|lampa(?:n|or|orna)?|belysning(?:en)?)\b",
        re.IGNORECASE,
    )
    _TV_REFERENCE = re.compile(
        r"\b(?:tv(?::n)?|teven|teve(?:n)?|skärm(?:en)?|nec[\s-]?(?:tv|teve(?:n)?)|necteven|nekteven|hdmi|vga|a\s*/?\s*v)\b",
        re.IGNORECASE,
    )
    _COLORS = (
        (re.compile(r"r[öo](?:d|t{1,2})\b", re.IGNORECASE), "#FF0000"),
        (re.compile(r"gr[öo](?:n|nt)\b", re.IGNORECASE), "#00FF00"),
        (re.compile(r"bl[åa](?:tt?)?\b", re.IGNORECASE), "#0000FF"),
        (re.compile(r"gul(?:t)?\b", re.IGNORECASE), "#FFD000"),
        (re.compile(r"lila\b", re.IGNORECASE), "#7A18C4"),
        (re.compile(r"orange(?:t)?\b", re.IGNORECASE), "#FF7000"),
        (re.compile(r"rosa\b", re.IGNORECASE), "#FF4081"),
        (re.compile(r"turkos(?:t)?\b", re.IGNORECASE), "#00D8C8"),
        (re.compile(r"vit(?:t)?\b", re.IGNORECASE), "#FFFFFF"),
    )
    _NUMBER_WORDS = {
        "tio": 10, "tjugo": 20, "trettio": 30, "fyrtio": 40, "femtio": 50,
        "sextio": 60, "sjuttio": 70, "åttio": 80, "nittio": 90, "hundra": 100,
    }

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
        deterministic_light = self._plan_light(transcript, node_name)
        if deterministic_light is not None:
            LOG.info("tool_decision path=deterministic domain=lights action=%s target=%s", deterministic_light.name, deterministic_light.arguments.get("target", "none"))
            return deterministic_light
        deterministic_tv = self._plan_tv(transcript, node_name)
        if deterministic_tv is not None:
            LOG.info("tool_decision path=deterministic domain=television action=%s target=%s", deterministic_tv.name, deterministic_tv.arguments["target"])
            return deterministic_tv
        if not self._ACTION_HINT.search(transcript):
            LOG.info("tool_decision path=conversation reason=no_action_hint")
            return None
        rooms = ", ".join(item["room"] for item in self.registry.list_cast_targets()) or "inga"
        lights = ", ".join(
            f"{item['name']} i {item['room']}" for item in self.registry.list_light_targets()
        ) or "inga"
        televisions = ", ".join(
            f"{item['name']} i {item['room']}" for item in self.registry.list_tv_targets()
        ) or "inga"
        pumps = ", ".join(
            f"{item['name']} i {item['room']}" for item in self.registry.list_pump_targets()
        ) or "inga"
        payload = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "think": False,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Du väljer EutherVox-verktyg. Anropa exakt ett verktyg endast när användaren faktiskt ber "
                        "om musik, en spellista, ljusstyrning, TV-styrning, värmepumpsstatus eller faktabaserad uppslagsinformation. Vanlig konversation får inget verktygsanrop. "
                        "Indirekta önskemål som 'jag är sugen på mörk cyberpunk i köket' betyder att musiken ska spelas nu. "
                        "Önskemål om en bestämd låt, till exempel 'jag vill höra November Rain', ska anropa music_play "
                        "och behålla låttitel och eventuell artist exakt i query. "
                        "Ord som spellista, lista eller mix betyder playlist_create när användaren ber att få en sådan sparad. "
                        "Använd wikipedia_lookup för fakta om offentliga ämnen, personer, platser och historiska händelser, "
                        "särskilt vid Wikipedia, slå upp, vem är, vad är, sammanfatta eller berätta om. Använd aldrig Wikipedia "
                        "för användarens privata saker, personliga råd, musikstyrning eller aktuella nyheter. Välj mode introduction "
                        "bara när användaren uttryckligen ber att få artikelns inledning uppläst; välj annars summary. "
                        "Använd light_set för av/på, statisk färg och intensitet. Översätt användarens färgbeskrivning till #RRGGBB. "
                        "Använd light_effect bara för ett mönster ur verktygets enum och välj normalt speed 40. "
                        "Använd tv_control för ström eller ingång på en konfigurerad NEC-TV. "
                        "Använd heat_pump_status endast för att läsa status eller temperatur; verktyget kan inte styra pumpen. "
                        f"Konfigurerade Cast-rum: {rooms}. Konfigurerade lampor: {lights}. Konfigurerade TV-apparater: {televisions}. Konfigurerade värmepumpar: {pumps}. Hitta aldrig på mål. "
                        "Behåll genre och stämning i query eller description."
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
            LOG.warning("tool_planning_skipped error_type=%s error=%s", type(error).__name__, error)
            return None

    def _plan_light(self, transcript: str, node_name: str) -> DeviceAction | None:
        targets = self.registry.list_light_targets()
        if not targets:
            return None
        if not self._LIGHT_INTENT.search(transcript):
            return None
        target = self._match_light_target(transcript, targets)
        light_reference = self._LIGHT_REFERENCE.search(transcript) is not None
        if not target and light_reference and len(targets) == 1:
            target = str(targets[0]["name"])
        if not target:
            if not light_reference:
                return None
            LOG.info("tool_decision path=fallback domain=lights reason=target_not_confident configured_targets=%d", len(targets))
            rooms = []
            for item in targets:
                room = str(item["room"])
                if room.casefold() not in {known.casefold() for known in rooms}:
                    rooms.append(room)
            examples = ", ".join(rooms[:3])
            return DeviceAction(
                action_id=str(uuid4()),
                name="assistant.clarify",
                target_node=node_name,
                arguments={"domain": "lights"},
                acknowledgement=f"Vilket rum menar du? Jag har ljus i {examples}.",
            )
        lowered = transcript.casefold()
        effect = self._effect(lowered)
        if effect:
            return self.registry.create_action(
                "light_effect",
                {"target": target, "effect": effect, "speed": self._speed(lowered)},
                node_name,
            )
        arguments: dict[str, object] = {"target": target}
        if re.search(r"\b(?:släck|släcka|stäng(?:a)?\s+av)\b", lowered):
            arguments["power"] = False
        elif re.search(r"\b(?:tänd|tända|sätt(?:a)?\s+på)\b", lowered):
            arguments["power"] = True
        color = next((value for pattern, value in self._COLORS if pattern.search(lowered)), None)
        if color:
            arguments["color"] = color
        brightness = self._percentage(lowered)
        if brightness is not None:
            arguments["brightness"] = brightness
        if len(arguments) == 1:
            return None
        return self.registry.create_action("light_set", arguments, node_name)

    def _plan_tv(self, transcript: str, node_name: str) -> DeviceAction | None:
        targets = self.registry.list_tv_targets()
        lowered = transcript.casefold()
        if not targets or not self._TV_REFERENCE.search(lowered):
            return None
        labels = []
        for item in targets:
            labels.extend([str(item["name"]), str(item["room"])])
        target = next((label for label in labels if self._compact(label) in self._compact(transcript)), "")
        if not target and len(targets) == 1:
            target = str(targets[0]["name"])
        if not target:
            return None
        command = ""
        if re.search(r"\b(?:stäng|slå)\w*\s+av\b", lowered):
            command = "power_off"
        elif re.search(r"\b(?:sätt|slå|starta)\w*\s+på\b", lowered):
            command = "power_on"
        elif re.search(r"\bhdmi\s*(?:1|ett)\b", lowered):
            command = "input_hdmi1"
        elif re.search(r"\bhdmi\s*(?:2|två)\b", lowered):
            command = "input_hdmi2"
        elif re.search(r"\bhdmi\s*(?:3|tre)\b", lowered):
            command = "input_hdmi3"
        elif "component" in lowered:
            command = "input_vga_component"
        elif "vga" in lowered:
            command = "input_vga_rgb"
        elif re.search(r"\ba\s*/?\s*v\b", lowered):
            command = "input_av"
        return self.registry.create_action("tv_control", {"target": target, "command": command}, node_name) if command else None

    @classmethod
    def _match_light_target(cls, transcript: str, targets: list[dict[str, object]]) -> str:
        compact = cls._compact(transcript)
        labels: list[str] = []
        for item in targets:
            for key in ("name", "room"):
                label = str(item[key])
                if label.casefold() not in {existing.casefold() for existing in labels}:
                    labels.append(label)
        exact = [label for label in labels if cls._compact(label) in compact]
        if exact:
            return max(exact, key=lambda label: len(cls._compact(label)))
        scored = []
        for label in labels:
            normalized = cls._compact(label)
            variants = {normalized}
            for suffix in ("rummet", "rum"):
                if normalized.endswith(suffix) and len(normalized) > len(suffix) + 2:
                    variants.add(normalized.removesuffix(suffix))
            score = max(cls._substring_similarity(compact, variant) for variant in variants)
            scored.append((score, label))
        scored.sort(reverse=True)
        if not scored or scored[0][0] < 0.78:
            return ""
        if len(scored) > 1 and scored[0][0] - scored[1][0] < 0.08:
            return ""
        LOG.info(
            "target_match domain=lights target=%s score=%.3f runner_up=%.3f",
            scored[0][1], scored[0][0], scored[1][0] if len(scored) > 1 else 0.0,
        )
        return scored[0][1]

    @staticmethod
    def _compact(value: str) -> str:
        return "".join(character for character in value.casefold() if character.isalnum())

    @staticmethod
    def _substring_similarity(text: str, target: str) -> float:
        if not text or not target:
            return 0.0
        best = 0.0
        # Names are often expanded by Whisper ("Estrids" -> "Esterhilds").
        # A slightly wider window recovers that case while the confidence and
        # runner-up margins above still prevent guessing between similar rooms.
        for length in range(max(2, len(target) - 3), len(target) + 5):
            for start in range(max(1, len(text) - length + 1)):
                best = max(best, SequenceMatcher(None, text[start:start + length], target).ratio())
        return best

    @classmethod
    def _percentage(cls, text: str) -> int | None:
        numeric = re.search(r"\b(100|[1-9]?\d)\s*(?:%|procent)\b", text)
        if numeric:
            return max(1, int(numeric.group(1)))
        for word, value in cls._NUMBER_WORDS.items():
            if re.search(rf"\b{word}\s+procent\b", text):
                return value
        return None

    @staticmethod
    def _effect(text: str) -> str:
        if not re.search(r"\b(?:blinka|blinkande|strobe|skifta|regnbåg)\w*\b", text):
            return ""
        if "regnbåg" in text:
            return "rainbow_strobe" if re.search(r"\b(?:blinka|blinkande|strobe)\b", text) else "rainbow_fade"
        colors = {
            "röd": "red_strobe", "rött": "red_strobe", "röt": "red_strobe",
            "grön": "green_strobe", "grönt": "green_strobe",
            "blå": "blue_strobe", "blått": "blue_strobe",
            "lila": "purple_strobe", "vit": "white_strobe", "vitt": "white_strobe",
        }
        return next((effect for word, effect in colors.items() if word in text), "rainbow_fade")

    @classmethod
    def _speed(cls, text: str) -> int:
        if re.search(r"\b(?:långsam|långsamt|sakta)\b", text):
            return 20
        if re.search(r"\b(?:snabb|snabbt|fort)\b", text):
            return 80
        return cls._percentage(text) or 40
