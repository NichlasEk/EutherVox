"""Bounded Swedish volume grammar; output only registered directions/targets."""
from dataclasses import dataclass
import re


def normalize(text: str) -> str:
    return ' '.join(re.sub(r'[.,!?;:]', ' ', text.casefold()).split())


def clean_request(text: str) -> str:
    value = normalize(text)
    value = re.sub(r'^(?:kan du|skulle du kunna|vill du|snälla) ', '', value)
    value = re.sub(r'^höja\b', 'höj', value)
    value = re.sub(r'^sänka\b', 'sänk', value)
    value = re.sub(r'^dra (?:ned|ner)\b', 'dra ner', value)
    return re.sub(r' (?:tack|är du snäll|snälla)$', '', value).strip()


@dataclass(frozen=True)
class VolumeIntent:
    direction: str
    target: str | None
    unknown_target: bool = False


def target_named(text: str, targets: dict) -> str | None:
    value = clean_request(text)
    matches = [key for key, item in targets.items() if value in item['aliases']]
    return matches[0] if len(matches) == 1 else None


def parse_volume(text: str, targets: dict) -> VolumeIntent | None:
    value = clean_request(text)
    destination_pattern = r'(?: (?:på|i|vid) (.+)| (nere|uppe|där nere|där uppe|en trappa upp))?'
    # Whole utterances only: no substring matching, negation or generated actions.
    match = re.fullmatch(
        r'(höj|sänk|öka|minska|dra upp|dra ner|dra ned|skruva upp|skruva ner|skruva ned)'
        r'(?: (?:volymen|ljudet))?(?: (?:lite|ett steg))?'
        + destination_pattern, value)
    if match:
        verb, destination, room = match.groups()
        destination = destination or room
        direction = 'up' if verb in ('höj', 'öka', 'dra upp', 'skruva upp') else 'down'
    else:
        match = re.fullmatch(
            r'(?:det är|det här är|ljudet är|volymen är) (för högt|för hög|för lågt|för låg|för tyst)'
            + destination_pattern, value)
        if not match:
            return None
        level, destination, room = match.groups()
        destination = destination or room
        direction = 'down' if level in ('för högt', 'för hög') else 'up'
    if destination in ('logitech', 'logitech-systemet', 'tv', 'tvn', 'tv n'):
        destination = None
    target = target_named(destination, targets) if destination else None
    return VolumeIntent(direction, target, bool(destination and target is None))
