from __future__ import annotations

from dataclasses import dataclass
import re

import httpx


@dataclass(frozen=True)
class WikipediaArticle:
    title: str
    extract: str
    url: str


class WikipediaService:
    """Read-only access to introductory text from one configured Wikipedia."""

    def __init__(self, settings: dict, transport: httpx.AsyncBaseTransport | None = None):
        self.enabled = bool(settings.get("enabled", False))
        self.api_url = str(settings.get("api_url", "https://sv.wikipedia.org/w/api.php"))
        self.timeout_seconds = float(settings.get("timeout_seconds", 8.0))
        self.max_attempts = max(1, int(settings.get("max_attempts", 2)))
        self.max_extract_chars = int(settings.get("max_extract_chars", 6000))
        self.user_agent = str(
            settings.get(
                "user_agent",
                "EutherVox/0.1 (https://github.com/NichlasEk/EutherVox)",
            )
        )
        self.transport = transport

    async def lookup(self, query: str) -> WikipediaArticle:
        if not self.enabled:
            raise RuntimeError("Wikipedia-verktyget är inte aktiverat")
        cleaned = " ".join(query.strip().split())
        if not cleaned:
            raise ValueError("Wikipedia-sökningen får inte vara tom")
        params = {
            "action": "query",
            "generator": "search",
            "gsrsearch": cleaned,
            "gsrnamespace": "0",
            "gsrlimit": "1",
            "prop": "extracts|info",
            "exintro": "1",
            "explaintext": "1",
            "inprop": "url",
            "redirects": "1",
            "format": "json",
            "formatversion": "2",
        }
        timeout = httpx.Timeout(self.timeout_seconds, connect=min(5.0, self.timeout_seconds))
        async with httpx.AsyncClient(
            timeout=timeout,
            trust_env=False,
            transport=self.transport,
            follow_redirects=True,
            headers={"User-Agent": self.user_agent},
        ) as client:
            response = None
            last_error: httpx.HTTPError | None = None
            for attempt in range(self.max_attempts):
                try:
                    response = await client.get(self.api_url, params=params)
                    response.raise_for_status()
                    break
                except (httpx.TimeoutException, httpx.NetworkError) as error:
                    last_error = error
                    if attempt + 1 >= self.max_attempts:
                        raise RuntimeError(
                            f"Wikipedia svarade inte efter {self.max_attempts} försök"
                        ) from error
                except httpx.HTTPStatusError as error:
                    if error.response.status_code not in {429, 500, 502, 503, 504}:
                        raise RuntimeError(
                            f"Wikipedia svarade med HTTP {error.response.status_code}"
                        ) from error
                    last_error = error
                    if attempt + 1 >= self.max_attempts:
                        raise RuntimeError(
                            f"Wikipedia svarade inte efter {self.max_attempts} försök"
                        ) from error
            if response is None:
                raise RuntimeError("Wikipedia kunde inte nås") from last_error
        pages = response.json().get("query", {}).get("pages", [])
        page = next((item for item in pages if item.get("extract")), None)
        if not page:
            raise LookupError(f"Jag hittade ingen Wikipedia-artikel om {cleaned}")
        extract = self._truncate(str(page["extract"]).strip())
        return WikipediaArticle(
            title=str(page.get("title", cleaned)),
            extract=extract,
            url=str(page.get("fullurl", "")),
        )

    def _truncate(self, text: str) -> str:
        if len(text) <= self.max_extract_chars:
            return text
        shortened = text[: self.max_extract_chars].rstrip()
        sentence_end = max(shortened.rfind(". "), shortened.rfind("! "), shortened.rfind("? "))
        if sentence_end >= self.max_extract_chars // 2:
            shortened = shortened[: sentence_end + 1]
        return re.sub(r"\s+", " ", shortened).strip()
