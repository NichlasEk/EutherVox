"""Isolated yt-dlp process. stdout is JSON; never log signed stream URLs."""
import json
import sys
from dataclasses import asdict
import yt_dlp
from youtube_audio import YouTubeAudioResolver, _VIDEO_ID

if __name__ == '__main__':
    try:
        video = sys.argv[1]
        if not _VIDEO_ID.fullmatch(video):
            raise ValueError('Invalid video ID')
        # Explicit factory prevents recursion into the managed runtime.
        audio = YouTubeAudioResolver(downloader_factory=yt_dlp.YoutubeDL)._resolve_sync(video)
        print(json.dumps(asdict(audio)))
    except Exception:
        print('YouTube extraction failed', file=sys.stderr)
        sys.exit(1)
