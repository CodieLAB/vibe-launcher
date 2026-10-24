"""Draws the Play Store graphics (icon, TV banner, feature graphic) in the launcher's own style: the glass
"V" tile over a violet glow on black, in Outfit. Needs Pillow:  python3 -m pip install pillow
Run from anywhere; writes into store/graphics/."""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "app", "src", "main", "res", "font", "outfit.ttf")
OUT = os.path.join(HERE, "graphics")
SS = 3  # drawn at 3x, then scaled down, for smooth edges


def font(px, weight):
    f = ImageFont.truetype(FONT, px)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def glow(w, h, cx, cy, r, color=(78, 70, 150), strength=1.0):
    """Black with a soft violet-blue glow centred at (cx, cy)."""
    img = Image.new("RGB", (w, h), (4, 6, 12))
    layer = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=tuple(int(c * strength) for c in color))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.55))
    return Image.blend(img, layer, 0.6)


def tile(size):
    """The glass tile with its V, size x size, transparent around it."""
    t = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    radius = int(size * 0.17)
    fill = Image.new("RGBA", (size, size))
    fd = ImageDraw.Draw(fill)
    for y in range(size):   # grey-blue at the top right to violet at the bottom left
        for_y = y / size
        fd.line([(0, y), (size, y)], fill=(int(78 + 12 * for_y), int(84 - 4 * for_y), int(104 + 40 * for_y), 235))
    shine = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(shine).ellipse([-size * 0.2, size * 0.35, size * 0.7, size * 1.2], fill=(120, 96, 200, 110))
    shine = shine.filter(ImageFilter.GaussianBlur(size * 0.18))
    fill = Image.alpha_composite(fill, shine)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius, fill=255)
    t.paste(fill, (0, 0), mask)
    d = ImageDraw.Draw(t)
    d.rounded_rectangle([1, 1, size - 2, size - 2], radius, outline=(190, 198, 214, 200), width=max(2, size // 90))
    f = font(int(size * 0.62), 300)
    box = d.textbbox((0, 0), "V", font=f)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((size - w) / 2 - box[0], (size - h) / 2 - box[1]), "V", font=f, fill=(255, 255, 255, 255))
    return t


def save(img, name, w, h):
    img.resize((w, h), Image.LANCZOS).convert("RGB").save(os.path.join(OUT, name), optimize=True)
    print("wrote", name, w, h)


def icon():
    s = 512 * SS
    img = glow(s, s, int(s * 0.42), int(s * 0.6), int(s * 0.36)).convert("RGBA")
    t = tile(int(s * 0.66))
    img.alpha_composite(t, ((s - t.width) // 2, (s - t.height) // 2))
    save(img, "icon-512.png", 512, 512)


def wordmark(img, x, cy, scale):
    d = ImageDraw.Draw(img)
    big, small = font(int(118 * scale), 600), font(int(42 * scale), 400)
    d.text((x, cy - 12 * scale), "Vibe", font=big, fill=(255, 255, 255), anchor="ls")
    d.text((x + 4 * scale, cy + 62 * scale), "L A U N C H E R", font=small, fill=(206, 210, 222), anchor="ls")


def banner():
    w, h = 1280 * SS, 720 * SS
    img = glow(w, h, int(w * 0.18), int(h * 0.6), int(h * 0.42)).convert("RGBA")
    t = tile(int(h * 0.38))
    img.alpha_composite(t, (int(w * 0.08), (h - t.height) // 2))
    wordmark(img, int(w * 0.35), int(h * 0.52), SS * 1.3)
    save(img, "tv-banner-1280x720.png", 1280, 720)


def feature():
    w, h = 1024 * SS, 500 * SS
    img = glow(w, h, int(w * 0.18), int(h * 0.6), int(h * 0.5)).convert("RGBA")
    t = tile(int(h * 0.46))
    img.alpha_composite(t, (int(w * 0.07), (h - t.height) // 2 - int(h * 0.02)))
    wordmark(img, int(w * 0.33), int(h * 0.47), SS * 0.95)
    d = ImageDraw.Draw(img)
    d.text((int(w * 0.33) + 4 * SS, int(h * 0.78)), "A calm, picture-first home screen for your TV",
           font=font(int(30 * SS), 300), fill=(176, 182, 198), anchor="ls")
    save(img, "feature-graphic-1024x500.png", 1024, 500)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    icon()
    banner()
    feature()
