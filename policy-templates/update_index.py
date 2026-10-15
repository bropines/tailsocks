#!/usr/bin/env python3
"""Rewrites index.json from the template files beside it.

Keeps the order index.json already has (the catalog's order) and puts new files after it;
takes each entry's id, version and minApp from its file and computes its sha256. Run it after
editing a template: BundledTemplatesTest fails while the index and the files disagree.

    python3 policy-templates/update_index.py
"""
import hashlib
import json
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
INDEX = HERE / "index.json"


def main() -> None:
    old = json.loads(INDEX.read_text(encoding="utf-8")) if INDEX.exists() else {"templates": []}
    files = sorted(p.name for p in HERE.glob("*.json") if p.name != "index.json")
    order = [e["file"] for e in old.get("templates", []) if e["file"] in files]
    order += [f for f in files if f not in order]
    entries = []
    for name in order:
        raw = (HERE / name).read_bytes()
        t = json.loads(raw.decode("utf-8"))
        entry = {"id": t["id"], "file": name, "version": t["version"], "sha256": hashlib.sha256(raw).hexdigest()}
        if "minApp" in t:
            entry["minApp"] = t["minApp"]
        entries.append(entry)
    out = {"schema": 1, "templates": entries}
    INDEX.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"index.json: {len(entries)} templates")


if __name__ == "__main__":
    main()
