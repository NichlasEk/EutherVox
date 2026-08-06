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
    _PLAY = re.compile(
        r"^\s*(?:(?:(?:kan\s+du|skulle\s+du\s+kunna)\s+)?spela(?:\s+upp)?|jag\s+(?:(?:vill|skulle\s+vilja)\s+(?:(?:att\s+du\s+)?spela|ha|höra|lyssna\s+på)|spelar)|kan\s+jag\s+få\s+höra|ge\s+mig|sätt\s+på|dra\s+igång)\s+(.+?)\s*[.!?]*\s*$",
        re.IGNORECASE,
    )
    _TRACK_NOUN_PREFIX = re.compile(r"^(?:låten\s+med\s+namnet|låten)\s+", re.IGNORECASE)
    _YOUTUBE_SUFFIX = re.compile(r"\s+(?:på|i)\s+youtube\s+music\s*$", re.IGNORECASE)
    _OUTPUT_SUFFIX = re.compile(r"\s+(?:i|på|till)\s+(?P<room>köket|kök\s*2)\s*$", re.IGNORECASE)
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
        match = self._PLAY.match(command)
        if not match:
            return None
        if re.match(r"^\s*in\b", match.group(1), re.IGNORECASE):
            return None
        query = self._YOUTUBE_SUFFIX.sub("", match.group(1)).strip(" .!?")
        query, output_room = self._extract_output(query)
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

    def _extract_output(self, text: str) -> tuple[str, str]:
        match = self._OUTPUT_SUFFIX.search(text)
        if not match:
            return text, ""
        room = "köket" if match.group("room").casefold().replace(" ", "") in {"köket", "kök2"} else match.group("room").casefold()
        return text[:match.start()].strip(" .!?"), room
