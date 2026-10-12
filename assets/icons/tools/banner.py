#!/usr/bin/env python3
"""Build the Android TV launcher banner from the main icon's layers.

    python3 banner.py --font <Inter variable .ttf> [--out <res dir>]

A TV home screen shows an app as a 16:9 banner (320x180 dp) with its name in the
picture, not as the launcher icon. This one is the fox sock exactly as the icon
draws it (../layers/sock_and_tail.svg, untouched) on the icon's own background
and mesh, with the name set beside it in the brand's two colours.

Writes drawable-xhdpi/tv_banner.webp (320x180, what a 1080p TV draws) and
drawable-xxxhdpi/tv_banner.webp (640x360, a 4K interface), lossless. The font is
not in the repository: any variable build of Inter (SIL OFL) will do.
"""
import argparse, io, os
import cairosvg
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
LAYERS = os.path.join(HERE, "..", "layers")
BACKGROUND = (0x0E, 0x0F, 0x19)
FLAME = (0xFF, 0xB1, 0x72)   # "Tail": the tail's lighter flame
PURPLE = (0xB5, 0x94, 0xFC)  # "Socks": the sock
SS = 4                       # supersampling, for clean edges at 320 px


def layer(name, size):
    png = cairosvg.svg2png(url=os.path.join(LAYERS, name), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def banner(width, height, font_path):
    w, h = width * SS, height * SS
    img = Image.new("RGBA", (w, h), BACKGROUND + (255,))

    # The sock and tail fill the height, less a margin a launcher's rounded
    # corners and focus zoom may eat; the mesh comes with them at the same scale,
    # as on the icon, and a mirrored copy carries it on to the right edge.
    probe = layer("sock_and_tail.svg", 1024)
    l, t, r, b = probe.getbbox()
    side = int(h * 0.84 * 1024 / (b - t))
    scale = side / 1024
    left = int(w * 0.055 - l * scale)
    top = int((h - (b - t) * scale) / 2 - t * scale)
    mesh = layer("grid.svg", side)
    img.alpha_composite(mesh, (left, top))
    img.alpha_composite(mesh.transpose(Image.FLIP_LEFT_RIGHT), (left + side, top))
    art_right = left + int(r * scale)
    # Quieter behind the name, so the letters are what is read there.
    fade = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(fade)
    start, end = art_right - int(w * 0.06), art_right + int(w * 0.10)
    for x in range(start, w):
        d.line([(x, 0), (x, h)], fill=int(200 * min(1.0, (x - start) / (end - start))))
    img.paste(Image.new("RGBA", (w, h), BACKGROUND + (255,)), (0, 0), fade)
    img.alpha_composite(layer("sock_and_tail.svg", side), (left, top))

    # The name in two lines, as large as the room beside the artwork allows.
    x = art_right + int(w * 0.035)
    room = w - int(w * 0.06) - x
    size = int(h * 0.30)
    while True:
        font = ImageFont.truetype(font_path, size)
        font.set_variation_by_axes([14, 800])  # optical size, weight
        if font.getlength("Socks") <= room:
            break
        size -= 2
    draw = ImageDraw.Draw(img)
    gap = int(size * 1.08)
    y = h // 2 - int(size * 0.14)
    draw.text((x, y), "Tail", font=font, fill=FLAME, anchor="ls")
    draw.text((x, y + gap), "Socks", font=font, fill=PURPLE, anchor="ls")
    return img.resize((width, height), Image.LANCZOS).convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True)
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "..", "app", "src", "main", "res"))
    args = ap.parse_args()
    for density, (w, h) in {"xhdpi": (320, 180), "xxxhdpi": (640, 360)}.items():
        d = os.path.join(args.out, "drawable-" + density)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "tv_banner.webp")
        banner(w, h, args.font).save(path, lossless=True, quality=100, method=6)
        print(os.path.normpath(path))


if __name__ == "__main__":
    main()
