#!/usr/bin/env python3
"""Build the README gallery, the hero banner, the social preview and the store
screenshots from the ReadmeShots previews.

    ./gradlew :app:updateDebugScreenshotTest
    python3 scripts/readme_shots.py [--font path/to/Inter.ttf]

Everything comes from app/src/screenshotTest/.../ReadmeShots.kt, which renders
the real screens from an invented tailnet — no device, nobody's real network.
Needs Pillow. Inter (a variable TTF) makes the social preview's text nicer;
without it DejaVu is used.
"""
import argparse
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REF = "app/src/screenshotTestDebug/reference/io/github/bropines/tailscaled/ui/ReadmeShotsKt"
OUT = "docs/screenshots"
LANGS = {"en": "en-US", "ru": "ru"}  # README language -> fastlane locale
NAMES = {
    "Main": "main", "MainProblem": "main-problem", "Peers": "peers", "Netcheck": "netcheck",
    "SettingsWide": "settings-wide", "Tablet": "tablet", "Fold": "foldable",
}
TAGLINE = {
    "en": "Unofficial Tailscale client for Android",
    "ru": "Неофициальный клиент Tailscale для Android",
}
BG = (4, 7, 6)
GLOW = (22, 120, 88)


def shot(name, lang):
    found = glob.glob(f"{REF}/Readme{name}_{lang}_*.png")
    if not found:
        sys.exit(f"missing render Readme{name}_{lang}; run ./gradlew :app:updateDebugScreenshotTest first")
    return Image.open(found[0]).convert("RGB")


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def device(im, width, bezel=10):
    """A screen at `width`, in a thin dark bezel with the corners of a phone."""
    h = round(im.height * width / im.width)
    screen = im.resize((width, h), Image.LANCZOS)
    r = max(12, int(min(width, h) * 0.09))
    frame = rounded(Image.new("RGB", (width + 2 * bezel, h + 2 * bezel), (34, 40, 38)), r + bezel)
    frame.alpha_composite(rounded(screen, r), (bezel, bezel))
    return frame


def with_shadow(layer, blur=36, offset=(0, 28), alpha=170):
    pad = blur * 3
    canvas = Image.new("RGBA", (layer.width + 2 * pad, layer.height + 2 * pad), (0, 0, 0, 0))
    shade = Image.new("RGBA", layer.size, (0, 0, 0, alpha))
    shade.putalpha(Image.eval(layer.getchannel("A"), lambda a: a * alpha // 255))
    canvas.alpha_composite(shade, (pad + offset[0], pad + offset[1]))
    canvas = canvas.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer, (pad, pad))
    return canvas, pad


def backdrop(w, h):
    """Near-black with a soft emerald glow — the app's own AMOLED look."""
    base = Image.new("RGB", (w, h), BG)
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    ImageDraw.Draw(glow).ellipse([w * 0.18, -h * 0.35, w * 0.82, h * 0.75], fill=GLOW)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) // 4))
    return Image.blend(base, glow, 0.35).convert("RGBA")


def place(canvas, layer, cx, top):
    shadowed, pad = with_shadow(layer)
    canvas.alpha_composite(shadowed, (int(cx - shadowed.width / 2), int(top - pad)))


def hero(lang):
    w, h = 2400, 1180
    c = backdrop(w, h)
    side = [device(shot("Peers", lang), 470), device(shot("Netcheck", lang), 470)]
    front = device(shot("Main", lang), 540)
    place(c, side[0], w * 0.30, 170)
    place(c, side[1], w * 0.70, 170)
    place(c, front, w * 0.50, 70)
    return c.convert("RGB")


def font(path, size, weight):
    try:
        f = ImageFont.truetype(path, size)
        try:
            f.set_variation_by_name(weight)
        except Exception:
            pass
        return f
    except Exception:
        bold = "Bold" in weight or "Black" in weight
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans" + ("-Bold" if bold else "") + ".ttf", size)


def logo(size):
    """docs/logo.svg when cairosvg is available, else the launcher bitmap."""
    try:
        import io
        import cairosvg
        png = cairosvg.svg2png(url="docs/logo.svg", output_width=size * 2, output_height=size * 2)
        return Image.open(io.BytesIO(png)).convert("RGBA").resize((size, size), Image.LANCZOS)
    except Exception:
        path = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp"
        return Image.open(path).convert("RGBA").resize((size, size), Image.LANCZOS) if os.path.exists(path) else None


def social(font_path):
    """1280x640, what GitHub shows when the repository link is shared."""
    w, h = 1280, 640
    c = backdrop(w, h)
    d = ImageDraw.Draw(c)
    icon = logo(112)
    if icon is not None:
        c.alpha_composite(icon, (84, 150))
    d.text((84, 290), "TailSocks", font=font(font_path, 88, b"Bold"), fill=(236, 247, 242))
    d.text((88, 400), "Unofficial Tailscale client", font=font(font_path, 34, b"Medium"), fill=(160, 214, 192))
    d.text((88, 446), "for Android", font=font(font_path, 34, b"Medium"), fill=(160, 214, 192))
    d.text((88, 520), "proxy · TUN · root · exit nodes · Taildrop", font=font(font_path, 24, b"Regular"), fill=(120, 150, 140))
    for img, cx, top in ((shot("Peers", "en"), 920, 120), (shot("Main", "en"), 1110, 70)):
        place(c, device(img, 270, 6), cx, top)
    return c.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default="", help="variable Inter TTF for the social preview")
    args = ap.parse_args()

    for lang, store in LANGS.items():
        out = f"{OUT}/{lang}"
        os.makedirs(out, exist_ok=True)
        for name, file in NAMES.items():
            im = shot(name, lang)
            width = 1080 if im.width > im.height else 540
            im.resize((width, round(im.height * width / im.width)), Image.LANCZOS).save(f"{out}/{file}.webp", quality=90, method=6)
        hero(lang).save(f"{out}/hero.webp", quality=90, method=6)

        # F-Droid / store listing: PNG, phones first.
        phone_dir = f"fastlane/metadata/android/{store}/images/phoneScreenshots"
        tablet_dir = f"fastlane/metadata/android/{store}/images/tenInchScreenshots"
        os.makedirs(phone_dir, exist_ok=True)
        os.makedirs(tablet_dir, exist_ok=True)
        for i, name in enumerate(["Main", "Peers", "Netcheck", "MainProblem", "SettingsWide"], 1):
            shot(name, lang).save(f"{phone_dir}/{i}.png", optimize=True)
        for i, name in enumerate(["Tablet", "Fold"], 1):
            shot(name, lang).save(f"{tablet_dir}/{i}.png", optimize=True)

    social(args.font).save("docs/social-preview.png", optimize=True)
    print("gallery, hero banners, store screenshots and docs/social-preview.png written")


if __name__ == "__main__":
    main()
