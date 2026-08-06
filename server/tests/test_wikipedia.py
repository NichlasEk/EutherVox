from __future__ import annotations

import asyncio
import json

import httpx

from gateway.wikipedia import WikipediaService


def test_wikipedia_lookup_returns_intro_and_source_url():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/w/api.php"
        assert request.url.params["gsrsearch"] == "Skinnskatteberg"
        assert request.url.params["exintro"] == "1"
        assert request.headers["user-agent"].startswith("EutherVox/")
        return httpx.Response(200, json={
            "query": {
                "pages": [{
                    "title": "Skinnskatteberg",
                    "extract": "Skinnskatteberg är en tätort i Västmanland.",
                    "fullurl": "https://sv.wikipedia.org/wiki/Skinnskatteberg",
                }]
            }
        })

    async def scenario():
        service = WikipediaService(
            {"enabled": True, "api_url": "https://sv.wikipedia.test/w/api.php"},
            transport=httpx.MockTransport(handler),
        )
        article = await service.lookup("  Skinnskatteberg ")

        assert article.title == "Skinnskatteberg"
        assert article.extract == "Skinnskatteberg är en tätort i Västmanland."
        assert article.url.endswith("/wiki/Skinnskatteberg")

    asyncio.run(scenario())


def test_wikipedia_lookup_reports_missing_article():
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=json.dumps({"batchcomplete": True}).encode())

    async def scenario():
        service = WikipediaService(
            {"enabled": True, "api_url": "https://sv.wikipedia.test/w/api.php"},
            transport=httpx.MockTransport(handler),
        )
        try:
            await service.lookup("Finns inte")
            assert False, "LookupError expected"
        except LookupError as error:
            assert "ingen Wikipedia-artikel" in str(error)

    asyncio.run(scenario())
