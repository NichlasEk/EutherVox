from __future__ import annotations

import asyncio
import json

from gateway.main import generate_washer_completion_message
from gateway.washer_notifications import WasherCompletionMonitor


class Washer:
    enabled = True

    def __init__(self, states):
        self.states = iter(states)

    async def status(self):
        return {"state": next(self.states), "online": True, "available": True}


def test_local_ai_generator_uses_configured_character_and_hides_internal_course_code():
    class Characters:
        def get(self, name):
            assert name == "skinnskattaren"
            return object()

    class Llm:
        prompts = []

        async def generate(self, prompt, _character):
            self.prompts.append(prompt)
            yield "Trumman har talat. "
            yield "Tvätten är fri!"

    async def scenario():
        llm = Llm()
        text = await generate_washer_completion_message(
            llm,
            Characters(),
            {"notification_character": "skinnskattaren"},
            "fallback",
            {"state": "finished", "program": "Table_02_Course_1E"},
        )
        assert text == "Trumman har talat. Tvätten är fri!"
        assert "Table_" not in llm.prompts[0]

    asyncio.run(scenario())


def test_completion_is_armed_persisted_and_delivered_once_per_device(tmp_path):
    async def scenario():
        delivered = {"phone": [], "kitchen": []}
        monitor = WasherCompletionMonitor(
            Washer(["running", "finished"]),
            {"notifications_enabled": True, "notification_state_file": "washer.json"},
            tmp_path,
        )

        async def phone(event_id, text):
            delivered["phone"].append((event_id, text))
            return True

        async def kitchen(event_id, text):
            delivered["kitchen"].append((event_id, text))
            return True

        monitor.subscribe("phone", phone)
        monitor.subscribe("kitchen", kitchen)
        await monitor.poll_once()
        assert json.loads((tmp_path / "washer.json").read_text())["armed"] is True
        await monitor.poll_once()
        assert len(delivered["phone"]) == len(delivered["kitchen"]) == 1
        assert delivered["phone"][0] == delivered["kitchen"][0]
        assert "Tvätten är klar" in delivered["phone"][0][1]
        saved = json.loads((tmp_path / "washer.json").read_text())
        assert saved["queues"] == {"phone": [], "kitchen": []}

    asyncio.run(scenario())


def test_busy_device_keeps_its_own_persistent_queue(tmp_path):
    async def scenario():
        phone_calls = []
        kitchen_calls = []
        monitor = WasherCompletionMonitor(
            Washer(["running", "finished", "idle"]),
            {"notifications_enabled": True, "notification_state_file": "washer.json"},
            tmp_path,
        )

        async def busy_phone(event_id, text):
            phone_calls.append((event_id, text))
            return False

        async def kitchen(event_id, text):
            kitchen_calls.append((event_id, text))
            return True

        monitor.subscribe("phone", busy_phone)
        monitor.subscribe("kitchen", kitchen)
        await monitor.poll_once()
        await monitor.poll_once()
        saved = json.loads((tmp_path / "washer.json").read_text())
        assert len(phone_calls) == len(kitchen_calls) == 1
        assert len(saved["queues"]["phone"]) == 1
        assert saved["queues"]["kitchen"] == []

        restarted = WasherCompletionMonitor(
            Washer(["idle"]),
            {"notifications_enabled": True, "notification_state_file": "washer.json"},
            tmp_path,
        )
        delivered = []

        async def recovered(event_id, text):
            delivered.append((event_id, text))
            return True

        restarted.subscribe("phone", recovered)
        await restarted.poll_once()
        assert delivered == phone_calls
        assert json.loads((tmp_path / "washer.json").read_text())["queues"]["phone"] == []

    asyncio.run(scenario())


def test_completion_waits_unassigned_until_first_device_connects(tmp_path):
    async def scenario():
        monitor = WasherCompletionMonitor(
            Washer(["running", "finished", "idle"]),
            {"notifications_enabled": True, "notification_state_file": "washer.json"},
            tmp_path,
        )
        await monitor.poll_once()
        await monitor.poll_once()
        assert len(json.loads((tmp_path / "washer.json").read_text())["unassigned"]) == 1

        delivered = []

        async def receive(event_id, text):
            delivered.append((event_id, text))
            return True

        monitor.subscribe("phone", receive)
        await monitor.poll_once()
        assert len(delivered) == 1

    asyncio.run(scenario())


def test_ai_message_is_generated_once_then_queued_as_text(tmp_path):
    async def scenario():
        generated = []

        async def generate(status):
            generated.append(status["state"])
            return "  Trumman har talat. Strumporna är fria!  "

        monitor = WasherCompletionMonitor(
            Washer(["running", "finished"]),
            {"notifications_enabled": True},
            tmp_path,
            message_generator=generate,
        )
        delivered = []

        async def receive(event_id, text):
            delivered.append((event_id, text))
            return True

        monitor.subscribe("phone", receive)
        await monitor.poll_once()
        await monitor.poll_once()
        assert generated == ["finished"]
        assert delivered[0][1] == "Trumman har talat. Strumporna är fria!"

    asyncio.run(scenario())


def test_finished_on_first_start_does_not_emit_stale_notification(tmp_path):
    async def scenario():
        delivered = []
        monitor = WasherCompletionMonitor(
            Washer(["finished"]),
            {"notifications_enabled": True},
            tmp_path,
        )

        async def receive(event_id, text):
            delivered.append((event_id, text))
            return True

        monitor.subscribe("phone", receive)
        await monitor.poll_once()
        assert delivered == []

    asyncio.run(scenario())
