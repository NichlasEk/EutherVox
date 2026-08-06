from __future__ import annotations

import asyncio
import json
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from websockets.datastructures import Headers
from websockets.http11 import Request

from gateway.main import OAuthHttpHandler
from gateway.youtube import YouTubePlaylistService, is_on_demand_music_result, normalize_music_search_query, rank_music_search_items


class FakeYouTube:
    configured = True

    def can_use(self, authenticated_user: str) -> bool:
        return bool(authenticated_user)

    def authorized(self, authenticated_user: str) -> bool:
        return False

    def authorization_url(self, authenticated_user: str) -> str:
        assert authenticated_user == "nichlas"
        return "https://accounts.google.com/test"

    async def complete_authorization(self, code: str, state: str, authenticated_user: str) -> None:
        assert code == "code-1"
        assert state == "state-1"
        assert authenticated_user == "nichlas"


def test_music_search_query_repairs_common_stt_joins_and_fillers():
    assert normalize_music_search_query("lite mörkcyberpunk") == "mörk cyberpunk"
    assert normalize_music_search_query("Något lugnt synthwave.") == "lugnt synthwave"
    assert normalize_music_search_query("Ghost") == "Ghost"


def test_music_search_skips_live_radio_and_24_7_streams():
    assert not is_on_demand_music_result({"snippet": {"title": "Cyberpunk Radio", "liveBroadcastContent": "live"}})
    assert not is_on_demand_music_result({"snippet": {"title": "Cyberpunk music 24/7", "liveBroadcastContent": "none"}})
    assert is_on_demand_music_result({"snippet": {"title": "Cyberpunk Mix", "liveBroadcastContent": "none"}})


def test_specific_song_search_prioritizes_official_result_over_cover():
    items = [
        {"id": {"videoId": "cover"}, "snippet": {"title": "Smells Like Teen Spirit cover"}},
        {"id": {"videoId": "official"}, "snippet": {"title": "Nirvana - Smells Like Teen Spirit (Official Music Video)"}},
        {"id": {"videoId": "reaction"}, "snippet": {"title": "Smells Like Teen Spirit reaction"}},
    ]

    ranked = rank_music_search_items(items, "Smells Like Teen Spirit")

    assert ranked[0]["id"]["videoId"] == "official"


def test_oauth_start_redirects_to_google():
    async def scenario():
        response = await OAuthHttpHandler(FakeYouTube())(
            None,
            Request("/euthervox/oauth/start", Headers({"X-Euther-User": "nichlas"})),
        )
        assert response.status_code == 302
        assert response.headers["Location"] == "https://accounts.google.com/test"

    asyncio.run(scenario())


def test_oauth_status_is_machine_readable_and_not_cached():
    async def scenario():
        response = await OAuthHttpHandler(FakeYouTube())(
            None,
            Request("/euthervox/oauth/status", Headers({"X-Euther-User": "nichlas"})),
        )
        assert response.status_code == 200
        assert json.loads(response.body) == {"configured": True, "authorized": False}
        assert response.headers["Cache-Control"] == "no-store"

    asyncio.run(scenario())


def test_oauth_start_rejects_unauthenticated_request():
    async def scenario():
        response = await OAuthHttpHandler(FakeYouTube())(
            None,
            Request("/euthervox/oauth/start", Headers()),
        )
        assert response.status_code == 403

    asyncio.run(scenario())


def test_oauth_tokens_and_state_are_bound_to_each_user(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("TEST_GOOGLE_CLIENT_ID", "client")
    monkeypatch.setenv("TEST_GOOGLE_CLIENT_SECRET", "secret")
    service = YouTubePlaylistService({
        "enabled": True,
        "client_id_env": "TEST_GOOGLE_CLIENT_ID",
        "client_secret_env": "TEST_GOOGLE_CLIENT_SECRET",
        "redirect_uri": "https://example.test/callback",
        "token_directory": str(tmp_path / "tokens"),
    }, tmp_path)
    service._write_token("anna", {"access_token": "anna-token", "refresh_token": "anna-refresh"})
    service._write_token("bo", {"access_token": "bo-token", "refresh_token": "bo-refresh"})

    assert service.authorized("anna")
    assert service.authorized("bo")
    assert len(list((tmp_path / "tokens").glob("*.json"))) == 2

    state = parse_qs(urlsplit(service.authorization_url("anna")).query)["state"][0]

    async def wrong_user():
        try:
            await service.complete_authorization("unused", state, "bo")
            assert False, "User-bound OAuth state should be rejected"
        except ValueError as error:
            assert "annan användare" in str(error)

    asyncio.run(wrong_user())
