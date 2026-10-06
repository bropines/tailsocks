#!/usr/bin/env python3
"""The third-party licenses the app shows (Settings → About → Open-source licenses).

    python3 scripts/licenses/generate.py           # rewrite app/src/main/assets/licenses.json
    python3 scripts/licenses/generate.py --check   # exit 1 if the shipped components changed

What ships, read from what was built rather than from the build files:
  - Go: the module list Go records in each binary (libgojni.so in appctr/tmp/appctr.aar,
    libtailscale.so and libtailscale_cli.so in app/src/main/jniLibs), the license texts
    from the module cache — every LICENSE / NOTICE / COPYING in the module, nested ones too;
  - Android: the artifacts of :app's releaseRuntimeClasspath (a Gradle init script),
    licenses from their POMs, NOTICE / LICENSE files inside the artifacts;
  - native: the C libraries ndk-build compiles (NATIVE below), with their own files;
  - the rest that rides inside something else (EXTRA below).

The output is committed, so a build never needs the network or this script, and F-Droid's
rebuild is unaffected. Run it after build.sh and a Gradle build whenever a dependency
changes; --check (CI) compares only which components and versions ship, so it needs no
module cache.
"""
import argparse, glob, hashlib, io, json, os, re, subprocess, sys, tempfile, zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "app/src/main/assets/licenses.json")
TEXTS = os.path.join(HERE, "texts")

GO_BINARIES = [
    ("appctr/tmp/appctr.aar", "jni/arm64-v8a/libgojni.so"),
    ("app/src/main/jniLibs/arm64-v8a/libtailscale.so", None),
    ("app/src/main/jniLibs/arm64-v8a/libtailscale_cli.so", None),
]
# Modules that are this project, or are built from a tree in it.
GO_LOCAL = {
    "appctr": None,  # the bridge: TailSocks itself
    "tailscale.com": "appctr/tailscale_src",
}
NATIVE = [
    ("hev-socks5-tunnel", "https://github.com/heiher/hev-socks5-tunnel", "app/src/main/jni/hev-socks5-tunnel", ["LICENSE"]),
    ("hev-socks5-core", "https://github.com/heiher/hev-socks5-core", "app/src/main/jni/hev-socks5-tunnel/src/core", ["LICENSE"]),
    ("hev-task-system", "https://github.com/heiher/hev-task-system", "app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system", ["LICENSE"]),
    ("lwIP", "https://savannah.nongnu.org/projects/lwip/", "app/src/main/jni/hev-socks5-tunnel/third-part/lwip", ["LICENSE"]),
    ("yaml (hev)", "https://github.com/heiher/yaml", "app/src/main/jni/hev-socks5-tunnel/third-part/yaml", ["License"]),
    ("ByeDPI", "https://github.com/hufrea/byedpi", "app/src/main/jni/byedpi", ["LICENSE"]),
]
EXTRA = [
    # The Tailscale web client's bundle (github.com/tailscale/web-client-prebuilt) embeds it.
    {"section": "other", "name": "Inter typeface", "version": "", "url": "https://rsms.me/inter/",
     "license": "OFL-1.1", "texts": ["OFL-1.1-Inter.txt"], "includes": ["in the Tailscale web client (github.com/tailscale/web-client-prebuilt)"]},
]
# Maven artifacts whose files and POM carry no license text of their own.
MAVEN_TEXT = {
    "io.nayuki:qrcodegen": "MIT-qrcodegen.txt",
    # Protocol Buffers' runtime, repackaged by AndroidX for Glance.
    "androidx.glance:glance-appwidget-external-protobuf": "BSD-3-Clause-protobuf.txt",
}
# Groups whose POM names its license only through a parent POM Gradle never fetched.
MAVEN_LICENSE = {"com.google.guava": "Apache-2.0", "com.google.auto.service": "Apache-2.0"}

LICENSE_FILE = re.compile(r"^(licen[cs]e|copying|notice|unlicense)([-._][\w.-]*)?$", re.I)


# ---------------------------------------------------------------- Go build info
def _uvarint(b, i):
    x = s = 0
    while True:
        c = b[i]; i += 1
        x |= (c & 0x7F) << s
        if c < 0x80: return x, i
        s += 7

