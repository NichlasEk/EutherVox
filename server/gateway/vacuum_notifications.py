"""Persisted Ebba completion announcements over the existing phone audio path."""
from __future__ import annotations

import json
import time
from uuid import uuid4

from .washer_notifications import WasherCompletionMonitor


class VacuumCompletionMonitor(WasherCompletionMonitor):
    def __init__(self, service, settings, config_dir):
        settings = dict(settings)
        settings['notification_state_file'] = settings.get(
            'vacuum_notification_state_file', 'state/vacuum-notification.json')
        settings['notifications_enabled'] = settings.get(
            'vacuum_notifications_enabled', settings.get('notifications_enabled', False))
        settings['notification_voice'] = settings.get('vacuum_notification_voice', 'ebba')
        settings['notification_jingle_file'] = settings.get(
            'vacuum_notification_jingle_file', 'assets/audio/vacuum-complete.wav')
        super().__init__(service, settings, config_dir)

    def _load(self):
        state = super()._load()
        state['run'] = None
        try:
            run = json.loads(self.state_path.read_text()).get('run')
            if isinstance(run, dict) and isinstance(run.get('started_at'), (int, float)):
                state['run'] = run
        except (OSError, ValueError, TypeError):
            pass
        return state

    @staticmethod
    def report(run, coverage, battery):
        parts = ['Dammsugaren är klar.']
        minutes = run.get('minutes')
        area = run.get('area')
        if isinstance(minutes, (int, float)) and isinstance(area, (int, float)):
            area_text = f'{area:g}'.replace('.', ',')
            parts.append(f'Städtid: {minutes:g} minuter. Städad yta: {area_text} kvadratmeter.')
        outcome = coverage.get('outcome') if coverage else None
        if outcome == 'succeeded':
            parts.append('Ett kompletteringspass genomfördes. Roboten är i dockan.')
        elif outcome == 'failed':
            parts.append('Kompletteringen misslyckades eller avbröts. Se rapporten i appen.')
        elif outcome == 'skipped':
            message = coverage.get('message', '')
            if 'ingen tydlig' in message or 'ingen säker kandidat' in message:
                parts.append('Ingen komplettering genomfördes. Ingen lämplig yta hittades.')
            elif 'positionsdata' in message or 'Positions' in message or 'körvägen' in message:
                parts.append('Ingen komplettering genomfördes. Positionsunderlaget var otillräckligt.')
            elif 'Batteriet' in message:
                parts.append('Ingen komplettering genomfördes. Batterinivån var för låg.')
            else:
                parts.append('Ingen komplettering genomfördes. Se orsaken i appen.')
        else:
            parts.append('Roboten är i dockan. Ingen automatisk komplettering registrerades.')
        if isinstance(battery, (int, float)):
            parts.append(f'Batterinivå: {battery:g} procent.')
        return ' '.join(parts)[:700]

    async def poll_once(self):
        status = await self.service.vacuum_status()
        now = time.time()
        run = self._state.get('run')
        coverage = status.get('coverage') or {}
        state = status.get('state')
        if run and now - run['started_at'] > 6 * 3600:
            run = None
        if status.get('online') and status.get('available', True):
            # Map building and autonomous supplements are not new ordinary runs.
            if state == 'cleaning' and status.get('raw_device_status') != 11 and coverage.get('outcome') != 'running':
                if run is None:
                    run = dict(started_at=now, returned=False, dock_reads=0)
                if coverage.get('updated_at', 0) >= run['started_at'] - 30:
                    run['coverage_id'] = coverage.get('id')
                run['dock_reads'] = 0
            current = coverage if coverage.get('id') == (run or {}).get('coverage_id') else {}
            if run:
                if current.get('cancelled') or status.get('fault_code') not in (0, 68):
                    run = None
                elif state == 'returning':
                    run['returned'] = True
                elif state == 'charging' and status.get('raw_task_status') == 0 and status.get('raw_charging_state') == 1:
                    run['dock_reads'] = run.get('dock_reads', 0) + 1
                    # Preserve the original pass statistics across a short supplement.
                    if 'minutes' not in run:
                        run['minutes'] = current.get('cleaning_time_minutes') or status.get('cleaning_time_minutes')
                        run['area'] = current.get('cleaning_area_m2') or status.get('cleaning_area_m2')
                    pending = current.get('outcome') in ('prepared', 'recording', 'running')
                    if (run.get('returned') and run['dock_reads'] >= 2 and not pending
                            and (run.get('minutes') or 0) >= 1 and (run.get('area') or 0) > 0):
                        event = dict(id=str(uuid4()), text=self.report(run, current, status.get('battery_percent')))
                        self._enqueue(event)
                        run = None
                else:
                    run['dock_reads'] = 0
        self._state['run'] = run
        self._save()
        await self._deliver_pending()
