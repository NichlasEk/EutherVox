from __future__ import annotations

import asyncio
import json

from gateway.washer_notifications import WasherCompletionMonitor


class Washer:
    enabled = True

    def __init__(self, states):
        self.states = iter(states)

    async def status(self):
        return {"state": next(self.states), "online": True, "available": True}


def test_completion_is_armed_persisted_and_delivered_once(tmp_path):
    async def scenario():
        delivered = []
        monitor = WasherCompletionMonitor(
            Washer(["running", "finished"]),
            {"notifications_enabled": True, "notification_state_file": "washer.json"},
            tmp_path,
        )

        async def receive(event_id, text):
            delivered.append((event_id, text))
            return True

        monitor.subscribe(receive)
        await monitor.poll_once()
        assert json.loads((tmp_path / "washer.json").read_text())["armed"] is True
        await monitor.poll_once()
        assert len(delivered) == 1
        assert "Tvätten är klar" in delivered[0][1]
        assert json.loads((tmp_path / "washer.json").read_text())["pending"] is None

    asyncio.run(scenario())


def test_finished_on_first_start_does_not_emit_stale_notification(tmp_path):
    async def scenario():
        delivered = []
        monitor = WasherCompletionMonitor(
            Washer(["finished"]),
            {"notifications_enabled": True},
            tmp_path,
        )
        monitor.subscribe(lambda event_id, text: delivered.append((event_id, text)))
        await monitor.poll_once()
        assert delivered == []

    asyncio.run(scenario())
