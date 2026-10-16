"""Renders the Ритм launcher icon in every color the user can pick (adaptive-icon backgrounds + mipmap XML)."""
import os, math
from PIL import Image, ImageDraw

RES = "/home/claude/rhythm/android/app/src/main/res"
S = 1728  # 108dp * 16, downscaled to 432 (xxxhdpi)

# name, gradient top, gradient bottom  (keep in sync with IconColors.kt and native.js)
COLORS = [
    ("cobalt", "#3346F0", "#8E3FE0"), ("ocean", "#0A8DB0", "#3346F0"), ("mint", "#16A085", "#2EE6C8"),
    ("green", "#1E9970", "#5E9400"), ("sunset", "#FF8A3D", "#D93A40"), ("amber", "#E0A000", "#E06A0A"),
    ("rose", "#E0548A", "#B42FB0"), ("red", "#D93A40", "#8E1C3A"), ("violet", "#8E3FE0", "#5B3DF5"),
    ("graphite", "#4A5270", "#15172C"),
]


def hexrgb(h):
    h = h.lstrip("#"); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def render(top, bottom):
    a, b = hexrgb(top), hexrgb(bottom)
    img = Image.new("RGB", (S, S))
    d = ImageDraw.Draw(img)
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)))
    img = img.convert("RGBA")
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
    cx = cy = S / 2
    k = 0.6  # the adaptive mask shows the middle ~66%, keep the art inside it
    r = S * 0.30 * k * 1.55
    w = int(S * 0.075 * k * 1.55)
    box = [cx - r, cy - r, cx + r, cy + r]
    d.ellipse(box, outline=(255, 255, 255, 70), width=w)
    track = layer.copy(); layer = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
    img = Image.alpha_composite(img, track)
    d.arc(box, start=-90, end=180, fill=(255, 255, 255, 255), width=w)
    for ang in (-90, 180):
        t = math.radians(ang); px, py = cx + (r - w / 2) * math.cos(t), cy + (r - w / 2) * math.sin(t)
        d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=(255, 255, 255, 255))
    q = S * 0.13 * k * 1.55
    pts = [(cx - q * 1.05, cy + q * 0.02), (cx - q * 0.28, cy + q * 0.78), (cx + q * 1.15, cy - q * 0.72)]
    lw = int(S * 0.07 * k * 1.55)
    d.line(pts, fill=(255, 255, 255, 255), width=lw, joint="curve")
    for p in (pts[0], pts[2]):
        d.ellipse([p[0] - lw / 2, p[1] - lw / 2, p[0] + lw / 2, p[1] + lw / 2], fill=(255, 255, 255, 255))
    sx, sy, sr = cx + r * 0.95, cy - r * 0.95, S * 0.035 * k * 1.55
    d.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(0xFF, 0xC2, 0x3D, 255))
    img = Image.alpha_composite(img, layer)
    return img.convert("RGB").resize((432, 432), Image.LANCZOS)


os.makedirs(f"{RES}/drawable-xxxhdpi", exist_ok=True)
os.makedirs(f"{RES}/mipmap-anydpi-v26", exist_ok=True)
for name, top, bottom in COLORS:
    render(top, bottom).save(f"{RES}/drawable-xxxhdpi/ic_bg_{name}.png", optimize=True)
    open(f"{RES}/mipmap-anydpi-v26/ic_launcher_{name}.xml", "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/ic_bg_{name}" />\n    <foreground android:drawable="@drawable/ic_empty" />\n'
        '    <monochrome android:drawable="@drawable/ic_notif" />\n</adaptive-icon>\n')
# the default icon is the cobalt one
open(f"{RES}/mipmap-anydpi-v26/ic_launcher.xml", "w").write(open(f"{RES}/mipmap-anydpi-v26/ic_launcher_cobalt.xml").read())
# a preview strip for the settings screen check
strip = Image.new("RGBA", (432 * 5, 432 * 2))
for i, (n, t, b) in enumerate(COLORS):
    strip.paste(render(t, b), ((i % 5) * 432, (i // 5) * 432))
strip.resize((1080, 432)).save("/tmp/claude-0/-home-claude/2fb7c8cb-2fa4-5718-bebe-e1d890467119/scratchpad/icons_strip.png")
print("icons:", len(COLORS))
