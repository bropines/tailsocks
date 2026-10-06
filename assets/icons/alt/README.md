# Alternative launcher icons

The icons a user can pick in Settings → Appearance → App icon (`core/AppIcons.kt`). Each
directory holds the three adaptive-icon layers as SVG (108×108 viewBox, the brief's subset
in `../tools/BRIEF.md`) and the designer's notes. The app ships them converted:

    python3 ../tools/vd.py to-vd <id>/foreground.svg app/src/main/res/drawable/ic_launcher_<id>_foreground.xml

(likewise `_background`, `_monochrome`), plus `mipmap-anydpi-v26/ic_launcher_<id>.xml` and a
192 px `mipmap-xxxhdpi/ic_launcher_<id>.webp` for Android 7. `python3 ../tools/render.py <id>`
previews one the way launchers mask it. The main icon (fox sock) is `../layers` and
`docs/logo.svg`, not here.
