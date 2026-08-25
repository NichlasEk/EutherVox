from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Awaitable, Callable
from uuid import uuid4

from .eutherwash import EutherWashService


LOG = logging.getLogger("euthervox.washer_notifications")
Delivery = Callable[[str, str], Awaitable[bool]]
MessageGenerator = Callable[[dict], Awaitable[str]]


class WasherCompletionMonitor:
    """Persist completion speech in an independent FIFO for every EutherVox node."""

    ACTIVE_STATES = {"running", "paused"}
    FINISHED_STATES = {"finished", "complete", "completed", "idle"}
    FALLBACK_TEXT = "Tvätten är klar! Dags att befria strumporna från sitt snurriga äventyr."
    MAX_QUEUE_DEPTH = 8
    MAX_TEXT_LENGTH = 500

    def __init__(
        self,
        service: EutherWashService,
        settings: dict,
        config_dir: Path,
        message_generator: MessageGenerator | None = None,
    ):
        self.service = service
        self.enabled = bool(settings.get("notifications_enabled", False)) and service.enabled
        self.poll_seconds = max(2.0, float(settings.get("notification_poll_seconds", 8.0)))
        self.voice_id = str(settings.get("notification_voice", "moss-nano")).strip() or "moss-nano"
        jingle = str(settings.get("notification_jingle_file", "")).strip()
        jingle_path = Path(jingle) if jingle else None
        self.jingle_path = (
            jingle_path if jingle_path is None or jingle_path.is_absolute() else config_dir / jingle_path
        )
        configured = str(settings.get("notification_state_file", "state/washer-notification.json"))
        path = Path(configured)
        self.state_path = path if path.is_absolute() else config_dir / path
        self.message_generator = message_generator
        self._callbacks: dict[str, set[Delivery]] = {}
        self._task: asyncio.Task | None = None
        self._state = self._load()

    @staticmethod
    def _node_key(node_name: str) -> str:
        normalized = " ".join(str(node_name).strip().split())
        return normalized[:128] or "unknown"

    def subscribe(self, node_name: str, callback: Delivery) -> None:
        node = self._node_key(node_name)
        self._callbacks.setdefault(node, set()).add(callback)
        devices = self._state["devices"]
        queues = self._state["queues"]
        changed = False
        if node not in devices:
            devices.append(node)
            changed = True
        if node not in queues:
            queues[node] = []
            changed = True
        unassigned = self._state["unassigned"]
        if unassigned:
            queues[node].extend(unassigned[-self.MAX_QUEUE_DEPTH :])
            self._state["unassigned"] = []
            changed = True
        if changed:
            self._save()

    def unsubscribe(self, node_name: str, callback: Delivery) -> None:
        node = self._node_key(node_name)
        callbacks = self._callbacks.get(node)
        if callbacks is None:
            return
        callbacks.discard(callback)
        if not callbacks:
            self._callbacks.pop(node, None)

    async def start(self) -> None:
        if self.enabled and self._task is None:
            self._task = asyncio.create_task(self._run(), name="washer-completion-monitor")

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None

    async def poll_once(self) -> None:
        status = await self.service.status()
        state = str(status.get("state") or "unknown").casefold()
        changed = False
        if state in self.ACTIVE_STATES and not self._state["armed"]:
            self._state["armed"] = True
            changed = True
        elif state in self.FINISHED_STATES and self._state["armed"]:
            self._state["armed"] = False
            event = {"id": str(uuid4()), "text": await self._completion_text(status)}
            devices = self._state["devices"]
            if devices:
                for node in devices:
                    queue = self._state["queues"].setdefault(node, [])
                    queue.append(event.copy())
                    del queue[:-self.MAX_QUEUE_DEPTH]
            else:
                self._state["unassigned"].append(event)
                del self._state["unassigned"][:-self.MAX_QUEUE_DEPTH]
            changed = True
        if self._state.get("last_state") != state:
            self._state["last_state"] = state
            changed = True
        if changed:
            self._save()
        await self._deliver_pending()

    async def _completion_text(self, status: dict) -> str:
        if self.message_generator is None:
            return self.FALLBACK_TEXT
        try:
            generated = " ".join((await self.message_generator(status)).strip().split())
            if generated:
                return generated[: self.MAX_TEXT_LENGTH]
        except Exception:
            LOG.exception("washer_notification_generation_failed")
        return self.FALLBACK_TEXT

    async def _deliver_pending(self) -> None:
        queues = self._state["queues"]
        for node, callbacks in tuple(self._callbacks.items()):
            queue = queues.get(node, [])
            if not queue:
                continue
            pending = queue[0]
            for callback in tuple(callbacks):
                try:
                    if await callback(str(pending["id"]), str(pending["text"])):
                        queue.pop(0)
                        self._save()
                        break
                except Exception:
                    LOG.exception("washer_notification_delivery_failed node=%s", node)

    async def _run(self) -> None:
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                LOG.warning("washer_notification_poll_failed error=%s", error)
            await asyncio.sleep(self.poll_seconds)

    @staticmethod
    def _valid_event(value: object) -> dict[str, str] | None:
        if not isinstance(value, dict):
            return None
        event_id = str(value.get("id", "")).strip()
        text = str(value.get("text", "")).strip()
        return {"id": event_id, "text": text} if event_id and text else None

    def _load(self) -> dict[str, object]:
        try:
            payload = json.loads(self.state_path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                devices = [
                    self._node_key(item)
                    for item in payload.get("devices", [])
                    if isinstance(item, str) and item.strip()
                ]
                devices = list(dict.fromkeys(devices))
                queues: dict[str, list[dict[str, str]]] = {node: [] for node in devices}
                raw_queues = payload.get("queues", {})
                if isinstance(raw_queues, dict):
                    for raw_node, raw_queue in raw_queues.items():
                        if not isinstance(raw_node, str) or not isinstance(raw_queue, list):
                            continue
                        node = self._node_key(raw_node)
                        events = [event for item in raw_queue if (event := self._valid_event(item))]
                        queues[node] = events[-self.MAX_QUEUE_DEPTH :]
                        if node not in devices:
                            devices.append(node)
                raw_unassigned = payload.get("unassigned", [])
                if not isinstance(raw_unassigned, list):
                    raw_unassigned = []
                unassigned = [
                    event for item in raw_unassigned if (event := self._valid_event(item))
                ][-self.MAX_QUEUE_DEPTH :]
                legacy = self._valid_event(payload.get("pending"))
                if legacy:
                    unassigned.append(legacy)
                return {
                    "armed": payload.get("armed") is True,
                    "last_state": str(payload.get("last_state", "unknown")),
                    "devices": devices,
                    "queues": queues,
                    "unassigned": unassigned[-self.MAX_QUEUE_DEPTH :],
                }
        except (OSError, ValueError, TypeError):
            pass
        return {
            "armed": False,
            "last_state": "unknown",
            "devices": [],
            "queues": {},
            "unassigned": [],
        }

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self._state, ensure_ascii=False), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(self.state_path)
