#!/usr/bin/env python3
"""Generates the CarTalk launcher icons: a white car with orange sound waves on dark.

Usage: python3 tools/make_icon.py   (needs Pillow; run from the repo root)
"""
from PIL import Image, ImageDraw

S = 1024
DARK, WHITE, ORANGE = (18, 22, 29, 255), (242, 244, 247, 255), (255, 148, 71, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def glyph(scale=1.0):
    """Car and waves on a transparent 1024 canvas, drawn in a 100-unit grid."""
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def u(v):
        return (v - 50) * scale * S / 100 + S / 2

    def pts(p):
        return [(u(x), u(y)) for x, y in p]

    d.polygon(pts([(26, 47), (34, 33), (36, 30.6), (39, 30), (55, 30), (58, 30.6), (60, 33), (68, 47)]), fill=WHITE)
    d.rounded_rectangle([u(12), u(46), u(74), u(63)], radius=7 * scale * S / 100, fill=WHITE)
    for cx in (28, 58):
        d.ellipse([u(cx - 9.5), u(64 - 9.5), u(cx + 9.5), u(64 + 9.5)], fill=WHITE)
        d.ellipse([u(cx - 5.5), u(64 - 5.5), u(cx + 5.5), u(64 + 5.5)], fill=DARK)

    def wave(x, y0, bulge, height):
        w = 4.5 * scale * S / 100
        # Stamp circles along the curve: smoother than a thick polyline.
        for i in range(401):
            t = i / 400
            cx = u(x + 2 * (1 - t) * t * bulge)
            cy = u(y0 + t * height)
            d.ellipse([cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2], fill=ORANGE)

    wave(80, 45, 7, 19)
    wave(87, 38, 12, 33)
    return im


def square():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.23), fill=DARK)
    im.alpha_composite(glyph(1.0))
    return im


def round_icon():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse([0, 0, S - 1, S - 1], fill=DARK)
    im.alpha_composite(glyph(0.8))
    return im


if __name__ == "__main__":
    sq, rd = square(), round_icon()
    for name, px in SIZES.items():
        base = "app/src/main/res/mipmap-%s/" % name
        sq.resize((px, px), Image.LANCZOS).save(base + "ic_launcher.png")
        rd.resize((px, px), Image.LANCZOS).save(base + "ic_launcher_round.png")
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    fg.alpha_composite(glyph(0.62))
    fg.resize((432, 432), Image.LANCZOS).save("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
    sq.resize((512, 512), Image.LANCZOS).save("/tmp/cartalk-icon.png")
    print("icons written")
