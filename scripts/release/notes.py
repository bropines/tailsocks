#!/usr/bin/env python3
"""The notes of a GitHub release, taken from CHANGELOG.md.

    python3 scripts/release/notes.py <tag> <release|beta> [output]

A release (v5.1.0) gets its own section, `## [5.1.0] - date`, without the heading. A beta
(v5.1.0-beta.1) has no section of its own: betas go out while the work is still under
`## [Unreleased]`, so its notes are that section as it stands at the tag, under the fixed
header of beta-notes.md. What the tag is comes from the caller — the workflow asks
buildSrc's ReleaseVersion, the one place that reads a version — and docs/RELEASING.md has
the two channels.

Run from the repository's root, as .github/workflows/android.yml does; the output is
release_notes.txt unless named.
"""
import os
import re
import sys


def section(changelog: str, version: str) -> str:
    """The text under `## [version]`, up to the next section; empty when there is none."""
    match = re.search(rf'(?s)(##\s*\[{re.escape(version)}\].*?)(?=\n##\s*\[|\Z)', changelog)
    if not match:
        return ''
    lines = match.group(1).strip().splitlines()
    return '\n'.join(lines[1:]).strip() if len(lines) > 1 else ''


def beta_header() -> str:
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'beta-notes.md'), 'r', encoding='utf-8') as f:
        return f.read().strip()


def notes(tag: str, kind: str, changelog: str) -> str:
    if kind == 'beta':
        return '\n\n'.join(part for part in (beta_header(), section(changelog, 'Unreleased')) if part)
    return section(changelog, tag.lstrip('v'))


def main(argv: list) -> int:
    if len(argv) not in (3, 4) or argv[2] not in ('release', 'beta'):
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    changelog = ''
    if os.path.exists('CHANGELOG.md'):
        with open('CHANGELOG.md', 'r', encoding='utf-8') as f:
            changelog = f.read()
    with open(argv[3] if len(argv) == 4 else 'release_notes.txt', 'w', encoding='utf-8') as out:
        out.write(notes(argv[1], argv[2], changelog))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
