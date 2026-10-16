"""Renders the Ритм alarm melodies to looping WAV files for the Android alarm (same notes as the web synth)."""
import numpy as np, wave, os, random

SR = 32000
OUT = "/home/claude/rhythm/android/app/src/main/res/raw"
os.makedirs(OUT, exist_ok=True)


def st(base, n):
    return base * 2 ** (n / 12)


def tone(buf, f, at, dur, vol, kind="sine", fm=0.0, fmr=3.5, glide=None, lp=None):
    n = int(dur * SR)
    t = np.arange(n) / SR
    if glide:
        k = np.minimum(t / (dur * 0.6), 1)
        freq = f * (glide ** k)
        phase = 2 * np.pi * np.cumsum(freq) / SR
    else:
        phase = 2 * np.pi * f * t
    if fm:
        mod_env = np.exp(-t * 4 / dur)
        phase = phase + fm * mod_env * np.sin(2 * np.pi * f * fmr * t)
    if kind == "sine":
        w = np.sin(phase)
    elif kind == "triangle":
        w = 2 / np.pi * np.arcsin(np.sin(phase))
    elif kind == "square":
        w = np.sign(np.sin(phase)) * 0.6
    atk = int(0.008 * SR)
    env = np.exp(-t * 5.0 / dur)
    env[:atk] *= np.linspace(0, 1, atk)
    w = w * env * vol
    if lp:  # simple one-pole low-pass for softer tones
        a = np.exp(-2 * np.pi * lp / SR)
        y = np.zeros_like(w)
        acc = 0.0
        for i in range(len(w)):
            acc = (1 - a) * w[i] + a * acc
            y[i] = acc
        w = y
    s = int(at * SR)
    e = min(len(buf), s + n)
    buf[s:e] += w[: e - s]


def reverb(x):
    out = x.copy()
    for d, g in [(0.031, 0.32), (0.047, 0.28), (0.073, 0.22), (0.113, 0.18), (0.161, 0.12)]:
        k = int(d * SR)
        out[k:] += x[:-k] * g
    return out


def render(name, length, fn):
    buf = np.zeros(int(length * SR))
    fn(buf)
    buf = reverb(buf)
    # fade the tail so the loop is seamless
    fade = int(0.08 * SR)
    buf[-fade:] *= np.linspace(1, 0, fade)
    buf /= max(1e-6, np.max(np.abs(buf))) / 0.85
    data = (buf * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, f"alarm_{name}.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())


random.seed(7)
render("bells", 2.6, lambda b: [tone(b, st(1046.5, n), a, 1.8, .25, fm=.6) for n, a in [(0, 0), (4, .2), (7, .4), (12, .6)]])
render("dawn", 3.0, lambda b: [tone(b, st(392, n), a, 1.4, .3, "triangle", lp=2600) for n, a in [(0, 0), (4, .3), (7, .6), (11, .9), (12, 1.2), (16, 1.5)]])
render("harp", 2.4, lambda b: [tone(b, st(261.6, n), i * .11, 1.6, .28, fm=.2, fmr=2, lp=3200) for i, n in enumerate([0, 4, 7, 11, 14, 19, 23])])
render("kalimba", 2.2, lambda b: [tone(b, st(523.25, n), a, .9, .3, fm=.35, fmr=5.4) for n, a in [(0, 0), (7, .2), (12, .4), (9, .6), (7, .8), (4, 1.05)]])
render("chimes", 3.0, lambda b: [tone(b, st(1318.5, [0, 3, 5, 7, 10, 12][random.randrange(6)]), i * .21 + random.random() * .08, 2.0, .18, fm=.9, fmr=2.76) for i in range(7)])
render("lullaby", 3.0, lambda b: [tone(b, st(392, n), a, 1.1, .3, lp=1800) for n, a in [(7, 0), (7, .3), (11, .6), (7, 1.05), (4, 1.35), (0, 1.65)]])
render("marimba", 1.8, lambda b: [tone(b, st(784, n), a, .4, .35, fm=1, fmr=4) for n, a in [(0, 0), (4, .15), (7, .3), (4, .45), (0, .6), (-5, .9)]])
render("birds", 2.4, lambda b: [tone(b, 2400 + random.random() * 600, a, .14, .25, glide=1.5) for a in [0, .18, .5, .68, 1.1, 1.28]])
render("classic", 1.2, lambda b: [tone(b, 880, a, .16, .2, "square") for a in [0, .25, .5, .75]])
render("arcade", 1.4, lambda b: [tone(b, st(523.25, n), i * .1, .12, .18, "square") for i, n in enumerate([0, 4, 7, 12, 16, 12, 16])])
print(sorted(os.listdir(OUT)))
