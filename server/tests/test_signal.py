import asyncio
import pytest
from gateway.signal import SignalService, SpeechPacer, prefetched_speech, signal_intent, speech_chunks
from gateway.session import Phase, ProtocolError
from test_session import make_session, start_message


@pytest.mark.parametrize(
    "text,expected",
    [
        ("Signal, hämta nyheter", "collect"),
        ("EutherSignal, läs rapporten", "speak"),
        ("Lämna Signal", "leave"),
        ("Läs nyhetsrapporten", "speak"),
        ("Signal rapportera inte", None),
        ("Starta dammsugaren", None),
        ("nyhetsrapport", "speak"),
        ("Vad är det senaste?", "speak"),
        ("vad är nytt", "speak"),
        ("Nyheter tack!", "speak"),
        ("Vad händer?", "speak"),
        ("Siare, säg mig vad är nytt", "speak"),
        ("Siaren! Berätta för mig vad är det senaste!", "speak"),
        ("Ooo Orakel, säg mig vad händer?", "speak"),
        ("Hej Signal, vad har hänt?", "speak"),
        ("Kan du säga mig vad är nytt?", "speak"),
        ("Ge mig en nyhetsrapport tack", "speak"),
        ("Vad finns det för nyheter?", "speak"),
        ("Scryer, rapportera", None),
        ("Vad händer med dammsugaren?", None),
        ("Vad är nytt i den andra nyheten?", None),
        ("Siaren säg mig vad är nytt med EutherVault", None),
        ("Nyheter tack men inte nu", None),
        ("Lämna inte Signal", None),
    ],
)
def test_intents(text, expected):
    assert signal_intent(text) == expected


def test_audio_pacing_bounds_lead_even_after_synthesis_stalls():
    async def run():
        now = 0.0

        async def sleep(delay):
            nonlocal now
            now += delay

        pacer = SpeechPacer(22050, clock=lambda: now, sleep=sleep)
        frame = bytes(882)  # 20 ms of mono PCM16
        for _ in range(3000):
            await pacer.wait(frame)
            assert pacer.playback_until - now <= 0.521
        assert 59.4 < now < 60.0
        now += 20  # Slow next sentence must not accumulate burst allowance.
        for _ in range(100):
            await pacer.wait(frame)
            assert pacer.playback_until - now <= 0.521

    asyncio.run(run())


def test_audio_pacing_remains_cancellable():
    async def run():
        waiting = asyncio.Event()

        async def sleep(delay):
            waiting.set()
            await asyncio.Future()

        pacer = SpeechPacer(22050, clock=lambda: 0, sleep=sleep)
        await pacer.wait(bytes(44100))
        task = asyncio.create_task(pacer.wait(bytes(882)))
        await waiting.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(run())


def test_speech_prefetch_is_one_paragraph_ahead_and_preserves_order():
    class TTS:
        sample_rate = 22050

        def __init__(self):
            self.calls = []
            self.second_ready = asyncio.Event()

        async def synthesize(self, text, *args):
            self.calls.append(text)
            yield text.encode()
            if len(self.calls) == 2:
                self.second_ready.set()

    async def run():
        tts = TTS()
        text = "a" * 500 + " " + "b" * 500 + " " + "c" * 500
        stream = prefetched_speech(tts, text, None)
        assert await anext(stream) == b"a" * 500
        await asyncio.wait_for(tts.second_ready.wait(), 1)
        assert tts.calls == ["a" * 500, "b" * 500]
        assert [frame async for frame in stream] == [b"b" * 500, b"c" * 500]

    asyncio.run(run())


def test_speech_prefetch_cancels_pending_synthesis_on_close():
    class TTS:
        sample_rate = 22050

        def __init__(self):
            self.started = asyncio.Event()
            self.cancelled = False

        async def synthesize(self, text, *args):
            if text.startswith("b"):
                self.started.set()
                try:
                    await asyncio.Future()
                finally:
                    self.cancelled = True
            yield b"audio"

    async def run():
        tts = TTS()
        stream = prefetched_speech(tts, "a" * 500 + " b", None)
        assert await anext(stream) == b"audio"
        await asyncio.wait_for(tts.started.wait(), 1)
        await stream.aclose()
        assert tts.cancelled

    asyncio.run(run())


@pytest.mark.parametrize("oversized", [True, False])
def test_speech_prefetch_propagates_errors_and_bounds_audio(oversized):
    class TTS:
        sample_rate = 10

        async def synthesize(self, *args):
            if oversized:
                yield bytes(1802)
            else:
                raise ValueError("worker failed")

    async def run():
        with pytest.raises(ValueError):
            _ = [frame async for frame in prefetched_speech(TTS(), "hello", None)]

    asyncio.run(run())


def test_owner_and_fixed_commands():
    async def run():
        s = SignalService({"enabled": True, "allowed_users": ["owner"]})
        for user, command in [("visitor", "status"), ("owner", "restart")]:
            with pytest.raises(ValueError):
                await s.request(user, command)

    asyncio.run(run())


