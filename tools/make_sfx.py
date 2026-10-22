"""
Synthesizes the game's placeholder sound effects into assets/sfx/.
No samples, no licenses: everything is noise, sine waves and envelopes.
Run from the project root:  python3 tools/make_sfx.py
Needs numpy.
"""
import os, wave
import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "sfx")
rng = np.random.default_rng(1943)

def t(sec): return np.arange(int(RATE * sec)) / RATE
def noise(sec): return rng.uniform(-1, 1, int(RATE * sec))
def env(sec, attack, decay):
    x = t(sec)
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return a * np.exp(-x / decay)

def lowpass(x, cutoff):
    """One-pole lowpass. cutoff can be a number or an array (for sweeps)."""
    cutoff = np.broadcast_to(np.asarray(cutoff, dtype=float), x.shape)
    a = 1 - np.exp(-2 * np.pi * cutoff / RATE)
    y = np.empty_like(x); acc = 0.0
    for i in range(len(x)):
        acc += a[i] * (x[i] - acc); y[i] = acc
    return y

def highpass(x, cutoff): return x - lowpass(x, cutoff)

def sweep(f0, f1, sec):
    f = np.linspace(f0, f1, int(RATE * sec))
    return np.sin(2 * np.pi * np.cumsum(f) / RATE)

def pad(x, sec):
    n = int(RATE * sec)
    return np.pad(x, (0, max(0, n - len(x))))[:n] if len(x) < n else x

def mix(*parts):
    n = max(len(p) for p in parts)
    return sum(np.pad(p, (0, n - len(p))) for p in parts)

def save(name, x, gain=0.9):
    x = np.tanh(x * 1.2)                 # soft clip, adds grit
    x = x / (np.max(np.abs(x)) + 1e-9) * gain
    fade = min(len(x), int(RATE * 0.01))
    x[-fade:] *= np.linspace(1, 0, fade)
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((x * 32767).astype(np.int16).tobytes())
    print(f"{name:16s} {len(x) / RATE:.2f}s")

def gunshot(dist):
    """dist 0 = right next to you, 1 = far away (duller, more echo)."""
    crack = highpass(noise(0.05), 1500 - dist * 1000) * env(0.05, 0.0005, 0.008 + dist * 0.01)
    body = lowpass(noise(0.25), 1800 - dist * 1200) * env(0.25, 0.001, 0.04 + dist * 0.03)
    thump = np.sin(2 * np.pi * 110 * t(0.12)) * env(0.12, 0.001, 0.03)
    tail = lowpass(noise(0.6), 500) * env(0.6, 0.02, 0.15) * (0.25 + dist * 0.3)
    return mix(crack * (1.2 - dist), body, thump * 0.8, tail)

def boom(sec, low):
    rumble = lowpass(noise(sec), 220) * env(sec, 0.004, sec / 4)
    drop = sweep(low * 2, low * 0.6, sec) * env(sec, 0.002, sec / 5)
    crack = lowpass(noise(0.15), 3000) * env(0.15, 0.0005, 0.02)
    return mix(rumble * 2.0, drop * 1.0, crack * 0.8)

save("rifle.wav", gunshot(0.0))
save("enemy_rifle.wav", gunshot(0.6), 0.8)

save("cannon.wav", mix(boom(1.3, 70), gunshot(0.2) * 0.6))
save("blast.wav", mix(boom(1.6, 55), lowpass(noise(1.6), 900) * env(1.6, 0.01, 0.35) * 0.6))

# Bullet into a body: short dull thud.
save("hit.wav", mix(lowpass(noise(0.09), 900) * env(0.09, 0.001, 0.018),
                    np.sin(2 * np.pi * 180 * t(0.09)) * env(0.09, 0.001, 0.02) * 0.7), 0.7)

# Bullet off armor: inharmonic metal ring plus a click.
x = t(0.35)
ring = sum(np.sin(2 * np.pi * f * x) * np.exp(-x / d) for f, d in [(820, .09), (1290, .06), (1930, .05), (2710, .03)])
save("clank.wav", mix(ring * 0.5, highpass(noise(0.02), 2000) * env(0.02, 0.0005, 0.004)), 0.6)

# Dodge: a whoosh of filtered noise sweeping up then down.
sec = 0.28
cut = 300 + 2200 * np.sin(np.pi * t(sec) / sec)
save("dodge.wav", lowpass(noise(sec), cut) * np.sin(np.pi * t(sec) / sec) ** 1.5, 0.55)

# You got hit: low punch and a short buzz.
save("hurt.wav", mix(lowpass(noise(0.2), 400) * env(0.2, 0.001, 0.05) * 1.5,
                     np.sign(np.sin(2 * np.pi * 90 * t(0.15))) * env(0.15, 0.002, 0.04) * 0.4), 0.8)

# Tank winding up to fire: turret motor whine and a breech clack at the end.
sec = 0.8
whine = sweep(180, 520, sec) * (0.6 + 0.4 * np.sin(2 * np.pi * 28 * t(sec))) * np.clip(t(sec) / 0.1, 0, 1) * 0.35
clack = np.zeros(int(RATE * sec))
c = highpass(noise(0.04), 1200) * env(0.04, 0.0005, 0.006)
clack[-len(c) - 200:-200] = c
save("tank_aim.wav", mix(whine, clack), 0.5)

# Someone got past you: a two-note bugle-ish warning.
a = (np.sin(2 * np.pi * 392 * t(0.18)) + 0.3 * np.sin(2 * np.pi * 784 * t(0.18))) * env(0.18, 0.01, 0.12)
b = (np.sin(2 * np.pi * 294 * t(0.3)) + 0.3 * np.sin(2 * np.pi * 588 * t(0.3))) * env(0.3, 0.01, 0.2)
save("breakthrough.wav", np.concatenate([a, b]), 0.5)
