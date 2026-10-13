"""Builds the e-ink launcher foreground (ic_launcher_eink_foreground): the color foreground's
fills in a few flat panel greys (multiples of 17, so e-ink panels draw them without dithering)
plus the monochrome layer's line art in black, for a white background. Writes every density from
the xxxhdpi sources. Needs Pillow; from the repository root:

    python3 assets/eink-icon.py app/src/main/res
"""
import sys
from PIL import Image

res_dir = sys.argv[1]
fg = Image.open(f'{res_dir}/mipmap-xxxhdpi/ic_launcher_foreground.png').convert('RGBA')
mono = Image.open(f'{res_dir}/mipmap-xxxhdpi/ic_launcher_monochrome.png').convert('RGBA')
assert fg.size == mono.size

# Luminance of the art's fills -> grey. Flat runs keep each part on one level; the slopes in
# between only touch anti-aliased edges.
CURVE = [
    (0, 0x00), (60, 0x00),       # outlines
    (80, 0x55), (115, 0x55),     # otter fur and its shading
    (128, 0x88), (165, 0x88),    # lion mane, otter shading
    (180, 0xCC), (195, 0xCC),    # lion face, otter muzzle
    (215, 0xFF), (255, 0xFF),    # highlights, hat
]


def tone(lum):
    for (x0, y0), (x1, y1) in zip(CURVE, CURVE[1:]):
        if lum <= x1:
            return y0 + (y1 - y0) * (lum - x0) / (x1 - x0)
    return 0xFF


w, h = fg.size
out = Image.new('RGBA', (w, h))
fp, mp, op = fg.load(), mono.load(), out.load()
for y in range(h):
    for x in range(w):
        r, g, b, a = fp[x, y]
        line = mp[x, y][3] / 255.0
        value = round(tone(0.299 * r + 0.587 * g + 0.114 * b) * (1 - line))
        op[x, y] = (value, value, value, max(a, mp[x, y][3]))

SIZES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
for density, size in SIZES.items():
    img = out if size == w else out.resize((size, size), Image.LANCZOS)
    img.save(f'{res_dir}/mipmap-{density}/ic_launcher_eink_foreground.png', optimize=True)
