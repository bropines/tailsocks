#!/usr/bin/env python3
"""Render an adaptive-icon candidate the way launchers show it.

    python3 render.py <candidate-dir>

The dir holds foreground.svg, background.svg, monochrome.svg (viewBox 0 0 108 108).
Writes <candidate-dir>/preview.png: circle and squircle masks on dark and light
wallpapers, the Android 13+ themed (monochrome) icon in light and dark, and the
icon at 48 and 32 px. The dashed ring in the first tile is the 66 dp safe zone.
"""
import io, sys, os
import cairosvg
from PIL import Image, ImageDraw, ImageFont

BIG = 768  # render size of the full 108 dp canvas

def svg(path, size=BIG):
    return Image.open(io.BytesIO(cairosvg.svg2png(url=path, output_width=size, output_height=size))).convert("RGBA")

def tint(img, rgb):
    a = img.split()[3]
    out = Image.new("RGBA", img.size, rgb + (0,))
    out.putalpha(a)
    return out

def masked(full, shape, px):
    # launchers show the central 72 dp of the 108 dp canvas
    s = full.size[0]; c = int(s * 18 / 108)
    view = full.crop((c, c, s - c, s - c)).resize((px * 4, px * 4), Image.LANCZOS)
    m = Image.new("L", view.size, 0); d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse((0, 0, view.size[0] - 1, view.size[1] - 1), fill=255)
    else:
        n = 4.0; w = view.size[0]; r = w / 2
        pts = []
        import math
        for i in range(720):
            t = 2 * math.pi * i / 720
            ct, st = math.cos(t), math.sin(t)
            pts.append((r + r * (abs(ct) ** (2 / n)) * (1 if ct >= 0 else -1), r + r * (abs(st) ** (2 / n)) * (1 if st >= 0 else -1)))
        d.polygon(pts, fill=255)
    view.putalpha(Image.composite(view.split()[3], Image.new("L", view.size, 0), m))
    return view.resize((px, px), Image.LANCZOS)

def main(d):
    fg, bg, mono = (os.path.join(d, n + ".svg") for n in ("foreground", "background", "monochrome"))
    full = Image.alpha_composite(svg(bg), svg(fg))
    tiles = []
    # 1: circle on dark with the safe zone drawn
    t = masked(full, "circle", 192)
    guide = Image.new("RGBA", (192, 192)); g = ImageDraw.Draw(guide)
    r = 192 * 33 / 72
    g.ellipse((96 - r, 96 - r, 96 + r, 96 + r), outline=(255, 255, 0, 160), width=1)
    tiles.append(("circle + safe zone", (24, 26, 34), Image.alpha_composite(t, guide)))
    tiles.append(("squircle, light", (226, 230, 238), masked(full, "squircle", 192)))
    mono_img = svg(mono) if os.path.exists(mono) else None
    if mono_img:
        for label, bgc, fgc, wall in (("themed light", (208, 228, 255), (0, 52, 79), (240, 244, 250)),
                                      ("themed dark", (0, 52, 79), (208, 228, 255), (20, 22, 28))):
            base = Image.new("RGBA", mono_img.size, bgc + (255,))
            tiles.append((label, wall, masked(Image.alpha_composite(base, tint(mono_img, fgc)), "circle", 192)))
    small = [(masked(full, "circle", 48), (24, 26, 34)), (masked(full, "circle", 32), (24, 26, 34)),
             (masked(full, "squircle", 48), (226, 230, 238))]
    W = 16 + len(tiles) * 208 + 140; H = 248
    sheet = Image.new("RGBA", (W, H), (40, 42, 50, 255))
    dr = ImageDraw.Draw(sheet)
    try: font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
    except Exception: font = None
    x = 16
    for label, wall, img in tiles:
        dr.rectangle((x - 8, 8, x + 200, 232), fill=wall + (255,))
        sheet.alpha_composite(img, (x, 16))
        dr.text((x, 214), label, fill=(128, 128, 128), font=font)
        x += 208
    y = 16
    for img, wall in small:
        dr.rectangle((x - 8, y - 8, x + 120, y + img.size[1] + 8), fill=wall + (255,))
        sheet.alpha_composite(img, (x, y)); y += img.size[1] + 24
    dr.text((x, 214), "48 / 32 px", fill=(160, 160, 160), font=font)
    sheet.convert("RGB").save(os.path.join(d, "preview.png"))
    print("wrote", os.path.join(d, "preview.png"))

if __name__ == "__main__":
    main(sys.argv[1])
