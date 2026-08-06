from __future__ import annotations

import asyncio
from dataclasses import dataclass
import re
from typing import Any, Callable
from urllib.parse import urlsplit


_VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{6,20}$")


@dataclass(frozen=True)
class ResolvedAudio:
    video_id: str
    url: str
    content_type: str
    title: str
    thumbnail: str


class YouTubeAudioResolver:
    """Resolve one short-lived YouTube audio URL without downloading media."""

    def __init__(self, socket_timeout_seconds: float = 10.0, downloader_factory: Callable | None = None):
        self.socket_timeout_seconds = socket_timeout_seconds
        self.downloader_factory = downloader_factory
        self._lock = asyncio.Lock()

    async def resolve(self, video_id: str) -> ResolvedAudio:
        if not _VIDEO_ID.fullmatch(video_id):
            raise ValueError("Ogiltigt YouTube-video-id")
        async with self._lock:
            return await asyncio.to_thread(self._resolve_sync, video_id)

    def _resolve_sync(self, video_id: str) -> ResolvedAudio:
        downloader_factory = self.downloader_factory
        if downloader_factory is None:
            try:
                import yt_dlp
            except ImportError as error:
                raise RuntimeError("yt-dlp är inte installerat") from error
            downloader_factory = yt_dlp.YoutubeDL

        options = {
            "format": "bestaudio[ext=m4a][acodec^=mp4a]/bestaudio[acodec!=none]/best",
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "skip_download": True,
            "socket_timeout": self.socket_timeout_seconds,
            "retries": 1,
            "extractor_retries": 1,
        }
        with downloader_factory(options) as downloader:
            info: dict[str, Any] = downloader.extract_info(
                f"https://www.youtube.com/watch?v={video_id}",
                download=False,
            )
        url = str(info.get("url", ""))
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or not parsed.hostname.endswith(".googlevideo.com"):
            raise RuntimeError("YouTube returnerade ingen betrodd HTTPS-ljudström")
        extension = str(info.get("ext", "")).casefold()
        content_type = "audio/mp4" if extension in {"m4a", "mp4"} else "audio/webm"
        return ResolvedAudio(
            video_id=video_id,
            url=url,
            content_type=content_type,
            title=str(info.get("title", "YouTube Music"))[:160],
            thumbnail=str(info.get("thumbnail", "")),
        )
