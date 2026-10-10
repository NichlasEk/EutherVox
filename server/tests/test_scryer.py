import asyncio
import pytest
from gateway.scryer import ScryerService
from test_session import make_session, start_message


def test_access_denied_before_transport():
    async def run():
        service = ScryerService({"enabled": True, "allowed_users": ["owner"]})
        with pytest.raises(ValueError):
            await service.request("visitor")
        with pytest.raises(ValueError):
            await service.request("owner", "restart")
        with pytest.raises(ValueError):
            await service.request("owner", "report-ack", None)

    asyncio.run(run())


def test_session_requires_authenticated_allowed_owner():
    class Fake:
        calls = 0

        def can_use(self, user):
            return user == "owner"

        async def request(self, *args):
            self.calls += 1
            return {"available": True, "reports": []}

    async def run():
        session, sent = make_session()
        session.scryer = Fake()
        await session._scryer_reports({})
        assert sent[-1]["available"] is False and session.scryer.calls == 0
        await session.handle_text(start_message())
        await session._scryer_reports({})
        assert sent[-1]["available"] is False and session.scryer.calls == 0
        session.authenticated_user = "owner"
        await session._scryer_reports({})
        assert sent[-1]["available"] is True and session.scryer.calls == 1

    asyncio.run(run())


from gateway.scryer import wants_report, spoken_report


@pytest.mark.parametrize(
    "text",
    [
        "Scryer.. rapportera.",
        "Siaren! Säg mig läget.",
        "ooo Orakel ge mig din sanning",
        "O du vise siare, berätta läget",
        "Scryer, några problem?",
        "Oraklet, vad har du sett?",
        "Scryer status",
        "Scryer, ge mig en lägesrapport",
        "Siaren är allt lugnt?",
        "Orakel, hur står det till?",
    ],
)
def test_friendly_report_aliases(text):
    assert wants_report(text)


@pytest.mark.parametrize(
    "text",
    [
        "Vad betyder orakel?",
        "Scryer, rapportera inte",
        "Sluta rapportera, siaren",
        "Vad är vädret?",
        "Berätta om oraklet i Delphi",
    ],
)
def test_not_every_oracle_mention_is_a_command(text):
    assert not wants_report(text)


def test_spoken_report_unknown_is_never_all_clear():
    assert "inte säga" in spoken_report({"available": False})
    text = spoken_report(
        {
            "available": True,
            "inventory_at": "2020-01-01T00:00:00Z",
            "reports": [{"id": "vault", "state": "unconfirmed"}],
        }
    )
    assert "senast kända" in text and "osäkert" in text


def test_spoken_report_uses_existing_audio_pipeline():
    class Fake:
        def can_use(self, user):
            return user == "owner"

        async def request(self, user):
            return {
                "available": True,
                "inventory_at": "2026-10-10T10:00:00Z",
                "reports": [
                    {
                        "id": "vault",
                        "state": "open",
                        "status": "failed",
                        "acknowledged": True,
                    }
                ],
            }

    async def run():
        session, sent = make_session()
        session.scryer = Fake()
        session.authenticated_user = "owner"
        await session.handle_text(start_message())
        await session.handle_text('{"type":"scryer.speak","utterance_id":"report-1"}')
        task = session.response_task
        await task
        assert any(isinstance(x, bytes) and x for x in sent)
        text = next(
            x["text"]
            for x in sent
            if isinstance(x, dict) and x.get("type") == "assistant.text.final"
        )
        assert "vault" in text and "inte bekräftat lösta" in text
        assert [
            x["type"]
            for x in sent
            if isinstance(x, dict) and x["type"].startswith("tts.")
        ] == ["tts.start", "tts.end"]

    asyncio.run(run())


@pytest.mark.parametrize(
    "phrase",
    ["Scryer.. rapportera.", "Siaren! Säg mig läget.", "ooo Orakel ge mig din sanning"],
)
def test_transcribed_alias_routes_to_report_without_model(phrase):
    import json
    from gateway.session import Phase

    class Stt:
        async def transcribe(self, pcm, sample_rate):
            return phrase

    class NoModel:
        def __getattr__(self, name):
            raise AssertionError("Reports must not invoke a model")

    class Reports:
        def can_use(self, user):
            return user == "owner"

        async def request(self, user):
            return {
                "available": True,
                "inventory_at": "2026-10-10T07:00:00Z",
                "reports": [],
            }

    async def run():
        session, sent = make_session()
        session.stt, session.llm, session.scryer = Stt(), NoModel(), Reports()
        session.authenticated_user = "owner"
        await session.handle_text(start_message())
        await session.handle_text(
            json.dumps({"type": "audio.start", "utterance_id": "alias"})
        )
        await session.handle_binary(bytes(640))
        await session.handle_text(
            json.dumps({"type": "audio.end", "utterance_id": "alias"})
        )
        await session.response_task
        assert any(
            isinstance(x, dict)
            and x.get("type") == "assistant.text.final"
            and "Scryers lägesrapport" in x["text"]
            for x in sent
        )
        assert any(isinstance(x, bytes) and x for x in sent)
        assert session.phase is Phase.READY

    asyncio.run(run())


def test_scryer_speech_cancellation_keeps_utterance_id():
    import json
    from gateway.session import Phase

    class SlowReports:
        def can_use(self, user):
            return True

        async def request(self, user):
            await asyncio.sleep(30)

    async def run():
        session, sent = make_session()
        session.scryer = SlowReports()
        session.authenticated_user = "owner"
        await session.handle_text(start_message())
        await session.handle_text(
            json.dumps({"type": "scryer.speak", "utterance_id": "cancel-report"})
        )
        await asyncio.sleep(0)
        await session.handle_text(
            json.dumps({"type": "response.cancel", "utterance_id": "cancel-report"})
        )
        assert sent[-1] == {
            "type": "response.cancelled",
            "utterance_id": "cancel-report",
        }
        assert session.phase is Phase.READY
        assert not any(isinstance(x, bytes) for x in sent)

    asyncio.run(run())
