#!/usr/bin/env python3
"""Macht aus freien Aufnahmen (CC0) nahtlose Schleifen für die Einschlafklänge.

Eingabe: WAV-Dateien (44,1 kHz, Stereo) aus ffmpeg. Ausgabe: WAV-Schleife mit Überblendung am
Schleifenpunkt und gleicher Lautheit (RMS). Aufruf: make-sound-loops.py ein.wav aus.wav START_S LAENGE_S
"""
import sys
import wave

import numpy as np

FADE_S = 4.0          # Überblendung am Schleifenpunkt
TARGET_DBFS = -26.0   # Lautheit (RMS) der Schleife; die App regelt mit der Medienlautstärke


def read(path):
    with wave.open(path) as w:
        rate, ch = w.getframerate(), w.getnchannels()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
    return rate, data.reshape(-1, ch)


def write(path, rate, data):
    pcm = (np.clip(data, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(pcm.shape[1]); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def main():
    src, dst, start_s, length_s = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
    rate, x = read(src)
    a, n, f = int(start_s * rate), int(length_s * rate), int(FADE_S * rate)
    seg = x[a:a + n + f]
    if len(seg) < n + f:
        sys.exit(f"{src}: zu kurz für {length_s} s ab {start_s} s")
    # Gleich laute Überblendung (Kosinus), damit die Schleife ohne Delle weiterläuft.
    t = np.linspace(0, np.pi / 2, f)[:, None]
    out = seg[:n].copy()
    out[:f] = seg[:f] * np.sin(t) + seg[n:n + f] * np.cos(t)
    out -= out.mean(axis=0)
    rms = np.sqrt(np.mean(out ** 2))
    out *= 10 ** (TARGET_DBFS / 20) / max(rms, 1e-9)
    peak = np.max(np.abs(out))
    if peak > 0.5:
        out *= 0.5 / peak  # Spitzen höchstens −6 dBFS
    write(dst, rate, out)
    print(f"{dst}: {length_s:.0f} s, RMS {20*np.log10(np.sqrt(np.mean(out**2))):.1f} dBFS, Spitze {20*np.log10(np.max(np.abs(out))):.1f} dBFS")


if __name__ == "__main__":
    main()
