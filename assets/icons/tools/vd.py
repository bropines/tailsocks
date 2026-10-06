#!/usr/bin/env python3
"""SVG (the brief's subset) <-> Android VectorDrawable.

    python3 vd.py to-vd   in.svg  out.xml
    python3 vd.py to-svg  in.xml  out.svg
"""
import re, sys, math
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
NAMED = {"white": "#FFFFFF", "black": "#000000", "none": None, "transparent": None}

def num(v, d=0.0):
    if v is None: return d
    return float(re.sub(r"(px|dp)$", "", str(v).strip()))

def fmt(x):
    s = ("%.3f" % x).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def color(c):
    """-> (#RRGGBB, alpha) or None"""
    if c is None: return None
    c = c.strip()
    c = NAMED.get(c.lower(), c)
    if c is None: return None
    if re.fullmatch(r"#[0-9a-fA-F]{3}", c): c = "#" + "".join(ch * 2 for ch in c[1:])
    if re.fullmatch(r"#[0-9a-fA-F]{6}", c): return c.upper(), 1.0
    if re.fullmatch(r"#[0-9a-fA-F]{8}", c): return "#" + c[3:].upper(), int(c[1:3], 16) / 255  # VD #AARRGGBB
    m = re.fullmatch(r"rgba?\(([^)]*)\)", c)
    if m:
        p = [x.strip() for x in m.group(1).split(",")]
        r, g, b = (int(float(x)) for x in p[:3]); a = float(p[3]) if len(p) > 3 else 1.0
        return "#%02X%02X%02X" % (r, g, b), a
    raise ValueError("colour " + c)

def argb(rgb, a):
    return "#%02X%s" % (round(max(0, min(1, a)) * 255), rgb[1:])

# ---------------------------------------------------------------- SVG -> VD
def shape_path(el):
    t = el.tag.split("}")[-1]
    if t == "path": return el.get("d")
    if t == "circle":
        cx, cy, r = num(el.get("cx")), num(el.get("cy")), num(el.get("r"))
        return f"M{fmt(cx-r)},{fmt(cy)}a{fmt(r)},{fmt(r)} 0,1 0,{fmt(2*r)},0a{fmt(r)},{fmt(r)} 0,1 0,{fmt(-2*r)},0Z"
    if t == "ellipse":
        cx, cy, rx, ry = (num(el.get(k)) for k in ("cx", "cy", "rx", "ry"))
        return f"M{fmt(cx-rx)},{fmt(cy)}a{fmt(rx)},{fmt(ry)} 0,1 0,{fmt(2*rx)},0a{fmt(rx)},{fmt(ry)} 0,1 0,{fmt(-2*rx)},0Z"
    if t == "rect":
        x, y, w, h = (num(el.get(k)) for k in ("x", "y", "width", "height"))
        rx = el.get("rx"); ry = el.get("ry")
        rx = num(rx if rx is not None else ry); ry = num(ry if ry is not None else rx)
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx == 0 and ry == 0:
            return f"M{fmt(x)},{fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}Z"
        return (f"M{fmt(x+rx)},{fmt(y)}h{fmt(w-2*rx)}a{fmt(rx)},{fmt(ry)} 0,0 1,{fmt(rx)},{fmt(ry)}v{fmt(h-2*ry)}"
                f"a{fmt(rx)},{fmt(ry)} 0,0 1,{fmt(-rx)},{fmt(ry)}h{fmt(-(w-2*rx))}a{fmt(rx)},{fmt(ry)} 0,0 1,{fmt(-rx)},{fmt(-ry)}"
                f"v{fmt(-(h-2*ry))}a{fmt(rx)},{fmt(ry)} 0,0 1,{fmt(rx)},{fmt(-ry)}Z")
    if t == "polygon" or t == "polyline":
        pts = re.findall(r"-?[\d.]+(?:e-?\d+)?", el.get("points"))
        pairs = [f"{pts[i]},{pts[i+1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(pairs) + ("Z" if t == "polygon" else "")
    if t == "line":
        return f"M{el.get('x1')},{el.get('y1')}L{el.get('x2')},{el.get('y2')}"
    return None

def parse_transform(t):
    """-> list of VD group attr dicts, outermost first"""
    out = []
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", t or ""):
        a = [float(x) for x in re.split(r"[\s,]+", args.strip()) if x]
        if name == "translate": out.append({"translateX": a[0], "translateY": a[1] if len(a) > 1 else 0})
        elif name == "scale": out.append({"scaleX": a[0], "scaleY": a[1] if len(a) > 1 else a[0]})
        elif name == "rotate":
            g = {"rotation": a[0]}
            if len(a) == 3: g.update(pivotX=a[1], pivotY=a[2])
            out.append(g)
        elif name == "matrix":
            a_, b, c, d, e, f = a
            if abs(b) < 1e-9 and abs(c) < 1e-9:
                out.append({"translateX": e, "translateY": f}); out.append({"scaleX": a_, "scaleY": d})
            else: raise ValueError("matrix with skew/rotation: " + t)
        else: raise ValueError("transform " + name)
    return out

INHERIT = ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "opacity")

