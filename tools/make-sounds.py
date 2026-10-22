#!/usr/bin/env python3
"""Synthesizes Aerial's interface sounds into app/src/main/res/raw.

Modeled on how tvOS sounds rather than copied from it: a light, woody tick when focus moves, a
fuller tock on select, a softer, lower one on Back, and a dull bump at the edge of the grid. Each
is a few decaying partials with a falling pitch and a short noise transient, so it reads as a
small physical tap rather than a beep. Short (under 0.2 s) and quiet, since they play on every
press. Standard library only:  python3 tools/make-sounds.py
"""
import math
import os
import random
import struct
import wave

RATE = 48000
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')


def render(length, partials, noise=0.0, noise_tau=0.0008, peak=0.3):
    """partials: (start Hz, end Hz, amplitude, decay tau in s). Pitch glides exponentially."""
    n = int(RATE * length)
    rnd = random.Random(7)
    out = []
    phases = [0.0] * len(partials)
    lp = 0.0
    for i in range(n):
        t = i / RATE
        # Raised-cosine attack over 1.5 ms: no click at the start.
        attack = 0.5 - 0.5 * math.cos(math.pi * min(1.0, t / 0.0015))
        # Fade the last 10 ms to silence.
        tail = min(1.0, (length - t) / 0.010)
        v = 0.0
        for k, (f0, f1, amp, tau) in enumerate(partials):
            f = f0 * (f1 / f0) ** min(1.0, t / length)
            phases[k] += 2 * math.pi * f / RATE
            v += amp * math.sin(phases[k]) * math.exp(-t / tau)
        if noise:
            # Gently low-passed noise: the "contact" of the tap.
            lp += 0.35 * (rnd.uniform(-1, 1) - lp)
            v += noise * lp * math.exp(-t / noise_tau)
        out.append(v * attack * tail)
    top = max(abs(x) for x in out) or 1.0
    return [x / top * peak for x in out]


def write(name, samples):
    path = os.path.join(OUT, name + '.wav')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s)) * 32767)) for s in samples))
    print('wrote', os.path.relpath(path))


os.makedirs(OUT, exist_ok=True)
# Focus moves: a short, bright, quiet tick.
write('ui_move', render(0.045, [(2300, 2150, 1.0, 0.005), (3500, 3300, 0.35, 0.004), (1150, 1100, 0.25, 0.008)],
                        noise=0.5, peak=0.20))
# Select: a rounder tock, a body under the tick.
write('ui_select', render(0.16, [(640, 560, 1.0, 0.030), (1280, 1150, 0.45, 0.012), (2600, 2400, 0.25, 0.004)],
                          noise=0.6, peak=0.32))
# Back: the same family, lower and softer, falling.
write('ui_back', render(0.15, [(470, 390, 1.0, 0.028), (940, 800, 0.35, 0.012), (2100, 1900, 0.15, 0.004)],
                        noise=0.45, peak=0.27))
# The edge of the grid: a dull bump with no brightness to it.
write('ui_limit', render(0.18, [(210, 150, 1.0, 0.040), (330, 260, 0.35, 0.018)],
                         noise=0.3, noise_tau=0.002, peak=0.36))