def test_long_speech_chunks_keep_words():
    text = "Det här är en källbunden nyhetsrapport. " * 100
    chunks = list(speech_chunks(text))
    assert all(len(c) <= 500 for c in chunks)
    assert " ".join(chunks) == " ".join(text.split())


class Fake:
    def can_use(self, user):
        return user == "owner"

    async def request(self, user, command="status", identity=None, *args):
        if command == "report":
            return {"id": identity, "spoken": "Det här är den sparade rapporten."}
        if command == "ask":
            return {
                "answer": "Det framgår inte av underlaget.",
                "unknown": True,
                "source_ids": [],
            }
        return {"available": True, "report": {"id": "new"}, "job": {"state": "done"}}


def test_polls_do_not_switch_active_report():
    async def run():
        s, sent = make_session()
        s.signal = Fake()
        s.authenticated_user = "owner"
        await s.handle_text(start_message())
        s.signal_report_id = "old"
        await s._signal_request({"command": "status"})
        assert (
            sent[-1]["report"]["id"] == "old" and sent[-1]["latest_report_id"] == "new"
        )
        s.phase = Phase.PROCESSING
        with pytest.raises(ProtocolError):
            await s._signal_request({"command": "leave"})

    asyncio.run(run())


def test_signal_auth_and_audio_use_existing_pipeline():
    async def run():
        s, sent = make_session()
        s.signal = Fake()
        with pytest.raises(ProtocolError):
            await s._signal_request({})
        await s.handle_text(start_message())
        s.authenticated_user = "owner"
        await s._signal_request(
            {"command": "speak", "report_id": "old", "utterance_id": "news"}
        )
        await s.response_task
        assert s.signal_report_id == "old" and s.phase is Phase.READY
        assert any(isinstance(x, bytes) and x for x in sent)
        types = [x.get("type") for x in sent if isinstance(x, dict)]
        assert types.count("tts.start") == types.count("tts.end") == 1
        await s._signal_request({"command": "leave"})
        assert s.signal_report_id is None

    asyncio.run(run())


@pytest.mark.parametrize("utterance", ["Vad händer?", "Siare säg mig vad är nytt"])
def test_short_voice_request_reads_news_through_audio_pipeline(utterance):
    class STT:
        async def transcribe(self, *args, **kwargs):
            return utterance

    async def run():
        s, sent = make_session()
        s.signal = Fake()
        s.stt = STT()
        s.authenticated_user = "owner"
        await s.handle_text(start_message())
        await s.handle_text('{"type":"audio.start","utterance_id":"short-news"}')
        await s.handle_binary(bytes(640))
        await s.handle_text('{"type":"audio.end","utterance_id":"short-news"}')
        await s.response_task
        assert s.signal_report_id == "new"
        assert any(isinstance(x, bytes) and x for x in sent)
        assert any(
            isinstance(x, dict) and x.get("type") == "assistant.text.final"
            and x["text"] == "Det här är den sparade rapporten."
            for x in sent
        )
        assert not any(
            isinstance(x, dict) and x.get("type") == "action.request" for x in sent
        )

    asyncio.run(run())


def test_spoken_followup_stays_in_news_scope_not_device_control():
    class STT:
        async def transcribe(self, *args, **kwargs):
            return "Starta dammsugaren"

    class Research(Fake):
        questions = []

        async def request(self, user, command="status", identity=None, *args):
            if command == "ask":
                self.questions.append(args[0])
            return await super().request(user, command, identity, *args)

    async def run():
        s, sent = make_session()
        s.signal = Research()
        s.stt = STT()
        s.authenticated_user = "owner"
        await s.handle_text(start_message())
        s.signal_report_id = "old"
        await s.handle_text('{"type":"audio.start","utterance_id":"news-voice"}')
        await s.handle_binary(bytes(640))
        await s.handle_text('{"type":"audio.end","utterance_id":"news-voice"}')
        await s.response_task
        assert s.signal.questions == ["Starta dammsugaren"]
        assert not any(
            isinstance(x, dict) and x.get("type") == "action.request" for x in sent
        )
        assert any(
            isinstance(x, dict) and x.get("type") == "assistant.text.final"
            for x in sent
        )

    asyncio.run(run())


def test_speech_does_not_read_urls_and_bounds_long_tokens():
    text = "Läs [originalet](https://example.org/news). " + ("x" * 1100)
    chunks = list(speech_chunks(text))
    assert all(len(chunk) <= 500 for chunk in chunks)
    assert "originalet" in chunks[0] and "https" not in " ".join(chunks)


def test_briefing_attributes_claims_to_named_source():
    from gateway.signal import spoken_briefing

    report = {
        "introduction": "Dagens rapport.",
        "sources": [{"id": "a", "source": "Utvecklaren"}],
        "items": [
            {
                "source_id": "a",
                "headline": "Ett test",
                "summary": "En uppgift.",
                "caveat": "Inte oberoende verifierat.",
            }
        ],
    }
    text = spoken_briefing(report)
    assert "Enligt Utvecklaren" in text and "Inte oberoende verifierat" in text
