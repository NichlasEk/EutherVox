"""Deterministic voice bridge to EutherBeam's saved phone-local devices."""
import re
from uuid import uuid4
from .actions import DeviceAction

OPERATIONS={'höj volymen':'volume_up','sänk volymen':'volume_down','tysta':'mute','sätt på':'power_on','slå på':'power_on','stäng av':'power_off','pausa':'pause','spela':'play','hem':'home','upp':'up','ner':'down','vänster':'left','höger':'right','okej':'ok','tillbaka':'back','hdmi ett':'hdmi1','hdmi två':'hdmi2'}
TARGETS={'samsung':'samsung','android tv':'android_tv','pucken':'android_tv','nec':'nec'}
def plan_beam(text,node_name):
    value=' '.join(text.casefold().strip(' .!?').split())
    for label,command in OPERATIONS.items():
        for name,target in TARGETS.items():
            if value not in (label+' '+name,label+' på '+name):continue
            if target=='nec' and command not in ('power_on','power_off','hdmi1','hdmi2'):return None
            return DeviceAction(str(uuid4()),'beam.control',node_name,{'provider':'eutherbeam','query':command,'uri':target},'Jag skickar kommandot via EutherBeam. Kontrollera TV:ns svar.')
    return None