def go_buildinfo(data):
    """(go version, [(module path, version, replacement dir or None)]) from a Go binary."""
    k = data.find(b"\xff Go buildinf:")
    if k < 0: raise SystemExit("no Go build info found")
    flags = data[k + 15]
    if not flags & 2: raise SystemExit("Go build info in the pre-1.18 format")
    i = k + 32
    n, i = _uvarint(data, i); gover = data[i:i + n].decode(); i += n
    n, i = _uvarint(data, i); mod = data[i:i + n].decode(errors="replace")
    deps, last = [], None
    for line in mod.split("\n"):
        f = line.split("\t")
        if f[0] == "dep" and len(f) >= 3:
            last = [f[1], f[2], None]; deps.append(last)
        elif f[0] == "=>" and last is not None:
            last[2] = f[1]
    return gover, [tuple(d) for d in deps]

def go_modules():
    gover, mods = None, {}
    for path, member in GO_BINARIES:
        full = os.path.join(ROOT, path)
        if not os.path.exists(full):
            raise SystemExit(f"{path} is missing: run appctr/build.sh first")
        data = zipfile.ZipFile(full).read(member) if member else open(full, "rb").read()
        v, deps = go_buildinfo(data)
        gover = gover or v
        for p, ver, repl in deps: mods[p] = (ver, repl)
    return gover, mods


