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

    _PLAY = re.compile(r"^\s*spela(?:\s+upp)?\s+(.+?)\s*[.!?]*\s*$", re.IGNORECASE)
    _YOUTUBE_SUFFIX = re.compile(r"\s+(?:på|i)\s+youtube\s+music\s*$", re.IGNORECASE)
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
        playlist_command = self._YOUTUBE_SUFFIX.sub("", transcript.strip(" .!?"))
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
                return DeviceAction(
                    action_id=str(uuid4()),
                    name="playlist.create",
                    target_node=node_name,
                    arguments={"provider": "euthervox", "query": query},
                    acknowledgement=f"Jag kan skapa en privat lista med {query} och spegla den till YouTube Music när kontot är kopplat. Bekräfta i appen.",
                    requires_confirmation=True,
                )
        if re.match(r"^\s*spela\s+in\b", transcript, re.IGNORECASE):
            return None
        match = self._PLAY.match(transcript)
        if not match:
            return None
        query = self._YOUTUBE_SUFFIX.sub("", match.group(1)).strip(" .!?")
        if not query:
            return None
        return DeviceAction(
            action_id=str(uuid4()),
            name="media.play",
            target_node=node_name,
            arguments={"provider": "youtube_music", "query": query},
            acknowledgement=f"Jag skickar {query} till YouTube Music.",
        )
