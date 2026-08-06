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
    _PLAYLIST = re.compile(
        r"^\s*(?:skapa|gör)(?:\s+en)?\s+spellista(?:\s+(?:med|för|som)\s+)(.+?)\s*[.!?]*\s*$",
        re.IGNORECASE,
    )

    def plan(self, transcript: str, node_name: str) -> DeviceAction | None:
        playlist = self._PLAYLIST.match(transcript)
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
