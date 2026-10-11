"""Deterministic voice bridge to EutherBeam's saved phone-local devices."""
import re
from uuid import uuid4
from .actions import DeviceAction
from .volume_voice import clean_request

OPERATIONS = {
    'höj volymen': 'volume_up', 'sänk volymen': 'volume_down', 'tysta': 'mute',
    'sätt på': 'power_on', 'slå på': 'power_on', 'starta': 'power_on',
    'stäng av': 'power_off', 'slå av': 'power_off',
    'pausa': 'pause', 'spela': 'play', 'fortsätt': 'play', 'hem': 'home',
    'upp': 'up', 'ner': 'down', 'vänster': 'left', 'höger': 'right',
    'okej': 'ok', 'tillbaka': 'back', 'hdmi ett': 'hdmi1', 'hdmi två': 'hdmi2',
}
ROOM_DISPLAYS = {'uppe': 'samsung', 'nere': 'nec'}
TARGETS = {'samsung': 'samsung', 'android tv': 'android_tv', 'pucken': 'android_tv', 'nec': 'nec'}
PLAYER_COMMANDS = {'play', 'pause', 'home', 'up', 'down', 'left', 'right', 'ok', 'back'}


def plan_beam(text, node_name, room=''):
    value = clean_request(text)
    value = re.sub(r'^(sätta|slå|stänga) ', lambda m: {'sätta': 'sätt', 'slå': 'slå', 'stänga': 'stäng'}[m[1]] + ' ', value)
    value = re.sub(r'\b(?:tv-apparaten|televisionen|teven|tv n|tvn)\b', 'tv', value)
    value = re.sub(r'\b(nec|samsung)[ -]tv\b', r'\1', value)
    value = re.sub(r'\bandroidpucken\b', 'pucken', value)
    for label, command in OPERATIONS.items():
        match = re.fullmatch(re.escape(label) + r'(?: (?:på )?(.+))?', value)
        if not match:
            continue
        destination = match[1] or ''
        # Only a complete allowlisted destination can override the selected room.
        explicit_room = re.fullmatch(r'(?:(?:tv:n|tv n|tvn|tv|teven|pucken|android tv) )?(?:där )?(uppe|nere)', destination)
        if explicit_room:
            display = ROOM_DISPLAYS[explicit_room[1]]
            target = 'android_tv_' + display if ('pucken' in destination or 'android tv' in destination or command in PLAYER_COMMANDS) else display
        elif destination in TARGETS:
            target = TARGETS[destination]
            if target == 'android_tv' and room in ROOM_DISPLAYS:
                target += '_' + ROOM_DISPLAYS[room]
        elif destination in ('', 'tv') and room in ROOM_DISPLAYS:
            display = ROOM_DISPLAYS[room]
            target = 'android_tv_' + display if command in PLAYER_COMMANDS else display
        elif destination in ('tv', 'pucken', 'android tv') and not room:
            return DeviceAction(str(uuid4()), 'assistant.clarify', node_name,
                {'beam_pending': label}, 'Vilken TV menar du, Uppe eller Nere? Du kan också välja TV-rum i appen.')
        else:
            return None
        if target == 'nec' and command not in ('power_on', 'power_off', 'hdmi1', 'hdmi2'):
            return None
        return DeviceAction(
            str(uuid4()), 'beam.control', node_name,
            {'provider': 'eutherbeam', 'query': command, 'uri': target},
            'Jag skickar kommandot via EutherBeam. Kontrollera TV:ns svar.',
        )
    return None
