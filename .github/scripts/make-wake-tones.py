#!/usr/bin/env python3
"""Erzeugt die sanften Wecktöne von Phase Wake (synthetisch, frei von Rechten).

Jeder Ton dauert 28 s (unter der Grenze von 30 s für Mitteilungstöne auf dem iPhone), beginnt leise und
lässt sich nahtlos wiederholen. Aufruf: make-wake-tones.py AUSGABEORDNER
"""
import os
import sys
import wave

import numpy as np

RATE = 44100
LENGTH = 28.0
rng = np.random.default_rng(7)


def env(n, attack, decay):
    t = np.arange(n) / RATE
    return np.minimum(1, t / attack) * np.exp(-t / decay)


def note(freq, dur, partials, decay, attack=0.005):
    n = int(dur * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for ratio, amp in partials:
        out += amp * np.sin(2 * np.pi * freq * ratio * t)
    return out * env(n, attack, decay)


def place(buf, sig, at):
    i = int(at * RATE)
    end = min(len(buf), i + len(sig))
    buf[i:end] += sig[:end - i]


def glockenspiel():
    buf = np.zeros(int(LENGTH * RATE))
    scale = [523.25, 587.33, 659.25, 783.99, 880.0, 1046.5]  # C-Dur pentatonisch
    motif = [0, 2, 3, 5, 4, 2]
    t = 0.5
    while t < LENGTH - 2:
        for k in motif:
            place(buf, note(scale[k], 1.6, [(1, 1), (2.76, 0.35), (5.4, 0.12)], 0.45), t)
            t += 0.42
        t += 1.4
    return buf


def gong():
    buf = np.zeros(int(LENGTH * RATE))
    for i, at in enumerate(np.arange(0.5, LENGTH - 1, 6.5)):
        f = [196.0, 220.0, 196.0, 174.61][i % 4]
        place(buf, note(f, 6.5, [(1, 1), (2.0, 0.5), (2.98, 0.35), (4.1, 0.2), (5.4, 0.1)], 2.2, attack=0.03), at)
    return buf


def harp():
    buf = np.zeros(int(LENGTH * RATE))
    freqs = [261.63, 329.63, 392.0, 523.25, 659.25, 783.99]
    t = 0.5
    while t < LENGTH - 3:
        for f in freqs:
            place(buf, pluck(f, 2.5), t)
            t += 0.22
        t += 2.2
    return buf


def pluck(freq, dur):
    # Karplus-Strong: gezupfte Saite
    n = int(dur * RATE)
    period = int(RATE / freq)
    ring = rng.uniform(-1, 1, period)
    out = np.zeros(n)
    for i in range(n):
        out[i] = ring[i % period]
        ring[i % period] = 0.996 * 0.5 * (ring[i % period] + ring[(i + 1) % period])
    return out * env(n, 0.002, 1.2)


def finish(sig):
    # Leise beginnen (8 s), ohne Gleichanteil, Spitze −3 dBFS; Ende sanft auf null (nahtlose Wiederholung).
    sig = sig - sig.mean()
    sig /= max(np.max(np.abs(sig)), 1e-9)
    n = len(sig)
    sig *= np.minimum(1, np.arange(n) / (8 * RATE)) * 0.35 + 0.65 * np.minimum(1, np.arange(n) / (8 * RATE)) ** 2
    tail = int(0.5 * RATE)
    sig[-tail:] *= np.linspace(1, 0, tail)
    return sig * 10 ** (-3 / 20)


def write(path, sig):
    stereo = np.stack([sig, sig], axis=1)
    pcm = (np.clip(stereo, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, fn in [("glockenspiel", glockenspiel), ("gong", gong), ("harp", harp)]:
        write(os.path.join(out, f"wake_{name}.wav"), finish(fn()))
        print(name, "ok")


if __name__ == "__main__":
    main()
