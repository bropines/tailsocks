Changelogs for app stores (F-Droid, IzzyOnDroid) live in this directory.

One file per release, named after the release's versionCode, literally and
without padding: 1497.txt for v4.4.4. Plain text, at most 500 characters.
Stores ignore any other file name here, this README included.

versionCode today is the commit count plus 502 (app/build.gradle.kts):

    echo $(( $(git rev-list --count HEAD) + 502 ))

The file has to be in the tagged commit, and adding it is itself a commit.
If the commit that adds the changelog is the one you tag, its versionCode is
the count *before* that commit plus 503:

    echo $(( $(git rev-list --count HEAD) + 503 ))   # run before committing

Check it after tagging: $(( $(git rev-list --count vX.Y.Z) + 502 )) must
equal the file name. If the versionCode scheme changes (docs/DISTRIBUTION.md
recommends one derived from the version number), update this note.

Write it as a two- or three-line digest of the CHANGELOG.md section, one
file per locale (en-US is the fallback for every other language).