def svg_to_vd(src, dst):
    root = ET.parse(src).getroot()
    vb = [float(x) for x in re.split(r"[\s,]+", root.get("viewBox", "0 0 108 108").strip())]
    grads = {}
    for g in root.iter():
        tag = g.tag.split("}")[-1]
        if tag in ("linearGradient", "radialGradient"):
            if g.get("gradientTransform"): raise ValueError("gradientTransform")
            stops = []
            for st in g:
                if st.tag.split("}")[-1] != "stop": continue
                off = st.get("offset", "0"); off = float(off[:-1]) / 100 if off.endswith("%") else float(off)
                rgb, a = color(st.get("stop-color", "#000000")); a *= float(st.get("stop-opacity", "1"))
                stops.append((off, argb(rgb, a)))
            href = g.get("{http://www.w3.org/1999/xlink}href") or g.get("href")
            grads[g.get("id")] = {"type": "linear" if tag == "linearGradient" else "radial", "el": g, "stops": stops, "href": href}
    for gid, gd in grads.items():
        if not gd["stops"] and gd["href"]: gd["stops"] = grads[gd["href"].lstrip("#")]["stops"]
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="108dp"', '    android:height="108dp"',
             f'    android:viewportWidth="{fmt(vb[2])}"', f'    android:viewportHeight="{fmt(vb[3])}">']
    def paint(prefix, val, opacity, ind):
        """attr lines + aapt child lines for fill/stroke"""
        if val is None or val == "none": return [], []
        m = re.match(r"url\(#([^)]+)\)", val)
        if m:
            gd = grads[m.group(1)]; e = gd["el"]
            if gd["type"] == "linear":
                ga = (f'android:type="linear" android:startX="{fmt(num(e.get("x1")))}" android:startY="{fmt(num(e.get("y1")))}" '
                      f'android:endX="{fmt(num(e.get("x2")))}" android:endY="{fmt(num(e.get("y2")))}"')
            else:
                ga = (f'android:type="radial" android:centerX="{fmt(num(e.get("cx")))}" android:centerY="{fmt(num(e.get("cy")))}" '
                      f'android:gradientRadius="{fmt(num(e.get("r")))}"')
            if e.get("gradientUnits") != "userSpaceOnUse": raise ValueError("gradient not userSpaceOnUse: " + m.group(1))
            child = [f'{ind}    <aapt:attr name="android:{prefix}Color">', f'{ind}        <gradient {ga}>']
            for off, c in gd["stops"]:
                child.append(f'{ind}            <item android:offset="{fmt(off)}" android:color="{c}" />')
            child += [f'{ind}        </gradient>', f'{ind}    </aapt:attr>']
            attrs = [f'android:{prefix}Alpha="{fmt(opacity)}"'] if opacity < 1 else []
            return attrs, child
        c = color(val)
        if c is None: return [], []
        rgb, a = c
        return [f'android:{prefix}Color="{argb(rgb, a * opacity)}"'], []
    def walk(el, style, ind, depth=0):
        tag = el.tag.split("}")[-1]
        if tag in ("defs", "linearGradient", "radialGradient", "title", "desc", "metadata"): return
        st = dict(style)
        for k in INHERIT:
            if el.get(k) is not None: st[k] = el.get(k)
        if el.get("style"): raise ValueError("style attribute")
        groups = parse_transform(el.get("transform"))
        for g in groups:
            lines.append(ind + "<group " + " ".join(f'android:{k}="{fmt(v)}"' for k, v in g.items()) + ">"); ind += "    "
        if tag in ("svg", "g"):
            if tag == "g" and st.get("opacity") not in (None, "1") and el.get("opacity") is not None:
                # spread group opacity onto children (exact only when they do not overlap)
                pass
            for ch in el: walk(ch, st, ind, depth + 1)
        else:
            d = shape_path(el)
            if d is None: raise ValueError("element " + tag)
            op = float(st.get("opacity", "1"))
            fa, fc = paint("fill", st.get("fill", "#000000"), op * float(st.get("fill-opacity", "1")), ind)
            sa, sc = paint("stroke", st.get("stroke"), op * float(st.get("stroke-opacity", "1")), ind)
            pd = re.sub(r"\s+", " ", d).strip()
            attrs = [f'android:pathData="{pd}"'] + fa + sa
            if st.get("stroke") not in (None, "none"):
                attrs.append(f'android:strokeWidth="{fmt(num(st.get("stroke-width", "1")))}"')
                cap = st.get("stroke-linecap"); join = st.get("stroke-linejoin")
                if cap: attrs.append(f'android:strokeLineCap="{cap}"')
                if join: attrs.append(f'android:strokeLineJoin="{join}"')
            if st.get("fill-rule") == "evenodd": attrs.append('android:fillType="evenOdd"')
            if not fa and not fc and not sa and not sc: pass
            lines.append(ind + "<path")
            for a in attrs: lines.append(ind + "    " + a)
            if fc or sc:
                lines[-1] += ">"
                lines.extend(fc + sc); lines.append(ind + "</path>")
            else:
                lines[-1] += " />"
        for g in groups:
            ind = ind[:-4]; lines.append(ind + "</group>")
    walk(root, {}, "    ")
    lines.append("</vector>")
    open(dst, "w").write("\n".join(lines) + "\n")