# ---------------------------------------------------------------- Maven
def maven_artifacts():
    """[(group, name, version, pom path, artifact path)] for :app's releaseRuntimeClasspath."""
    out = subprocess.run(
        ["./gradlew", "-q", "--no-configuration-cache", "-I", os.path.join(HERE, "shipped-artifacts.init.gradle.kts"),
         ":app:printShippedArtifacts"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0: raise SystemExit("Gradle failed:\n" + out.stderr[-3000:])
    arts = []
    for line in out.stdout.splitlines():
        if not line.startswith("ART\t"): continue
        _, coord, pom, file = (line.split("\t") + ["", "", ""])[:4]
        g, a, v = coord.split(":")[:3]
        arts.append((g, a, v, pom or None, file or None))
    return sorted(set(arts))

NS = "{http://maven.apache.org/POM/4.0.0}"

def pom_info(pom, depth=0):
    """(licenses [(name, url)], project url, organization) following parent POMs."""
    if not pom or not os.path.exists(pom): return [], "", ""
    root = ET.parse(pom).getroot()
    lic = [((l.findtext(NS + "name") or "").strip(), (l.findtext(NS + "url") or "").strip()) for l in root.iter(NS + "license")]
    url = (root.findtext(NS + "url") or "").strip()
    org = (root.findtext(NS + "organization/" + NS + "name") or "").strip()
    par = root.find(NS + "parent")
    if (not lic or not url) and par is not None and depth < 6:
        pg, pa, pv = (par.findtext(NS + x) for x in ("groupId", "artifactId", "version"))
        cands = glob.glob(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(pom)))),
                                       "..", pg, pa, pv, "*", f"{pa}-{pv}.pom"))
        cands = cands or glob.glob(os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/{pg}/{pa}/{pv}/*/{pa}-{pv}.pom"))
        if cands:
            l2, u2, o2 = pom_info(cands[0], depth + 1)
            lic, url, org = lic or l2, url or u2, org or o2
    return lic, url, org

def artifact_notices(path):
    """License and notice files packed into a jar or an aar's classes.jar."""
    if not path or not os.path.exists(path): return []
    texts = []
    def scan(z):
        for n in sorted(z.namelist()):
            if n.endswith("/") or n.endswith(".class"): continue
            if LICENSE_FILE.match(os.path.basename(n)) and ("META-INF/" in n or "/" not in n):
                t = z.read(n).decode("utf-8", "replace").strip()
                if t: texts.append(t)
    with zipfile.ZipFile(path) as z:
        if path.endswith(".aar"):
            scan(z)
            if "classes.jar" in z.namelist():
                scan(zipfile.ZipFile(io.BytesIO(z.read("classes.jar"))))
        else:
            scan(z)
    return texts


# ---------------------------------------------------------------- license names
def spdx(text):
    t = re.sub(r"\s+", " ", text)
    if "Apache License" in t and "Version 2.0" in t: return "Apache-2.0"
    if "Mozilla Public License" in t and "2.0" in t: return "MPL-2.0"
    if "SIL OPEN FONT LICENSE" in t.upper(): return "OFL-1.1"
    if "Permission is hereby granted, free of charge" in t: return "MIT"
    if "Permission to use, copy, modify, and/or distribute this software" in t or \
       "Permission to use, copy, modify, and distribute this software for any purpose with or without fee" in t: return "ISC"
    if "Redistribution and use in source and binary forms" in t:
        return "BSD-3-Clause" if re.search(r"Neither the name|The names? of (its|the) (contributors|authors?|copyright)|may not be used to endorse", t, re.I) else "BSD-2-Clause"
    if "This is free and unencumbered software released into the public domain" in t: return "Unlicense"
    return None

def pom_spdx(name, url):
    s = (name + " " + url).lower()
    if "apache" in s: return "Apache-2.0"
    if "mit" in s.split() or "/mit" in s or "mit license" in s: return "MIT"
    if "bsd" in s: return "BSD-3-Clause" if "3" in s or "new" in s else "BSD"
    return name or "?"

def squash(text):
    return re.sub(r"\s+", " ", text).strip()

def norm(text):
    return text.replace("\r\n", "\n").replace("\r", "\n").strip("\n") + "\n"


# ---------------------------------------------------------------- assemble
def module_dir(modcache, path, ver):
    esc = re.sub(r"[A-Z]", lambda m: "!" + m.group(0).lower(), path)
    return os.path.join(modcache, f"{esc}@{ver}")

def license_files(d, nested=True):
    found = []
    for root, dirs, files in os.walk(d):
        dirs[:] = sorted(x for x in dirs if x not in ("testdata", ".github", ".git", "vendor", "node_modules"))
        for f in sorted(files):
            if LICENSE_FILE.match(f) and not f.endswith((".go", ".yml", ".yaml", ".json", ".sh", ".py")):
                found.append(os.path.join(root, f))
        if not nested: break
    return found

def generate():
    texts = {}
    def add_text(t):
        t = norm(t)
        k = hashlib.sha256(t.encode()).hexdigest()[:12]
        texts[k] = t
        return k
    def read(p): return open(p, encoding="utf-8", errors="replace").read()

    sections = {"app": [], "go": [], "android": [], "native": [], "other": []}

    sections["app"].append({"name": "TailSocks", "version": "", "url": "https://github.com/bropines/tailsocks",
                            "license": "BSD-3-Clause", "texts": [add_text(read(os.path.join(ROOT, "LICENSE")))], "includes": []})

    # Go
    gover, mods = go_modules()
    modcache = subprocess.run(["go", "env", "GOMODCACHE"], capture_output=True, text=True).stdout.strip() \
        or os.path.expanduser("~/go/pkg/mod")
    goroot_lic = None
    for cand in sorted(glob.glob(os.path.join(modcache, f"golang.org/toolchain@v0.0.1-{gover}.*", "LICENSE"))) + \
                [os.path.join(subprocess.run(["go", "env", "GOROOT"], capture_output=True, text=True).stdout.strip(), "LICENSE")]:
        if os.path.exists(cand): goroot_lic = cand; break
    if not goroot_lic: raise SystemExit("no Go LICENSE found")
    sections["go"].append({"name": "Go standard library and runtime", "version": gover, "url": "https://go.dev",
                           "license": spdx(read(goroot_lic)), "texts": [add_text(read(goroot_lic))], "includes": []})
    missing = []
    for path in sorted(mods):
        ver, repl = mods[path]
        if path in GO_LOCAL:
            if GO_LOCAL[path] is None: continue
            d = os.path.join(ROOT, GO_LOCAL[path])
            files = license_files(d, nested=False)
        else:
            d = module_dir(modcache, path, ver)
            files = license_files(d) if os.path.isdir(d) else []
        if not files: missing.append(f"{path}@{ver}"); continue
        ids, kinds = [], []
        for f in files:
            t = read(f)
            ids.append(add_text(t))
            k = spdx(t)
            if k and k not in kinds: kinds.append(k)
        sections["go"].append({"name": path, "version": ver, "url": f"https://pkg.go.dev/{path}",
                               "license": ", ".join(kinds) or "see text", "texts": sorted(set(ids), key=ids.index),
                               "includes": []})
    if missing: raise SystemExit("no license file for: " + ", ".join(missing))

    # Android (Maven), one entry per group
    apache_text = read(os.path.join(TEXTS, "Apache-2.0.txt"))
    apache = add_text(apache_text)
    groups = {}
    for g, a, v, pom, file in maven_artifacts():
        lic, url, org = pom_info(pom)
        groups.setdefault(g, []).append((a, v, lic, url, org, artifact_notices(file)))
    for g in sorted(groups):
        arts = groups[g]
        kinds, ids, urls = [], [], []
        for a, v, lic, url, org, notices in arts:
            names = [pom_spdx(n, u) for n, u in lic] or [MAVEN_LICENSE.get(g, "?")]
            for k in names:
                if k not in kinds: kinds.append(k)
            if url and url not in urls: urls.append(url)
            for t in notices:
                # AndroidX packs the Apache license itself, reflowed and without the
                # appendix: one copy is enough.
                st, sa = squash(t), squash(apache_text)
                k = apache if st == sa or (sa.startswith(st) and len(st) > 0.8 * len(sa)) else add_text(t)
                if k not in ids: ids.append(k)
            override = MAVEN_TEXT.get(f"{g}:{a}")
            if override:
                k = add_text(read(os.path.join(TEXTS, override)))
                if k not in ids: ids.append(k)
        if "Apache-2.0" in kinds and apache not in ids: ids.append(apache)
        if "?" in kinds and not ids: raise SystemExit(f"no license known for Maven group {g}")
        versions = sorted({v for _, v, *_ in arts})
        sections["android"].append({
            "name": g, "version": versions[0] if len(versions) == 1 else "",
            "url": urls[0] if urls else "", "license": ", ".join(k for k in kinds if k != "?"),
            "texts": ids, "includes": [f"{a} {v}" for a, v, *_ in sorted(arts)]})

    # native
    for name, url, d, files in NATIVE:
        ids = [add_text(read(os.path.join(ROOT, d, f))) for f in files]
        sections["native"].append({"name": name, "version": "", "url": url,
                                   "license": spdx(texts[ids[0]]) or "see text", "texts": ids, "includes": []})
    for e in EXTRA:
        e = dict(e); sec = e.pop("section")
        e["texts"] = [add_text(read(os.path.join(TEXTS, f))) for f in e["texts"]]
        sections[sec].append(e)

    used = {k for s in sections.values() for c in s for k in c["texts"]}
    return {
        "format": 1,
        "sections": [{"id": k, "components": v} for k, v in sections.items() if v],
        "texts": {k: texts[k] for k in sorted(used)},
    }

def fingerprint(doc):
    return sorted(f'{s["id"]}:{c["name"]}@{c["version"]}:{",".join(c["includes"])}' for s in doc["sections"] for c in s["components"])

def shipped_fingerprint():
    """What --check compares: the components and versions in the build, no texts needed."""
    gover, mods = go_modules()
    comp = [f"app:TailSocks@:", f"go:Go standard library and runtime@{gover}:"]
    comp += [f"go:{p}@{v}:" for p, (v, _) in mods.items() if not (p in GO_LOCAL and GO_LOCAL[p] is None)]
    groups = {}
    for g, a, v, *_ in maven_artifacts(): groups.setdefault(g, []).append((a, v))
    for g, arts in groups.items():
        vs = sorted({v for _, v in arts})
        comp.append(f'android:{g}@{vs[0] if len(vs) == 1 else ""}:{",".join(f"{a} {v}" for a, v in sorted(arts))}')
    comp += [f"native:{n}@:" for n, *_ in NATIVE]
    comp += [f'{e["section"]}:{e["name"]}@{e["version"]}:{",".join(e["includes"])}' for e in EXTRA]
    return sorted(comp)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        have = fingerprint(json.load(open(OUT)))
        want = shipped_fingerprint()
        if have != want:
            print("app/src/main/assets/licenses.json is out of date — run scripts/licenses/generate.py")
            for x in sorted(set(want) - set(have)): print("  + " + x)
            for x in sorted(set(have) - set(want)): print("  - " + x)
            sys.exit(1)
        print("licenses.json matches what ships")
        return
    doc = generate()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1, sort_keys=False)
        f.write("\n")
    n = sum(len(s["components"]) for s in doc["sections"])
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {n} components, {len(doc['texts'])} distinct texts, {os.path.getsize(OUT)} bytes")

if __name__ == "__main__":
    main()
