"""Owner-authorized facade for the independent EutherSignal HTTP API."""

from pathlib import Path
from uuid import UUID
import re
import httpx


class SignalService:
    def __init__(self, settings):
        self.enabled = settings.get("enabled") is True
        self.users = settings.get("allowed_users", [])
        self.base_url = settings.get("base_url", "http://127.0.0.1:8797").rstrip("/")
        self.token_file = Path(
            settings.get("token_file", "/home/nichlas/EutherSignal/state/api-token")
        )

    def can_use(self, user):
        return bool(self.enabled and user and user in self.users)

    async def request(
        self, user, command="status", identity=None, question="", conversation="vox"
    ):
        if not self.can_use(user):
            raise ValueError("EutherSignal är inte tillgänglig för detta konto.")
        if command in {"status", "profile", "sources", "reports"}:
            path = "/v1/" + command
            body = None
        elif command in {"collect", "cancel"}:
            path = "/v1/" + command
            body = {}
        elif command == "report":
            path = "/v1/reports/" + str(UUID(identity))
            body = None
        elif command == "ask":
            if not isinstance(question, str) or not 0 < len(question) <= 1200:
                raise ValueError("Frågan måste innehålla 1–1200 tecken")
            path = "/v1/reports/" + str(UUID(identity)) + "/ask"
            body = {"question": question, "conversation_id": conversation}
        else:
            raise ValueError("Okänt Signal-kommando")
        try:
            async with httpx.AsyncClient(
                timeout=620 if command == "ask" else 15,
                trust_env=False,
                headers={
                    "Authorization": "Bearer " + self.token_file.read_text().strip()
                },
            ) as client:
                response = await (
                    client.get(self.base_url + path)
                    if body is None
                    else client.post(self.base_url + path, json=body)
                )
                response.raise_for_status()
                return response.json()
        except (httpx.HTTPError, OSError) as error:
            raise ValueError(
                "Signal är tillfälligt upptaget eller otillgängligt. Kontrollera rapportstatus."
            ) from error


def signal_intent(text):
    t = " ".join(re.findall(r"\w+", text.lower()))
    if re.search(r"\b(?:lämna|avsluta|sluta)\b.*\b(?:signal|nyheter|rapporten)\b", t):
        return "leave"
    if not re.search(
        r"\b(?:euthersignal|signal|nyhetsrapport(?:en)?|nyhetsbriefing(?:en)?)\b", t
    ):
        return None
    if re.search(r"\b(?:inte|aldrig)\b", t):
        return None
    if re.search(r"\b(?:samla|hämta|skapa|uppdatera)\b", t) or "vad blev möjligt" in t:
        return "collect"
    if re.search(
        r"\b(?:läs|berätta|rapportera|nyhetsrapport(?:en)?|nyhetsbriefing(?:en)?)\b", t
    ):
        return "speak"
    return None


def speech_chunks(text, limit=500):
    text = re.sub(r"\[([^\]]+)\]\(https?://[^)]+\)", r"\1", text)
    text = re.sub(r"https?://\S+", "länk i rapporten", text)
    text = re.sub(r"\[[^\]]{1,24}\]", "", text)
    words = [
        word[i : i + limit] for word in text.split() for i in range(0, len(word), limit)
    ]
    chunk = []
    for word in words:
        if sum(len(w) + 1 for w in chunk) + len(word) > limit and chunk:
            yield " ".join(chunk)
            chunk = []
        chunk.append(word)
    if chunk:
        yield " ".join(chunk)


def spoken_briefing(report):
    if not report.get("items"):
        return report.get("spoken", "Ingen rapport finns ännu.")
    sources = {s["id"]: s["source"] for s in report.get("sources", [])}
    return (
        "EutherSignal. "
        + report.get("introduction", "")
        + " "
        + " ".join(
            f"{index + 1}. Enligt {sources.get(item['source_id'], 'den angivna källan')}: {item['headline']}. {item['summary']} Viktig begränsning: {item['caveat']}"
            for index, item in enumerate(report["items"][:3])
        )
    )
