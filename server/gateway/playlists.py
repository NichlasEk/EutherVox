from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tomllib
from uuid import uuid4


@dataclass(frozen=True)
class PlaylistTrack:
    provider: str
    provider_id: str
    title: str


@dataclass(frozen=True)
class LocalPlaylist:
    playlist_id: str
    owner: str
    title: str
    query: str
    created_at: str
    updated_at: str
    tracks: tuple[PlaylistTrack, ...] = ()
    youtube_playlist_id: str = ""

    @property
    def youtube_music_url(self) -> str:
        if not self.youtube_playlist_id:
            return ""
        return f"https://music.youtube.com/playlist?list={self.youtube_playlist_id}"


class TomlPlaylistStore:
    """Private, provider-neutral playlists stored as one atomic TOML file each."""

    def __init__(self, settings: dict, config_dir: Path):
        root = Path(str(settings.get("directory", "state/playlists")))
        self.root = root if root.is_absolute() else config_dir / root

    def create(self, owner: str, title: str, query: str) -> LocalPlaylist:
        self._require_owner(owner)
        now = datetime.now(timezone.utc).isoformat()
        playlist = LocalPlaylist(str(uuid4()), owner, title, query, now, now)
        self._write(playlist)
        return playlist

    def save(self, playlist: LocalPlaylist) -> LocalPlaylist:
        self._require_owner(playlist.owner)
        updated = replace(playlist, updated_at=datetime.now(timezone.utc).isoformat())
        self._write(updated)
        return updated

    def latest(self, owner: str) -> LocalPlaylist | None:
        self._require_owner(owner)
        playlists = [playlist for path in self.root.glob(f"{self._owner_key(owner)}-*.toml") if (playlist := self._read(path)).owner.casefold() == owner.casefold()]
        return max(playlists, key=lambda item: item.created_at, default=None)

    def _write(self, playlist: LocalPlaylist) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        self.root.chmod(0o700)
        path = self.root / f"{self._owner_key(playlist.owner)}-{playlist.playlist_id}.toml"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(self._serialize(playlist), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(path)

    @staticmethod
    def _serialize(playlist: LocalPlaylist) -> str:
        quote = lambda value: json.dumps(value, ensure_ascii=False)
        lines = [
            "schema_version = 1",
            f"playlist_id = {quote(playlist.playlist_id)}",
            f"owner = {quote(playlist.owner)}",
            f"title = {quote(playlist.title)}",
            f"query = {quote(playlist.query)}",
            f"created_at = {quote(playlist.created_at)}",
            f"updated_at = {quote(playlist.updated_at)}",
            f"youtube_playlist_id = {quote(playlist.youtube_playlist_id)}",
        ]
        for track in playlist.tracks:
            lines.extend([
                "",
                "[[tracks]]",
                f"provider = {quote(track.provider)}",
                f"provider_id = {quote(track.provider_id)}",
                f"title = {quote(track.title)}",
            ])
        return "\n".join(lines) + "\n"

    @staticmethod
    def _read(path: Path) -> LocalPlaylist:
        with path.open("rb") as source:
            raw = tomllib.load(source)
        tracks = tuple(
            PlaylistTrack(str(item["provider"]), str(item["provider_id"]), str(item.get("title", "")))
            for item in raw.get("tracks", [])
        )
        return LocalPlaylist(
            playlist_id=str(raw["playlist_id"]),
            owner=str(raw["owner"]),
            title=str(raw["title"]),
            query=str(raw["query"]),
            created_at=str(raw["created_at"]),
            updated_at=str(raw["updated_at"]),
            tracks=tracks,
            youtube_playlist_id=str(raw.get("youtube_playlist_id", "")),
        )

    @staticmethod
    def _owner_key(owner: str) -> str:
        return hashlib.sha256(owner.casefold().encode()).hexdigest()[:16]

    @staticmethod
    def _require_owner(owner: str) -> None:
        if not owner.strip():
            raise ValueError("En verifierad användare krävs för privata spellistor")
