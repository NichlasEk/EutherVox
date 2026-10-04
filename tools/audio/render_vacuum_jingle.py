#!/usr/bin/env python3
"""Short original vacuum spin-down and three-note completion cue. No speech."""
import math
from pathlib import Path
import struct
import wave

rate = 22050
samples = []
phase = 0.0
for i in range(int(rate * 1.8)):
    t = i / rate
    value = 0.0
    if t < .5:
        phase += 2 * math.pi * (210 - 140 * t / .5) / rate
        envelope = min(1, t / .03) * (1 - t / .5) ** 2
        value += .11 * envelope * (math.sin(phase) + .2 * math.sin(2*phase))
    for onset, hz in ((.4, 523.25), (.64, 659.25), (.88, 783.99)):
        age = t - onset
        if 0 <= age < .8:
            envelope = min(1, age / .015) * math.exp(-6 * age) * min(1, (.8-age)/.08)
            value += .15 * envelope * (math.sin(2*math.pi*hz*age) + .15*math.sin(4*math.pi*hz*age))
    samples.append(struct.pack('<h', round(max(-1, min(1, value))*32767)))
target = Path(__file__).resolve().parents[2] / 'assets/audio/vacuum-complete.wav'
with wave.open(str(target), 'wb') as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(b''.join(samples))
print(target)
