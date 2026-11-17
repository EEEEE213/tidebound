"""Crops the preset renders to one shared frame -> GUI previews (textures/gui/preset) and README cards."""
from PIL import Image, ImageDraw
import glob, os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs", "images", "presets")
GUI = os.path.join(ROOT, "src", "main", "resources", "assets", "tidebound", "textures", "gui", "preset")
CARDS = os.path.join(SRC, "readme")
os.makedirs(GUI, exist_ok=True)
os.makedirs(CARDS, exist_ok=True)
fs = sorted(glob.glob(os.path.join(SRC, "[0-9][0-9]_*.png")))


def frame(files):
    """One crop for a set of renders: their union bounding box, padded, grown to 4:3."""
    box = None
    for f in files:
        b = Image.open(f).getchannel("A").point(lambda a: 255 if a > 40 else 0).getbbox()
        if b:
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    x0, y0, x1, y1 = box[0] - 16, box[1] - 16, box[2] + 16, box[3] + 16
    w, h = x1 - x0, y1 - y0
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w, h = (h * 4 / 3, h) if w / h < 4 / 3 else (w, w * 3 / 4)
    return tuple(int(round(v)) for v in (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))


galleon = [f for f in fs if f.endswith("_galleon.png")]
others = [f for f in fs if f not in galleon]
crops = {f: frame(others) for f in others}
crops.update({f: frame(galleon) for f in galleon})
for f in fs:
    sid = os.path.basename(f)[3:]
    im = Image.open(f).convert("RGBA").crop(crops[f])
    im.resize((320, 240), Image.LANCZOS).save(os.path.join(GUI, sid), optimize=True)
    bg = Image.new("RGBA", im.size)
    g = ImageDraw.Draw(bg)
    H = im.size[1]
    for y in range(H):
        g.line([(0, y), (im.size[0], y)], fill=(int(58 + 53 * y / H), int(82 + 56 * y / H), int(114 + 52 * y / H), 255))
    Image.alpha_composite(bg, im).convert("RGB").resize((360, 270), Image.LANCZOS).save(os.path.join(CARDS, sid))
for extra in ("lower_deck", "section"):
    src = os.path.join(SRC, extra + ".png")
    if os.path.exists(src):
        im = Image.open(src).convert("RGBA")
        bg = Image.new("RGBA", im.size, (58, 82, 114, 255))
        bg.alpha_composite(im)
        bg.convert("RGB").resize((1200, 675), Image.LANCZOS).save(os.path.join(CARDS, extra + ".jpg"), quality=90)
Image.open(os.path.join(SRC, "hero.png")).convert("RGB").resize((1200, 675), Image.LANCZOS).save(os.path.join(CARDS, "hero.jpg"), quality=90)
print(len(fs), "stages")
