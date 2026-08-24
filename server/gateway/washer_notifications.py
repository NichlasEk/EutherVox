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


class WasherCompletionMonitor:
    """Persist one cycle edge and deliver it to one authenticated live client."""

    ACTIVE_STATES = {"running", "paused"}
    FINISHED_STATES = {"finished", "complete", "completed"}

    def __init__(self, service: EutherWashService, settings: dict, config_dir: Path):
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
        self._callbacks: set[Delivery] = set()
        self._task: asyncio.Task | None = None
        self._state = self._load()

    def subscribe(self, callback: Delivery) -> None:
        self._callbacks.add(callback)

    def unsubscribe(self, callback: Delivery) -> None:
        self._callbacks.discard(callback)

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
            self._state["pending"] = {
                "id": str(uuid4()),
                "text": "Tvätten är klar! Dags att befria strumporna från sitt snurriga äventyr.",
            }
            changed = True
        if self._state.get("last_state") != state:
            self._state["last_state"] = state
            changed = True
        if changed:
            self._save()
        await self._deliver_pending()

    async def _deliver_pending(self) -> None:
        pending = self._state.get("pending")
        if not isinstance(pending, dict):
            return
        for callback in tuple(self._callbacks):
            try:
                if await callback(str(pending["id"]), str(pending["text"])):
                    self._state["pending"] = None
                    self._save()
                    return
            except Exception:
                LOG.exception("washer_notification_delivery_failed")

    async def _run(self) -> None:
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                LOG.warning("washer_notification_poll_failed error=%s", error)
            await asyncio.sleep(self.poll_seconds)

    def _load(self) -> dict[str, object]:
        try:
            payload = json.loads(self.state_path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                return {
                    "armed": payload.get("armed") is True,
                    "last_state": str(payload.get("last_state", "unknown")),
                    "pending": payload.get("pending") if isinstance(payload.get("pending"), dict) else None,
                }
        except (OSError, ValueError, TypeError):
            pass
        return {"armed": False, "last_state": "unknown", "pending": None}

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self._state, ensure_ascii=False), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(self.state_path)