# ---------------------------------------------------------------- VD -> SVG
def vd_to_svg(src, dst):
    tree = ET.parse(src); root = tree.getroot()
    w, h = root.get(A + "viewportWidth"), root.get(A + "viewportHeight")
    defs = []; body = []; n = [0]
    def col(c):
        if c is None: return None, 1
        if c.startswith("@"): raise ValueError("resource colour " + c)
        return color(c)
    def walk(el, out):
        tag = el.tag
        if tag == "group":
            tx, ty = num(el.get(A + "translateX")), num(el.get(A + "translateY"))
            sx, sy = num(el.get(A + "scaleX"), 1), num(el.get(A + "scaleY"), 1)
            r = num(el.get(A + "rotation")); px, py = num(el.get(A + "pivotX")), num(el.get(A + "pivotY"))
            t = f"translate({tx} {ty}) translate({px} {py}) rotate({r}) scale({sx} {sy}) translate({-px} {-py})"
            out.append(f'<g transform="{t}">')
            for ch in el: walk(ch, out)
            out.append("</g>")
        elif tag == "path":
            attrs = {"d": el.get(A + "pathData")}
            for prefix in ("fill", "stroke"):
                c = el.get(A + prefix + "Color")
                alpha = num(el.get(A + prefix + "Alpha"), 1)
                grad = None
                for ch in el:
                    if ch.tag == "{http://schemas.android.com/aapt}attr" and ch.get("name") == f"android:{prefix}Color":
                        grad = ch.find("gradient")
                if grad is not None:
                    n[0] += 1; gid = f"g{n[0]}"
                    items = [(num(i.get(A + "offset")), i.get(A + "color")) for i in grad.findall("item")]
                    if not items:
                        items = [(0, grad.get(A + "startColor"))] + ([(0.5, grad.get(A + "centerColor"))] if grad.get(A + "centerColor") else []) + [(1, grad.get(A + "endColor"))]
                    stops = "".join(f'<stop offset="{o}" stop-color="{color(c)[0]}" stop-opacity="{color(c)[1]}"/>' for o, c in items)
                    if grad.get(A + "type", "linear") == "linear":
                        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{grad.get(A+"startX")}" y1="{grad.get(A+"startY")}" x2="{grad.get(A+"endX")}" y2="{grad.get(A+"endY")}">{stops}</linearGradient>')
                    else:
                        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{grad.get(A+"centerX")}" cy="{grad.get(A+"centerY")}" r="{grad.get(A+"gradientRadius")}">{stops}</radialGradient>')
                    attrs[prefix] = f"url(#{gid})"; attrs[prefix + "-opacity"] = alpha
                elif c:
                    rgb, a = col(c); attrs[prefix] = rgb; attrs[prefix + "-opacity"] = a * alpha
                else:
                    attrs[prefix] = "none"
            if el.get(A + "strokeWidth"): attrs["stroke-width"] = el.get(A + "strokeWidth")
            if el.get(A + "strokeLineCap"): attrs["stroke-linecap"] = el.get(A + "strokeLineCap")
            if el.get(A + "strokeLineJoin"): attrs["stroke-linejoin"] = el.get(A + "strokeLineJoin")
            if el.get(A + "fillType") == "evenOdd": attrs["fill-rule"] = "evenodd"
            out.append("<path " + " ".join(f'{k}="{v}"' for k, v in attrs.items()) + "/>")
        elif tag == "clip-path":
            raise ValueError("clip-path")
    for ch in root: walk(ch, body)
    open(dst, "w").write(f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 {w} {h}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>')

if __name__ == "__main__":
    {"to-vd": svg_to_vd, "to-svg": vd_to_svg}[sys.argv[1]](sys.argv[2], sys.argv[3])
