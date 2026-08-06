from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlencode

import httpx

from .playlists import LocalPlaylist, PlaylistTrack


YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

_LEADING_SEARCH_FILLER = re.compile(
    r"^(?:(?:lite|något|någon|några|en|ett)\s+)+",
    re.IGNORECASE,
)
_JOINED_MUSIC_TERM = re.compile(
    r"(?<=\w)(cyberpunk|synthwave|darkwave|retrowave|vaporwave|ambient|techno|jazz|metal|rock)\b",
    re.IGNORECASE,
)


def normalize_music_search_query(query: str) -> str:
    """Repair common Swedish STT joins without changing the spoken transcript."""
    clean = " ".join(query.strip(" .!?").split())
    clean = _LEADING_SEARCH_FILLER.sub("", clean)
    clean = _JOINED_MUSIC_TERM.sub(r" \1", clean)
    return clean or query.strip(" .!?")


@dataclass(frozen=True)
class PlaylistPreview:
    title: str
    query: str
    track_count: int


@dataclass(frozen=True)
class CreatedPlaylist:
    playlist_id: str
    title: str
    track_count: int

    @property
    def music_url(self) -> str:
        return f"https://music.youtube.com/playlist?list={self.playlist_id}"


class YouTubePlaylistService:
    def __init__(self, settings: dict, config_dir: Path):
        self.enabled = bool(settings.get("enabled", False))
        self.client_id = os.environ.get(str(settings.get("client_id_env", "EUTHERVOX_GOOGLE_CLIENT_ID")), "")
        self.client_secret = os.environ.get(str(settings.get("client_secret_env", "EUTHERVOX_GOOGLE_CLIENT_SECRET")), "")
        self.redirect_uri = str(settings.get("redirect_uri", ""))
        token_dir = Path(str(settings.get("token_directory", "state/youtube-tokens")))
        self.token_dir = token_dir if token_dir.is_absolute() else config_dir / token_dir
        self.playlist_size = min(25, max(5, int(settings.get("playlist_size", 15))))
        self._states: dict[str, tuple[float, str]] = {}
        self._lock = asyncio.Lock()

    @property
    def configured(self) -> bool:
        return self.enabled and bool(self.client_id and self.client_secret and self.redirect_uri)

    def authorized(self, authenticated_user: str) -> bool:
        return self.configured and self._token_path(authenticated_user).is_file()

    def can_use(self, authenticated_user: str) -> bool:
        return bool(authenticated_user.strip())

    def authorization_url(self, authenticated_user: str) -> str:
        if not self.configured:
            raise RuntimeError("YouTube OAuth är inte konfigurerat på servern")
        if not self.can_use(authenticated_user):
            raise RuntimeError("En verifierad användare krävs")
        self._discard_expired_states()
        state = secrets.token_urlsafe(32)
        self._states[state] = (time.monotonic() + 600, authenticated_user)
        return "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode({
            "client_id": self.client_id,
            "redirect_uri": self.redirect_uri,
            "response_type": "code",
            "scope": YOUTUBE_SCOPE,
            "access_type": "offline",
            "prompt": "consent",
            "state": state,
        })

    async def complete_authorization(self, code: str, state: str, authenticated_user: str) -> None:
        self._discard_expired_states()
        pending = self._states.pop(state, None)
        if pending is None or pending[0] < time.monotonic():
            raise ValueError("OAuth-state saknas eller har gått ut")
        if not secrets.compare_digest(pending[1].casefold(), authenticated_user.casefold()):
            raise ValueError("OAuth-state tillhör en annan användare")
        async with httpx.AsyncClient(timeout=20, trust_env=False) as client:
            response = await client.post("https://oauth2.googleapis.com/token", data={
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "code": code,
                "grant_type": "authorization_code",
                "redirect_uri": self.redirect_uri,
            })
            response.raise_for_status()
            token = response.json()
        if not token.get("refresh_token"):
            raise RuntimeError("Google returnerade ingen refresh token")
        self._write_token(authenticated_user, token)

    def preview(self, query: str) -> PlaylistPreview:
        clean = query.strip(" .!?")
        title = f"EutherVox – {clean[:60]}"
        return PlaylistPreview(title=title, query=clean, track_count=self.playlist_size)

    async def find_tracks(self, authenticated_user: str, preview: PlaylistPreview) -> tuple[PlaylistTrack, ...]:
        access_token = await self._access_token(authenticated_user)
        headers = {"Authorization": f"Bearer {access_token}"}
        search_query = normalize_music_search_query(preview.query)
        async with httpx.AsyncClient(timeout=30, trust_env=False, headers=headers) as client:
            search = await client.get("https://www.googleapis.com/youtube/v3/search", params={
                "part": "snippet",
                "type": "video",
                "videoCategoryId": "10",
                "maxResults": preview.track_count,
                "q": search_query,
            })
            search.raise_for_status()
        tracks = tuple(
            PlaylistTrack("youtube", item["id"]["videoId"], str(item.get("snippet", {}).get("title", "")))
            for item in search.json().get("items", [])
            if item.get("id", {}).get("videoId")
        )
        if not tracks:
            raise RuntimeError("YouTube hittade inga musikträffar")
        return tracks

    async def create_playlist(self, authenticated_user: str, playlist: LocalPlaylist) -> CreatedPlaylist:
        async with self._lock:
            access_token = await self._access_token(authenticated_user)
            headers = {"Authorization": f"Bearer {access_token}"}
            async with httpx.AsyncClient(timeout=30, trust_env=False, headers=headers) as client:
                video_ids = [track.provider_id for track in playlist.tracks if track.provider == "youtube"]
                if not video_ids:
                    raise RuntimeError("Den lokala spellistan saknar YouTube-träffar")
                created = await client.post(
                    "https://www.googleapis.com/youtube/v3/playlists",
                    params={"part": "snippet,status"},
                    json={
                        "snippet": {"title": playlist.title, "description": f"Skapad av EutherVox från: {playlist.query}"},
                        "status": {"privacyStatus": "private"},
                    },
                )
                created.raise_for_status()
                playlist_id = created.json()["id"]
                added = 0
                for video_id in video_ids:
                    response = await client.post(
                        "https://www.googleapis.com/youtube/v3/playlistItems",
                        params={"part": "snippet"},
                        json={"snippet": {"playlistId": playlist_id, "resourceId": {"kind": "youtube#video", "videoId": video_id}}},
                    )
                    response.raise_for_status()
                    added += 1
        return CreatedPlaylist(playlist_id, playlist.title, added)

    async def _access_token(self, authenticated_user: str) -> str:
        token_path = self._token_path(authenticated_user)
        if not self.authorized(authenticated_user):
            raise RuntimeError("YouTube-kontot är inte kopplat")
        token = json.loads(token_path.read_text(encoding="utf-8"))
        if token.get("access_token") and float(token.get("expires_at", 0)) > time.time() + 60:
            return str(token["access_token"])
        async with httpx.AsyncClient(timeout=20, trust_env=False) as client:
            response = await client.post("https://oauth2.googleapis.com/token", data={
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "refresh_token": token["refresh_token"],
                "grant_type": "refresh_token",
            })
            response.raise_for_status()
            refreshed = response.json()
        token.update(refreshed)
        self._write_token(authenticated_user, token)
        return str(token["access_token"])

    def _write_token(self, authenticated_user: str, token: dict) -> None:
        stored = dict(token)
        stored["expires_at"] = time.time() + int(stored.get("expires_in", 3600))
        self.token_dir.mkdir(parents=True, exist_ok=True)
        self.token_dir.chmod(0o700)
        token_path = self._token_path(authenticated_user)
        temporary = token_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(stored), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(token_path)

    def _token_path(self, authenticated_user: str) -> Path:
        key = hashlib.sha256(authenticated_user.casefold().encode()).hexdigest()[:24]
        return self.token_dir / f"{key}.json"

    def _discard_expired_states(self) -> None:
        now = time.monotonic()
        self._states = {state: pending for state, pending in self._states.items() if pending[0] >= now}
