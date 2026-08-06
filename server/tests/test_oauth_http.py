from __future__ import annotations

import asyncio
import json

from websockets.datastructures import Headers
from websockets.http11 import Request

from gateway.main import OAuthHttpHandler


class FakeYouTube:
    configured = True
    authorized = False

    def can_use(self, authenticated_user: str) -> bool:
        return authenticated_user == "nichlas"

    def authorization_url(self) -> str:
        return "https://accounts.google.com/test"

    async def complete_authorization(self, code: str, state: str) -> None:
        assert code == "code-1"
        assert state == "state-1"


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


def test_oauth_start_rejects_non_owner():
    async def scenario():
        response = await OAuthHttpHandler(FakeYouTube())(
            None,
            Request("/euthervox/oauth/start", Headers({"X-Euther-User": "someone-else"})),
        )
        assert response.status_code == 403

    asyncio.run(scenario())
