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
        r"\b(spela|lyssna|höra|musik|låt|låtar|artist|album|spell?ista|lista|mix|stämning|sugen|önskar|vill\s+ha|ge\s+mig|köket|kök\s*2|högtalare|sätt\s+på|dra\s+igång|wikipedia|wiki|slå\s+upp|läs(?:a)?\s+(?:upp\s+)?(?:om|artikeln)|sammanfatta|vem\s+(?:är|var)|vad\s+är|berätta\s+om|tänd|släck|lampa|lampor|ljus|belysning|ljusstyrka|procent|färg|röd|grön|blå|gul|lila|orange|rosa|turkos|vit|blinka|blinkande|strobe|regnbåg|tv|teven|skärm|hdmi|vga|bildingång|värmepump|pumpen|dammsugare|robotdammsugare|roboten|rumstemperatur|inomhustemperatur|temperatur|värme|kyla|kylning|fläkt|luften|fryser|svettas)\b",
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
    _PUMP_REFERENCE = re.compile(
        r"\b(?:värmepump(?:en)?|pumpen|luftvärmepump(?:en)?|temperatur(?:en)?|"
        r"rumstemperatur(?:en)?|inomhustemperatur(?:en)?|värme(?:n)?|kyla(?:n)?|"
        r"kylning(?:en)?|fläkt(?:en)?|luften|fryser|svettas)\b",
        re.IGNORECASE,
    )
    _REPORT_INTENT = re.compile(
        r"\b(?:status|rapport|lägesrapport|hur\s+(?:går|mår)|hur\s+långt|hur\s+mycket\s+återstår|när\s+är|vad\s+säger)\b",
        re.IGNORECASE,
    )
    _WASHER_REFERENCE = re.compile(
        r"\b(?:tvättmaskin(?:en)?|tvätt(?:en|rapport)?|maskinen)\b",
        re.IGNORECASE,
    )
    _VACUUM_REFERENCE = re.compile(
        r"\b(?:robotdammsug(?:are|aren)|dammsug(?:are|aren)|städrobot(?:en)?|roboten|ebba)\b",
        re.IGNORECASE,
    )
    _PUMP_CONTROL_INTENT = re.compile(
        r"\b(?:ställ|sätt|slå|starta|stäng|stoppa|höj|sänk|öka|minska|mer|mindre|"
        r"fixa|ordna|fryser|svettas)\w*\b",
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
    _TEMPERATURE_WORDS = {
        "sexton": 16, "sjutton": 17, "arton": 18, "nitton": 19,
        "tjugo": 20, "tjugoett": 21, "tjugo ett": 21,
        "tjugotvå": 22, "tjugo två": 22, "tjugotre": 23, "tjugo tre": 23,
        "tjugofyra": 24, "tjugo fyra": 24, "tjugofem": 25, "tjugo fem": 25,
        "tjugosex": 26, "tjugo sex": 26, "tjugosju": 27, "tjugo sju": 27,
        "tjugoåtta": 28, "tjugo åtta": 28, "tjugonio": 29, "tjugo nio": 29,
        "trettio": 30,
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
        deterministic = self.plan_deterministic(transcript, node_name)
        if deterministic is not None:
            return deterministic
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
                        "om musik, en spellista, ljusstyrning, TV-styrning, värmepumpsstatus, värmepumpsstyrning eller faktabaserad uppslagsinformation. Vanlig konversation får inget verktygsanrop. "
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
                        "Använd heat_pump_status för att läsa status eller temperatur. Använd heat_pump_control för att "
                        "slå på eller av pumpen, välja värme/kyla, sätta temperatur eller ändra fläkten. "
                        "'Jag fryser' betyder temperature_delta 1, 'jag svettas' betyder -1 och 'mer/mindre fläkt' "
                        "betyder fan_delta 1/-1. Ange aldrig både exakt och relativ ändring för samma egenskap. "
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
            if function.get("name") == "vacuum_control":
                return None
            arguments: Any = function.get("arguments", {})
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            action = self.registry.create_action(str(function.get("name", "")), arguments, node_name)
            LOG.info("tool_planned name=%s action=%s", function.get("name"), action.name)
            return action
        except (httpx.HTTPError, json.JSONDecodeError, KeyError, TypeError, ToolValidationError) as error:
            LOG.warning("tool_planning_skipped error_type=%s error=%s", type(error).__name__, error)
            return None

    def plan_deterministic(self, transcript: str, node_name: str) -> DeviceAction | None:
        vacuum = self._plan_vacuum_control(transcript, node_name)
        if vacuum is not None:
            return vacuum
        report = self._plan_report(transcript, node_name)
        if report is not None:
            LOG.info("tool_decision path=deterministic domain=report action=%s", report.name)
            return report
        pump = self._plan_pump(transcript, node_name)
        if pump is not None:
            LOG.info(
                "tool_decision path=deterministic domain=pump action=%s target=%s",
                pump.name, pump.arguments.get("target", "none"),
            )
            return pump
        light = self._plan_light(transcript, node_name)
        if light is not None:
            LOG.info(
                "tool_decision path=deterministic domain=lights action=%s target=%s",
                light.name, light.arguments.get("target", "none"),
            )
            return light
        television = self._plan_tv(transcript, node_name)
        if television is not None:
            LOG.info(
                "tool_decision path=deterministic domain=television action=%s target=%s",
                television.name, television.arguments.get("target", "none"),
            )
            return television
        return None

    def _plan_vacuum_control(self, transcript: str, node_name: str) -> DeviceAction | None:
        # Full utterance matches only: no negations, hypothetical requests, room
        # targets or model-generated motion. A spoken imperative is the request.
        text = transcript.casefold().strip().rstrip(".!?").strip()
        text = re.sub(r"^(?:kan du|skulle du kunna|snälla)\s+", "", text)
        text = re.sub(r"\s+tack$", "", text)
        target = r"(?:ebba|robotdammsugaren|dammsugaren|städroboten)"
        patterns = (
            (rf"(?:starta|fortsätt med) {target}|{target}[, ]+städa", "start"),
            (rf"pausa {target}|{target}[, ]+pausa", "pause"),
            (rf"stoppa {target}|{target}[, ]+stoppa", "stop"),
            (rf"skicka hem {target}|skicka {target} (?:hem|till laddaren)|{target}[, ]+(?:gå|åk) hem", "return-to-dock"),
        )
        for pattern, command in patterns:
            if re.fullmatch(pattern, text):
                try:
                    return self.registry.create_action("vacuum_control", {"command": command}, node_name)
                except ToolValidationError:
                    return None
        return None

    def _plan_report(self, transcript: str, node_name: str) -> DeviceAction | None:
        lowered = transcript.casefold()
        printer_action = None
        if re.search(r"\bskanna\b", lowered): printer_action = "printer_scan"
        elif re.search(r"\bskriv ut\b", lowered): printer_action = "printer_print"
        elif re.search(r"\b(?:skrivarjobb|utskriftsjobb)\b", lowered): printer_action = "printer_jobs"
        elif re.search(r"\b(?:skrivaren|skrivarstatus|toner|skrivarrapport)\b", lowered): printer_action = "printer_status"
        if printer_action:
            try: return self.registry.create_action(printer_action, {}, node_name)
            except ToolValidationError: return None
        if not self._REPORT_INTENT.search(lowered) and not re.search(
            r"\b(?:tvätt|värmepumps?|dammsugar|robotdammsugar)rapport\b", lowered
        ):
            return None
        if self._VACUUM_REFERENCE.search(lowered):
            try:
                return self.registry.create_action("vacuum_status", {}, node_name)
            except ToolValidationError:
                return None
        if self._WASHER_REFERENCE.search(lowered):
            try:
                return self.registry.create_action("washer_status", {}, node_name)
            except ToolValidationError:
                return None
        if self._PUMP_REFERENCE.search(lowered) or re.search(r"\bvärmepumps?rapport\b", lowered):
            targets = self.registry.list_pump_targets()
            if len(targets) == 1:
                return self.registry.create_action(
                    "heat_pump_status", {"target": str(targets[0]["name"])}, node_name
                )
        return None

    def _plan_pump(self, transcript: str, node_name: str) -> DeviceAction | None:
        targets = self.registry.list_pump_targets()
        lowered = transcript.casefold()
        if (
            not targets
            or not self._PUMP_REFERENCE.search(lowered)
            or not self._PUMP_CONTROL_INTENT.search(lowered)
        ):
            return None
        compact = self._compact(transcript)
        labels: list[tuple[str, str]] = []
        for item in targets:
            labels.extend([
                (str(item["name"]), str(item["name"])),
                (str(item["room"]), str(item["name"])),
            ])
        matches = [canonical for label, canonical in labels if self._compact(label) in compact]
        target = matches[0] if matches else (str(targets[0]["name"]) if len(targets) == 1 else "")
        if not target:
            rooms = ", ".join(str(item["room"]) for item in targets[:3])
            return DeviceAction(
                action_id=str(uuid4()),
                name="assistant.clarify",
                target_node=node_name,
                arguments={"domain": "pump"},
                acknowledgement=f"Vilken värmepump menar du? Jag har pumpar i {rooms}.",
            )

        arguments: dict[str, object] = {"target": target}
        if re.search(r"\b(?:stäng|slå|sätt|stoppa)\w*\s+av\b", lowered):
            arguments["power"] = False
        elif re.search(r"\b(?:sätt|slå|starta)\w*\s+på\b", lowered):
            arguments["power"] = True
        if re.search(r"\b(?:värme(?:n|läge)?|värma)\b", lowered):
            arguments.update(power=True, mode="heat")
        elif re.search(r"\b(?:kyla|kylning|kylläge|svalka)\b", lowered):
            arguments.update(power=True, mode="cool")

        exact_temperature = self._temperature(lowered)
        if exact_temperature is not None:
            arguments["target_temperature"] = exact_temperature
        elif re.search(r"\b(?:fryser|höj|öka)\w*\b", lowered) and "fläkt" not in lowered:
            arguments["temperature_delta"] = self._temperature_delta(lowered, 1)
        elif re.search(r"\b(?:svettas|sänk|minska)\w*\b", lowered) and "fläkt" not in lowered:
            arguments["temperature_delta"] = self._temperature_delta(lowered, -1)

        if re.search(r"\bfläkt(?:en)?\b", lowered):
            if re.search(r"\b(?:auto|automatisk(?:t)?)\b", lowered):
                arguments["fan_mode"] = "auto"
            elif re.search(r"\b(?:tyst|tystare|quiet)\b", lowered):
                arguments["fan_mode"] = "quiet"
            else:
                level = re.search(r"\b(?:läge|nivå|på)\s*([1-5])\b", lowered)
                if level:
                    arguments["fan_mode"] = level.group(1)
                elif re.search(r"\b(?:mer|höj|öka|snabbare|max)\w*\b", lowered):
                    arguments["fan_delta"] = 1
                elif re.search(r"\b(?:mindre|sänk|minska|långsammare)\w*\b", lowered):
                    arguments["fan_delta"] = -1
        elif re.search(r"\b(?:fixa|ordna)\w*\s+luften\b", lowered):
            arguments["fan_delta"] = 1

        if len(arguments) == 1:
            return None
        return self.registry.create_action("heat_pump_control", arguments, node_name)

    @classmethod
    def _temperature(cls, text: str) -> int | None:
        numeric = re.search(r"\b(1[6-9]|2\d|30)\s*(?:°\s*c|grader)?\b", text)
        if numeric:
            return int(numeric.group(1))
        for word, value in sorted(cls._TEMPERATURE_WORDS.items(), key=lambda item: -len(item[0])):
            if re.search(rf"\b{re.escape(word)}(?:\s+grader)?\b", text):
                return value
        return None

    @staticmethod
    def _temperature_delta(text: str, direction: int) -> int:
        match = re.search(r"\bmed\s+([1-3]|en|ett|två|tre)\s+grader?\b", text)
        if not match:
            return direction
        amount = {"en": 1, "ett": 1, "två": 2, "tre": 3}.get(
            match.group(1), int(match.group(1)) if match.group(1).isdigit() else 1
        )
        return direction * amount

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
