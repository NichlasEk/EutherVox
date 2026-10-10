"""Owner-scoped report access through a fixed, forced-command SSH bridge."""

import asyncio
import json
import pathlib
import time


class ScryerService:
    def __init__(self, settings):
        self.enabled = settings.get("enabled") is True
        self.users = settings.get("allowed_users", [])
        self.key = pathlib.Path.home() / ".ssh/euther_scryer_reports"
        self.lock = asyncio.Lock()
        self.cached = None
        self.at = 0

    def can_use(self, user):
        return bool(self.enabled and user and user in self.users)

    async def request(self, user, command="list", identity=None):
        if not self.can_use(user) or command not in {
            "list",
            "report-ack",
            "report-snooze",
        }:
            raise ValueError("Åtkomst till Scryer nekad")
        if command != "list" and (not isinstance(identity, str) or len(identity) > 80):
            raise ValueError("Ogiltig rapport")
        async with self.lock:
            if (
                command == "list"
                and self.cached is not None
                and time.monotonic() - self.at < 30
            ):
                return self.cached
            process = None
            try:
                process = await asyncio.create_subprocess_exec(
                    "ssh",
                    "-F",
                    "/dev/null",
                    "-i",
                    str(self.key),
                    "-o",
                    "IdentitiesOnly=yes",
                    "-o",
                    "BatchMode=yes",
                    "-o",
                    "StrictHostKeyChecking=yes",
                    "-o",
                    "ConnectTimeout=4",
                    "nichlas@192.168.32.186",
                    "scryer-reports",
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                )
                output, _ = await asyncio.wait_for(
                    process.communicate(
                        json.dumps({"command": command, "id": identity}).encode()
                    ),
                    timeout=15,
                )
                if process.returncode != 0 or len(output) > 131072:
                    raise ValueError("unavailable")
                data = json.loads(output)
                if (
                    data.get("available") is not True
                    or not isinstance(data.get("reports"), list)
                    or len(data["reports"]) > 64
                ):
                    raise ValueError("invalid reports")
                self.cached, self.at = data, time.monotonic()
                return data
            except (OSError, ValueError, asyncio.TimeoutError):
                self.cached = None
                return {
                    "available": False,
                    "reports": [],
                    "error": "Kunde inte hämta rapporterna. Eventuell kvittering är inte bekräftad.",
                }
            finally:
                if process and process.returncode is None:
                    process.kill()
                    await process.wait()


def wants_report(text):
    import re

    text = " ".join(re.sub(r"[^\wåäö]+", " ", text.lower()).split())
    reference = re.search(
        r"\b(?:euther\s*)?(?:scryer|scryern|skrajer|scrajer|siaren|siare|orakel|oraklet)\b",
        text,
    )
    if not reference or re.search(r"\b(?:inte|aldrig|sluta|avbryt)\b", text):
        return False
    return bool(
        re.search(r"\b(?:rapportera|lägesrapport|statusrapport|systemrapport)\b", text)
        or re.search(
            r"\b(?:ge|säg|berätta|visa|läs|kolla|kör)\b.*\b(?:rapport|läget|status|sanning|lägesbild|mår)\b",
            text,
        )
        or re.search(
            r"\b(?:hur står det till|hur är läget|hur mår systemen|vad har du sett|vad händer|några problem|något att oroa|allt lugnt)\b",
            text,
        )
        or re.fullmatch(
            r"(?:scryer|scryern|siaren|oraklet|orakel) (?:rapport|status|läget)", text
        )
    )


def spoken_report(data, now=None):
    import datetime as dt

    now = time.time() if now is None else now
    if not data.get("available"):
        return "Jag når inte Scryers rapporter just nu. Jag kan därför inte säga hur systemen mår."
    try:
        age = (
            now
            - dt.datetime.fromisoformat(
                data["inventory_at"].replace("Z", "+00:00")
            ).timestamp()
        )
    except (ValueError, TypeError, KeyError):
        return (
            "Scryer saknar daterat underlag. Jag kan inte ge en aktuell lägesrapport."
        )
    stale = age < -60 or age >= 86400
    stopped = data.get("paused") or data.get("disabled") or data.get("observer_error")
    prefix = "Scryers lägesrapport. "
    if stale or stopped:
        prefix += "Övervakningen är pausad eller underlaget är gammalt eller ofullständigt. Detta är bara senast kända läge. "
    else:
        minutes = max(0, int(age / 60))
        prefix += f"Inventeringen är ungefär {minutes} minuter gammal. "
    rows = data.get("reports", [])
    active = [r for r in rows if r.get("state") == "open"]
    unknown = sum(r.get("state") in {"pending", "unconfirmed"} for r in rows)
    resolved = sum(r.get("state") == "resolved" for r in rows)
    parts = [prefix]
    if active:
        parts.append(f"{len(active)} öppna problem finns rapporterade.")
        labels = {
            "backup-mirror-apansson": "backupspegeln",
            "ebba-map-sync": "Ebbas kartarkiv",
        }
        status = {
            "failed": "rapporterat fel",
            "offline": "rapporterad frånkoppling",
            "degraded": "försämrad status",
        }
        for r in active[:3]:
            name = labels.get(r["id"], r["id"].replace("-", " "))
            parts.append(f'{name}: {status.get(r.get("status"),"okänt läge")}.')
        acknowledged = sum(r.get("acknowledged") is True for r in active)
        if acknowledged:
            parts.append(
                f"{acknowledged} av problemen är kvitterade, men inte bekräftat lösta."
            )
        if len(active) > 3:
            parts.append("Resten finns i Scryer-avsnittet i appen.")
    else:
        parts.append(
            "Inga öppna problem finns i det tillgängliga underlaget. Det är ingen garanti för att allt fungerar."
        )
    if unknown:
        parts.append(f"{unknown} rapporter har osäkert läge och behöver följas upp.")
    if resolved:
        parts.append(
            f"{resolved} tidigare problem har återhämtats enligt inventeringen."
        )
    if len(rows) >= 64:
        parts.append("Rapportlistan har nått sin gräns och kan vara ofullständig.")
    return " ".join(parts)
