from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
import os
from pathlib import Path
import secrets
import time
from urllib.parse import urlencode

import httpx


YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"


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
        self.owner_username = os.environ.get(str(settings.get("owner_username_env", "EUTHERVOX_YOUTUBE_OWNER")), "")
        self.redirect_uri = str(settings.get("redirect_uri", ""))
        token_path = Path(str(settings.get("token_path", "state/youtube-token.json")))
        self.token_path = token_path if token_path.is_absolute() else config_dir / token_path
        self.playlist_size = min(25, max(5, int(settings.get("playlist_size", 15))))
        self._states: dict[str, float] = {}
        self._lock = asyncio.Lock()

    @property
    def configured(self) -> bool:
        return self.enabled and bool(self.client_id and self.client_secret and self.redirect_uri)

    @property
    def authorized(self) -> bool:
        return self.configured and self.token_path.is_file()

    def can_use(self, authenticated_user: str) -> bool:
        return bool(self.owner_username) and secrets.compare_digest(authenticated_user.casefold(), self.owner_username.casefold())

    def authorization_url(self) -> str:
        if not self.configured:
            raise RuntimeError("YouTube OAuth är inte konfigurerat på servern")
        self._discard_expired_states()
        state = secrets.token_urlsafe(32)
        self._states[state] = time.monotonic() + 600
        return "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode({
            "client_id": self.client_id,
            "redirect_uri": self.redirect_uri,
            "response_type": "code",
            "scope": YOUTUBE_SCOPE,
            "access_type": "offline",
            "prompt": "consent",
            "state": state,
        })

    async def complete_authorization(self, code: str, state: str) -> None:
        self._discard_expired_states()
        expires = self._states.pop(state, None)
        if expires is None or expires < time.monotonic():
            raise ValueError("OAuth-state saknas eller har gått ut")
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
        self._write_token(token)

    def preview(self, query: str) -> PlaylistPreview:
        clean = query.strip(" .!?")
        title = f"EutherVox – {clean[:60]}"
        return PlaylistPreview(title=title, query=clean, track_count=self.playlist_size)

    async def create_playlist(self, preview: PlaylistPreview) -> CreatedPlaylist:
        async with self._lock:
            access_token = await self._access_token()
            headers = {"Authorization": f"Bearer {access_token}"}
            async with httpx.AsyncClient(timeout=30, trust_env=False, headers=headers) as client:
                search = await client.get("https://www.googleapis.com/youtube/v3/search", params={
                    "part": "snippet",
                    "type": "video",
                    "videoCategoryId": "10",
                    "maxResults": preview.track_count,
                    "q": preview.query,
                })
                search.raise_for_status()
                video_ids = [item["id"]["videoId"] for item in search.json().get("items", []) if item.get("id", {}).get("videoId")]
                if not video_ids:
                    raise RuntimeError("YouTube hittade inga musikträffar")
                created = await client.post(
                    "https://www.googleapis.com/youtube/v3/playlists",
                    params={"part": "snippet,status"},
                    json={
                        "snippet": {"title": preview.title, "description": f"Skapad av EutherVox från: {preview.query}"},
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
        return CreatedPlaylist(playlist_id, preview.title, added)

    async def _access_token(self) -> str:
        if not self.authorized:
            raise RuntimeError("YouTube-kontot är inte kopplat")
        token = json.loads(self.token_path.read_text(encoding="utf-8"))
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
        self._write_token(token)
        return str(token["access_token"])

    def _write_token(self, token: dict) -> None:
        stored = dict(token)
        stored["expires_at"] = time.time() + int(stored.get("expires_in", 3600))
        self.token_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.token_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(stored), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(self.token_path)

    def _discard_expired_states(self) -> None:
        now = time.monotonic()
        self._states = {state: expiry for state, expiry in self._states.items() if expiry >= now}
