#!/usr/bin/env python3
"""Bakes the world outline the exit-node map draws.

Input: Natural Earth 1:110m land polygons (public domain, naturalearthdata.com),
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson
Output: app/src/main/res/raw/world_land.bin, read by ui/WorldLand.kt.

Format, big-endian: u16 ring count; per ring u16 point count, then that many
(i16 lon * 100, i16 lat * 100). Outer rings only, simplified, specks dropped:
the map is a phone-sized backdrop for a few dots, not cartography.

    python3 scripts/bake_world_land.py ne_110m_land.geojson
"""
import json, struct, sys

TOLERANCE_DEG = 0.35   # Douglas-Peucker; below what a 360 dp wide map can show
MIN_AREA_DEG2 = 6.0    # islands smaller than this are a pixel or less
MIN_LAT = -60.0        # Antarctica only stretches the map

def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = pts[a], pts[b]
        dx, dy = bx - ax, by - ay
        norm = (dx * dx + dy * dy) ** 0.5 or 1e-12
        worst, at = 0.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * (px - ax) - dx * (py - ay)) / norm
            if d > worst:
                worst, at = d, i
        if worst > tol and at > 0:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(pts, keep) if k]

def area(pts):
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]))) / 2

def main():
    src = json.load(open(sys.argv[1]))
    rings = []
    for feature in src["features"]:
        g = feature["geometry"]
        polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        for poly in polys:
            outer = [(float(x), float(y)) for x, y in poly[0]]
            if max(y for _, y in outer) < MIN_LAT or area(outer) < MIN_AREA_DEG2:
                continue
            # A closed ring starts where it ends, which gives Douglas-Peucker no
            # baseline: cut it at the point farthest from the start and do each half.
            if outer[0] == outer[-1]:
                outer = outer[:-1]
            far = max(range(len(outer)), key=lambda i: (outer[i][0] - outer[0][0]) ** 2 + (outer[i][1] - outer[0][1]) ** 2)
            ring = simplify(outer[:far + 1], TOLERANCE_DEG)[:-1] + simplify(outer[far:] + outer[:1], TOLERANCE_DEG)[:-1]
            if len(ring) >= 4:
                rings.append(ring)
    rings.sort(key=area, reverse=True)
    out = struct.pack(">H", len(rings))
    for ring in rings:
        out += struct.pack(">H", len(ring))
        for x, y in ring:
            out += struct.pack(">hh", round(max(-180, min(180, x)) * 100), round(max(-90, min(90, y)) * 100))
    dest = "app/src/main/res/raw/world_land.bin"
    open(dest, "wb").write(out)
    print(f"{len(rings)} rings, {sum(map(len, rings))} points, {len(out)} bytes -> {dest}")

if __name__ == "__main__":
    main()
