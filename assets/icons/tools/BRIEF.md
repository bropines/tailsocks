# Alternative launcher icons for TailSocks — the brief

TailSocks is an Android client for Tailscale (a mesh VPN) that runs the daemon as a
SOCKS5/HTTP proxy or a TUN VPN, with extras: TailCat (NAT-traversing tunnels, mascot
idea: a cat paw), Taildrop (file sharing), Serve/Funnel (hosting). The name is a pun:
Tail + SOCKS (socks the garment / SOCKS the proxy protocol).

The main icon stays as it is and is NOT to be redrawn: a purple sock whose cuff has
three stripes, with a red-orange fox tail flaming out of it, on a near-black background
covered with a thin purple node-and-edge mesh. Look at it: `reference-fox-sock.webp`
in this directory. The app is adding an icon picker; you are drawing ADDITIONAL icons
users can switch to. They should feel like siblings of the main one (same world, same
quality bar), not copies of it.

Brand palette (use it unless your direction says otherwise):
  background  #0E0F19   mesh lines  #4D4671
  purples     #9E78F0  #B594FC  #C7B0F8
  fox red     #BC4343   flame  #FD610A → #FFB172   cream  #FBDCC7

## Deliverables

For each candidate, a directory `<this dir>/<your-direction>/<candidate-name>/` with:
- `foreground.svg`, `background.svg`, `monochrome.svg` — each `viewBox="0 0 108 108"`,
  width/height 108;
- `preview.png` — made by `python3 <this dir>/render.py <candidate-dir>` (do not edit
  render.py; it shows circle/squircle masks, themed icons and 48/32 px sizes);
- `notes.md` — 2–4 lines: the idea, and anything the converter must know.

## Hard rules (adaptive icon + VectorDrawable)

- Canvas 108×108. Launchers show only the central 72×72 (18..90) and mask it to a circle,
  squircle or rounded square. Everything that matters stays inside the **66-unit safe
  circle** (centre 54,54, radius 33) — the dashed ring in the first preview tile.
- background.svg is opaque and covers the full 108×108. foreground.svg is transparent
  outside the artwork.
- monochrome.svg: the Android 13+ themed icon. One colour only (#FFFFFF), the system
  tints it; shapes, not a photo of the colour icon — usually the foreground silhouette
  simplified, details cut out as holes (fill-rule evenodd) rather than drawn in another
  colour. Same safe zone.
- Must read at 48 px and still be recognisable at 32 px: no detail thinner than ~2.5
  units, no more than ~3 main shapes competing.
- SVG subset only, so it converts to Android VectorDrawable: `<path>` (d, fill,
  fill-opacity, fill-rule, stroke, stroke-width, stroke-linecap, stroke-linejoin,
  stroke-opacity), `<circle>`, `<ellipse>`, `<rect>`, `<g transform>` with translate /
  scale / rotate only, `<linearGradient>` / `<radialGradient>` with
  gradientUnits="userSpaceOnUse" and no gradientTransform. NOT allowed: filter, blur,
  shadow filters, mask, clipPath, pattern, text, image, use, CSS/style attributes,
  opacity on groups, stroke-dasharray.
- No resemblance to any real company's logo or another app's icon (no Tailscale logo,
  no system-app look-alikes).

## Process

Draw, render the preview, LOOK at preview.png (Read it), fix, repeat — at least three
rounds per candidate. Judge it at 48 px next to the reference: is it instantly readable,
balanced in the circle, not cramped against the safe ring, not empty? Delete failed
candidates rather than handing them in. Hand in exactly the number of candidates your
direction asks for.

Final answer: for each candidate its directory, one line on the idea, and your honest
verdict on how good it is at 48 px.
