"""Draws the Ритм app icons: a cobalt-to-violet tile with a progress ring and a check mark."""
from PIL import Image, ImageDraw
import os, math

OUT = os.path.join(os.path.dirname(__file__), "dist")
os.makedirs(OUT, exist_ok=True)
S = 2048  # draw large, then downscale for smooth edges


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def tile(full_bleed: bool, content_scale: float) -> Image.Image:
    top, bottom = (0x33, 0x46, 0xF0), (0x8E, 0x3F, 0xE0)
    grad = Image.new("RGB", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        # diagonal feel: mix by y with a slight tilt
        gd.line([(0, y), (S, y)], fill=lerp(top, bottom, y / S))
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    if full_bleed:
        img.paste(grad, (0, 0))
    else:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.23), fill=255)
        img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    cx = cy = S / 2
    r = S * 0.30 * content_scale
    w = int(S * 0.075 * content_scale)
    box = [cx - r, cy - r, cx + r, cy + r]
    # faint full track
    d.ellipse(box, outline=(255, 255, 255, 70), width=w)
    # progress arc, 3/4 of the circle, starting at the top
    d.arc(box, start=-90, end=180, fill=(255, 255, 255, 255), width=w)
    # rounded caps
    for ang in (-90, 180):
        a = math.radians(ang)
        px, py = cx + (r - w / 2) * math.cos(a), cy + (r - w / 2) * math.sin(a)
        d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=(255, 255, 255, 255))
    # check mark
    k = S * 0.13 * content_scale
    pts = [(cx - k * 1.05, cy + k * 0.02), (cx - k * 0.28, cy + k * 0.78), (cx + k * 1.15, cy - k * 0.72)]
    lw = int(S * 0.07 * content_scale)
    d.line(pts, fill=(255, 255, 255, 255), width=lw, joint="curve")
    for p in (pts[0], pts[2]):
        d.ellipse([p[0] - lw / 2, p[1] - lw / 2, p[0] + lw / 2, p[1] + lw / 2], fill=(255, 255, 255, 255))
    # small accent spark
    sx, sy, sr = cx + r * 0.95, cy - r * 0.95, S * 0.035 * content_scale
    d.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(0xFF, 0xC2, 0x3D, 255))
    return img


def save(img, size, name):
    img.resize((size, size), Image.LANCZOS).save(os.path.join(OUT, name), optimize=True)


rounded = tile(False, 1.0)
save(rounded, 192, "icon-192.png")
save(rounded, 512, "icon-512.png")
save(rounded, 32, "favicon-32.png")
maskable = tile(True, 0.78)  # keep the art inside the 80% safe zone
save(maskable, 512, "maskable-512.png")
save(maskable, 192, "maskable-192.png")
apple = tile(True, 0.95)  # iOS rounds the corners itself
save(apple, 180, "apple-touch-icon.png")
print("icons ok")
