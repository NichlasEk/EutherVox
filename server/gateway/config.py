from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import tomllib


@dataclass(frozen=True)
class AudioConfig:
    sample_rate: int
    channels: int
    frame_ms: int = 20
    buffer_ms: int = 120


@dataclass(frozen=True)
class GatewayConfig:
    config_dir: Path
    host: str
    port: int
    text_logging: bool
    response_timeout_seconds: float
    input_audio: AudioConfig
    output_audio: AudioConfig
    default_character: str
    profile_dir: Path
    stt_provider: str
    llm_provider: str
    tts_provider: str
    stt_settings: dict
    llm_settings: dict
    tts_settings: dict
    youtube_settings: dict
    playlist_settings: dict
    cast_settings: dict
    mcp_settings: dict
    wikipedia_settings: dict
    conversation_settings: dict


def load_config(path: str | Path) -> GatewayConfig:
    config_path = Path(path).resolve()
    with config_path.open("rb") as source:
        raw = tomllib.load(source)
    server = raw["server"]
    audio_in = raw["audio"]["input"]
    audio_out = raw["audio"]["output"]
    profile_dir = Path(raw["character"].get("profile_dir", "characters"))
    if not profile_dir.is_absolute():
        profile_dir = config_path.parent / profile_dir
    stt_settings = dict(raw["stt"])
    llm_settings = dict(raw["llm"])
    tts_settings = dict(raw["tts"])
    youtube_settings = dict(raw.get("youtube", {}))
    playlist_settings = dict(raw.get("playlists", {}))
    cast_settings = dict(raw.get("cast", {}))
    mcp_settings = dict(raw.get("mcp", {}))
    wikipedia_settings = dict(raw.get("wikipedia", {}))
    conversation_settings = dict(raw.get("conversation", {}))
    for settings, key in ((stt_settings, "download_root"), (tts_settings, "model_path")):
        if key in settings:
            value = Path(settings[key])
            if not value.is_absolute():
                settings[key] = str(config_path.parent / value)
    return GatewayConfig(
        config_dir=config_path.parent,
        host=server.get("host", "0.0.0.0"),
        port=int(server.get("port", 8788)),
        text_logging=bool(server.get("text_logging", False)),
        response_timeout_seconds=float(server.get("response_timeout_seconds", 15.0)),
        input_audio=AudioConfig(
            sample_rate=int(audio_in.get("sample_rate", 16000)),
            channels=int(audio_in.get("channels", 1)),
            frame_ms=int(audio_in.get("frame_ms", 20)),
        ),
        output_audio=AudioConfig(
            sample_rate=int(audio_out.get("sample_rate", 24000)),
            channels=int(audio_out.get("channels", 1)),
            buffer_ms=int(audio_out.get("buffer_ms", 120)),
        ),
        default_character=raw["character"].get("default", "skinnskattaren"),
        profile_dir=profile_dir,
        stt_provider=raw["stt"].get("provider", "mock"),
        llm_provider=raw["llm"].get("provider", "mock"),
        tts_provider=raw["tts"].get("provider", "mock"),
        stt_settings=stt_settings,
        llm_settings=llm_settings,
        tts_settings=tts_settings,
        youtube_settings=youtube_settings,
        playlist_settings=playlist_settings,
        cast_settings=cast_settings,
        mcp_settings=mcp_settings,
        wikipedia_settings=wikipedia_settings,
        conversation_settings=conversation_settings,
    )
