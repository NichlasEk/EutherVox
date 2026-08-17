from __future__ import annotations

from dataclasses import dataclass
import re
from uuid import uuid4


@dataclass(frozen=True)
class DeviceAction:
    action_id: str
    name: str
    target_node: str
    arguments: dict[str, object]
    acknowledgement: str
    requires_confirmation: bool = False

    def to_message(self, utterance_id: str) -> dict:
        return {
            "type": "action.request",
            "action_id": self.action_id,
            "utterance_id": utterance_id,
            "name": self.name,
            "target": {"kind": "node", "node_name": self.target_node},
            "arguments": self.arguments,
            "requires_confirmation": self.requires_confirmation,
        }


class ActionPlanner:
    """Deterministic allowlisted commands that must never depend on LLM prose."""

    _CONVERSATION_PREFIX = re.compile(
        r"^\s*(?:(?:okej|ok|hörru|du|snälla|skinnskattaren)\s*[,.:!?]?\s+)+",
        re.IGNORECASE,
    )
    _RETRY_PREFIX = re.compile(
        r"^\s*(?:prova|försök|testa)\s+(?:igen\s+)?(?:att\s+)?",
        re.IGNORECASE,
    )
    _PLAY = re.compile(
        r"^\s*(?:(?:(?:kan\s+du|skulle\s+du\s+kunna)\s+)?spela(?:\s+upp)?|jag\s+(?:(?:vill|skulle\s+vilja)\s+(?:(?:att\s+du\s+)?spela|ha|höra|lyssna\s+på)|spelar)|kan\s+jag\s+få\s+höra|ge\s+mig|sätt\s+på|dra\s+igång)\s+(.+?)\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _TRACK_NOUN_PREFIX = re.compile(r"^(?:låten\s+med\s+namnet|låten)\s+", re.IGNORECASE)
    _WIKIPEDIA_TOKEN = re.compile(
        r"\b(?:wikipedia|wiki\s*pedia|vilket\s+pedia|vicket\s+pedia)\b",
        re.IGNORECASE,
    )
    _WIKIPEDIA_REQUEST_PREFIX = re.compile(
        r"^\s*(?:(?:kan\s+du|skulle\s+du\s+kunna)\s+)?"
        r"(?:kolla(?:\s+upp)?|sök(?:a)?(?:\s+efter)?|slå\s+upp|leta(?:\s+efter|\s+upp)?|"
        r"sammanfatta|berätta\s+om|vad\s+(?:står|säger)\s+(?:det\s+)?om|"
        r"läs(?:a)?(?:\s+upp)?(?:\s+(?:inledningen|artikeln))?(?:\s+(?:av|om))?)\s+",
        re.IGNORECASE,
    )
    _MEDIA_CONTROL = re.compile(
        r"^\s*(?:(?:kan\s+du|skulle\s+du\s+kunna)\s+)?"
        r"(?P<command>pausa|pause|sätt(?:a)?\s+på\s+paus|fortsätt(?:a)?(?:\s+spela)?|spela\s+vidare|återuppta|resume|stoppa|stäng(?:a)?\s+av|sluta\s+spela)"
        r"(?:\s+(?:musiken|låten|upp\s*spelningen|spelningen|den))?(?:\s+(?:tack|nu))?\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _YOUTUBE_SUFFIX = re.compile(r"\s+(?:på|i)\s+youtube\s+music\s*$", re.IGNORECASE)
    # Whisper occasionally joins a trailing "tack" to the room or hears
    # köket as köken. Keep those observed variants out of the music query.
    _OUTPUT_SUFFIX = re.compile(
        r"\s+(?:(?:här\s+)?(?:i|på|till)\s+"
        r"(?:(?:nest(?:en)?|högtalaren)\s+(?:här\s+)?(?:i|på)\s+)?|"
        r"på\s+kökshögtalaren\s*|)"
        r"(?P<room>kök(?:et(?:ack|ag)?|en)?|kök\s*2)"
        r"(?:\s*,?\s*tack)?\s*$",
        re.IGNORECASE,
    )
    _SPEAKER_SUFFIX = re.compile(r"\s+på\s+(?:högtalaren|nest(?:en)?)\s*$", re.IGNORECASE)
    _PLAYLIST_AFTER_NOUN = re.compile(
        r"^\s*(?:(?:skulle\s+du\s+kunna|kan\s+du)\s+)?(?:skapa|gör(?:a)?|fixa|sätt(?:a)?\s+ihop)(?:\s+mig)?(?:\s+en)?\s+spell?ist[ae](?:\s+(?:med|för|som|av)\s+)(.+?)\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _PLAYLIST_BEFORE_NOUN = re.compile(
        r"^\s*(?:(?:skulle\s+du\s+kunna|kan\s+du)\s+)?(?:skapa|gör(?:a)?|fixa|sätt(?:a)?\s+ihop)(?:\s+mig)?(?:\s+en)?\s+(.+?)(?:\s+|-)?spell?ist[ae](?:\s+åt\s+mig)?\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _PLAYLIST_WANT = re.compile(
        r"^\s*(?:jag\s+)?(?:vill|skulle\s+vilja)\s+ha(?:\s+mig)?(?:\s+en)?\s+(.+?)(?:\s+|-)?spell?ist[ae]\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _PLAYLIST_WANT_AFTER_NOUN = re.compile(
        r"^\s*(?:jag\s+)?(?:vill|skulle\s+vilja)\s+ha(?:\s+mig)?(?:\s+en)?\s+spell?ist[ae](?:\s+(?:med|för|som|av)\s+)(.+?)\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _PLAYLIST_GIVE = re.compile(
        r"^\s*ge\s+mig(?:\s+en)?\s+(.+?)(?:\s+|-)?spell?ist[ae]\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _PLAYLIST_BARE = re.compile(
        r"^\s*(?!(?:kan|vad|hur|varför|berätta|förklara|jag)\b)(.+?)(?:\s+|-)?spell?ist[ae](?:\s+åt\s+mig)?\s*[.!?]*\s*$",
        re.IGNORECASE,
    )

    def plan(self, transcript: str, node_name: str) -> DeviceAction | None:
        command = self._CONVERSATION_PREFIX.sub("", transcript)
        command = self._RETRY_PREFIX.sub("", command)
        wikipedia = self._plan_wikipedia(command, node_name)
        if wikipedia:
            return wikipedia
        control_text, control_room = self._extract_output(command.strip(" .!?"))
        control = self._MEDIA_CONTROL.match(control_text)
        if control:
            spoken_command = control.group("command").casefold()
            if spoken_command in {"pausa", "pause", "sätt på paus", "sätta på paus"}:
                action_name, verb = "media.pause", "pausar"
            elif spoken_command in {"fortsätt", "fortsätta", "fortsätt spela", "fortsätta spela", "spela vidare", "återuppta", "resume"}:
                action_name, verb = "media.resume", "fortsätter"
            else:
                action_name, verb = "media.stop", "stoppar"
            arguments: dict[str, object] = {}
            if control_room:
                arguments["output_room"] = control_room
            return DeviceAction(
                action_id=str(uuid4()),
                name=action_name,
                target_node=node_name,
                arguments=arguments,
                acknowledgement=f"Jag {verb} musiken" + (f" i {control_room}." if control_room else "."),
            )
        playlist_command = self._YOUTUBE_SUFFIX.sub("", command.strip(" .!?"))
        playlist_command, output_room = self._extract_output(playlist_command)
        playlist = (
            self._PLAYLIST_AFTER_NOUN.match(playlist_command)
            or self._PLAYLIST_BEFORE_NOUN.match(playlist_command)
            or self._PLAYLIST_WANT.match(playlist_command)
            or self._PLAYLIST_WANT_AFTER_NOUN.match(playlist_command)
            or self._PLAYLIST_GIVE.match(playlist_command)
            or self._PLAYLIST_BARE.match(playlist_command)
        )
        if playlist:
            query = playlist.group(1).strip(" .!?")
            if query:
                arguments = {"provider": "euthervox", "query": query}
                if output_room:
                    arguments["output_room"] = output_room
                return DeviceAction(
                    action_id=str(uuid4()),
                    name="playlist.create",
                    target_node=node_name,
                    arguments=arguments,
                    acknowledgement=(
                        f"Jag kan skapa en privat lista med {query} och spela den i {output_room}. Bekräfta i appen."
                        if output_room
                        else f"Jag kan skapa en privat lista med {query} och spegla den till YouTube Music när kontot är kopplat. Bekräfta i appen."
                    ),
                    requires_confirmation=True,
                )
        if re.match(r"^\s*spela\s+in\b", command, re.IGNORECASE):
            return None
        bare_request = bool(re.search(r"(?:,|\s)\s*tack\s*[.!?]*\s*$", command, re.IGNORECASE))
        play_command, inferred_room = self._extract_output(command.strip(" .!?"))
        match = self._PLAY.match(play_command)
        if not match and bare_request and inferred_room:
            match = re.match(r"^\s*(.+?)\s*$", play_command)
        if not match:
            return None
        if re.match(r"^\s*in\b", match.group(1), re.IGNORECASE):
            return None
        query = self._YOUTUBE_SUFFIX.sub("", match.group(1)).strip(" .!?")
        query, output_room = self._extract_output(query)
        output_room = output_room or inferred_room
        query = self._TRACK_NOUN_PREFIX.sub("", query).strip(" .!?")
        if not query:
            return None
        arguments = {"provider": "youtube_music", "query": query}
        if output_room:
            arguments["output_room"] = output_room
        return DeviceAction(
            action_id=str(uuid4()),
            name="media.play",
            target_node=node_name,
            arguments=arguments,
            acknowledgement=(
                f"Jag spelar {query} i {output_room}." if output_room else f"Jag skickar {query} till YouTube Music."
            ),
        )

    def _plan_wikipedia(self, command: str, node_name: str) -> DeviceAction | None:
        match = self._WIKIPEDIA_TOKEN.search(command)
        if not match:
            return None
        mode = (
            "introduction"
            if re.search(r"\b(?:läs|läsa)\b.*\b(?:inledningen|artikeln)\b", command, re.IGNORECASE)
            else "summary"
        )
        before = command[: match.start()].strip(" .,!?:;-")
        after = command[match.end() :].strip(" .,!?:;-")
        after = re.sub(r"^(?:artikeln\s+)?(?:om|efter)\s+", "", after, flags=re.IGNORECASE)
        if after:
            query = after
        else:
            before = re.sub(r"\s+(?:på|i|från)\s*$", "", before, flags=re.IGNORECASE)
            query = self._WIKIPEDIA_REQUEST_PREFIX.sub("", before).strip(" .,!?:;-")
        generic_query = re.sub(
            r"^(?:kan\s+du|skulle\s+du\s+kunna)\s+",
            "",
            query,
            flags=re.IGNORECASE,
        ).casefold()
        if generic_query in {"", "kolla", "kolla upp", "köra", "söka", "slå upp", "gå in"}:
            query = ""
        return DeviceAction(
            action_id=str(uuid4()),
            name="knowledge.wikipedia",
            target_node=node_name,
            arguments={"query": query, "mode": mode},
            acknowledgement=(
                f"Jag slår upp {query} på svenska Wikipedia."
                if query else "Vad vill du att jag slår upp på Wikipedia?"
            ),
        )

    def _extract_output(self, text: str) -> tuple[str, str]:
        speaker = self._SPEAKER_SUFFIX.search(text)
        if speaker:
            return text[:speaker.start()].strip(" .!?"), "köket"
        match = self._OUTPUT_SUFFIX.search(text)
        if not match:
            return text, ""
        return text[:match.start()].strip(" .!?"), "köket"
