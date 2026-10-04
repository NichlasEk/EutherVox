import asyncio
import json
from gateway.vacuum_notifications import VacuumCompletionMonitor


class Robot:
    enabled=True
    def __init__(self):self.value={}
    async def vacuum_status(self):return self.value


def status(state, **kw):
    return dict(online=True,available=True,state=state,fault_code=0,raw_device_status=1,
                raw_charging_state=1,raw_task_status=0,cleaning_time_minutes=34,
                cleaning_area_m2=34,battery_percent=80,**kw)


def test_normal_completion_waits_for_dock_and_survives_restart(tmp_path):
    async def scenario():
        robot=Robot();settings={'notifications_enabled':True}
        monitor=VacuumCompletionMonitor(robot,settings,tmp_path)
        received=[]
        async def receive(i,text):received.append((i,text));return True
        monitor.subscribe('phone',receive)
        robot.value=status('charging');await monitor.poll_once()
        assert not received  # Existing docked status must never announce old cleaning.
        robot.value=status('cleaning');await monitor.poll_once()
        robot.value=status('returning');await monitor.poll_once()
        monitor=VacuumCompletionMonitor(robot,settings,tmp_path)
        monitor.subscribe('phone',receive)
        robot.value=status('charging');await monitor.poll_once();assert not received
        await monitor.poll_once();await monitor.poll_once()
        assert len(received)==1
        assert 'Dammsugaren är klar' in received[0][1]
        assert '34 minuter' in received[0][1] and '34 kvadratmeter' in received[0][1]
    asyncio.run(scenario())


def test_followup_delays_report_and_keeps_original_statistics(tmp_path):
    async def scenario():
        import time
        robot=Robot();monitor=VacuumCompletionMonitor(robot,{'notifications_enabled':True},tmp_path)
        coverage=dict(id='run',updated_at=time.time(),outcome='recording',cancelled=False)
        for state in ('cleaning','returning','charging','charging'):
            robot.value=status(state,coverage=coverage);await monitor.poll_once()
        assert not monitor._state['unassigned']
        coverage.update(outcome='running')
        robot.value=status('cleaning',coverage=coverage);await monitor.poll_once()
        coverage.update(outcome='succeeded')
        robot.value=status('charging',coverage=coverage);robot.value.update(cleaning_time_minutes=1,cleaning_area_m2=1)
        await monitor.poll_once()
        await monitor.poll_once()
        event=monitor._state['unassigned'][0]
        assert '34 minuter' in event['text'] and 'kompletteringspass' in event['text']
        await monitor.poll_once();assert len(monitor._state['unassigned'])==1
    asyncio.run(scenario())


def test_cancel_fault_mapping_and_offline_do_not_announce_success(tmp_path):
    async def scenario():
        import time
        for case in ('cancel','fault','mapping','offline'):
            robot=Robot();monitor=VacuumCompletionMonitor(robot,{'notifications_enabled':True,'vacuum_notification_state_file':case+'.json'},tmp_path)
            c=dict(id='run',updated_at=time.time(),outcome='recording',cancelled=False)
            robot.value=status('cleaning',coverage=c)
            if case=='mapping':robot.value['raw_device_status']=11
            await monitor.poll_once()
            robot.value=status('returning',coverage=c)
            if case=='cancel':c['cancelled']=True
            if case=='fault':robot.value['fault_code']=1
            await monitor.poll_once()
            c.update(outcome='skipped',message='Ingen kandidat')
            robot.value=status('charging',coverage=c)
            if case=='offline':robot.value['online']=False
            await monitor.poll_once();await monitor.poll_once()
            assert not monitor._state['unassigned'],case
    asyncio.run(scenario())


def test_failed_delivery_retries_with_same_event_id_after_restart(tmp_path):
    async def scenario():
        robot=Robot();settings={'notifications_enabled':True};monitor=VacuumCompletionMonitor(robot,settings,tmp_path)
        received=[]
        async def busy(i,text):received.append(i);return False
        monitor.subscribe('phone',busy)
        for state in ('cleaning','returning','charging','charging'):
            robot.value=status(state);await monitor.poll_once()
        monitor=VacuumCompletionMonitor(robot,settings,tmp_path)
        async def ready(i,text):received.append(i);return True
        monitor.subscribe('phone',ready)
        await monitor.poll_once();await monitor.poll_once()
        assert len(received)==2 and received[0]==received[1]
        assert not monitor._state['queues']['phone']
    asyncio.run(scenario())


def test_ebba_has_own_voice_jingle_and_dry_report(tmp_path):
    monitor=VacuumCompletionMonitor(Robot(),{
        'notifications_enabled':True,'notification_voice':'washer',
        'notification_jingle_file':'washer.wav'},tmp_path)
    assert monitor.voice_id=='ebba'
    assert monitor.jingle_path==tmp_path/'assets/audio/vacuum-complete.wav'
    text=monitor.report({'started_at':1,'minutes':34,'area':34.5},
                        {'outcome':'skipped','message':'Avstod: ingen tydlig, omgiven lucka'},100)
    assert text=='Dammsugaren är klar. Städtid: 34 minuter. Städad yta: 34,5 kvadratmeter. Ingen komplettering genomfördes. Ingen lämplig yta hittades. Batterinivå: 100 procent.'
