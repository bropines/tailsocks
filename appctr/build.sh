#!/bin/bash
set -e

# Check NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ Error: ANDROID_NDK_HOME is not set! Please export it."
    exit 1
fi

# Determine Tailscale version from TAILSCALE_VERSION file, env var, or CLI parameter
if [ -n "$1" ] && [[ "$1" != --* ]]; then
    TS_VERSION="$1"
    if [[ "$TS_VERSION" != v* ]]; then
        TS_VERSION="v${TS_VERSION}"
    fi
    echo "$TS_VERSION" > TAILSCALE_VERSION
    echo "-> Updated TAILSCALE_VERSION to: $TS_VERSION"
elif [ -n "$TS_VER_TAG" ]; then
    TS_VERSION="$TS_VER_TAG"
    echo "-> Using provided TS_VER_TAG: $TS_VERSION"
elif [ -f "TAILSCALE_VERSION" ]; then
    TS_VERSION=$(cat TAILSCALE_VERSION | tr -d ' \n\r')
    echo "-> Read Tailscale version from TAILSCALE_VERSION: $TS_VERSION"
else
    TS_VERSION="v1.104.0"
    echo "$TS_VERSION" > TAILSCALE_VERSION
    echo "-> Defaulting Tailscale version to: $TS_VERSION"
fi

echo "-> Auto-formatting appctr Go files..."
gofmt -w *.go

# Check if force clean rebuild is requested or if version changed
FORCE_REBUILD=0
if [ "$1" == "--clean" ] || [ "$2" == "--clean" ] || [ "$1" == "--force" ] || [ "$2" == "--force" ]; then
    FORCE_REBUILD=1
elif [ -d "tailscale_src" ]; then
    EXISTING_VER=$(cat tailscale_src/.build_version 2>/dev/null || echo "")
    if [ "$EXISTING_VER" != "$TS_VERSION" ]; then
        echo "-> Tailscale version changed ($EXISTING_VER -> $TS_VERSION). Forcing clean download & patch."
        FORCE_REBUILD=1
    fi
fi

if [ "$FORCE_REBUILD" -eq 1 ]; then
    echo "-> Cleaning old tailscale_src and orig..."
    rm -rf tailscale_src orig
fi

echo "[1/4] Preparing and Patching Tailscale sources (${TS_VERSION})..."
# A tree patched by an older patch set is not the tree the patches describe, and
# nothing noticed: the download-and-patch block below is skipped whenever
# tailscale_src exists, so weeks of edits could accumulate locally while CI —
# which always starts clean — built something else. That is how the 4.0.0 tag
# failed. Stamp the patch set and force a clean re-patch when it moves.
PATCH_STAMP=$(cat patches/*.patch TAILSCALE_VERSION 2>/dev/null | sha256sum | cut -d" " -f1)
if [ -d "tailscale_src" ] && [ "$(cat tailscale_src/.patch_stamp 2>/dev/null)" != "$PATCH_STAMP" ]; then
    echo "-> Patch set changed since tailscale_src was built. Forcing a clean re-patch."
    rm -rf tailscale_src
fi
# A tree unpacked before the commit was recorded (below) cannot stamp the version.
if [ -d "tailscale_src" ] && [ ! -s tailscale_src/.commit ]; then
    echo "-> tailscale_src predates the recorded upstream commit. Forcing a clean download."
    rm -rf tailscale_src
fi

if [ ! -d "tailscale_src" ]; then
    echo "-> Downloading sources for ${TS_VERSION}..."
    # The archive is checked against TAILSCALE_SHA256 before anything in it is
    # used: the build patches and compiles whatever arrives, and piping curl into
    # tar trusted it blindly. For a new version the hash is not there yet — the
    # script prints what the archive hashes to; verify it, add the line, or rerun
    # once with TS_TRUST_NEW_TARBALL=1 to record it.
    TARBALL="tailscale-${TS_VERSION}.tar.gz"
    curl -fsSL -o "$TARBALL" "https://github.com/tailscale/tailscale/archive/refs/tags/${TS_VERSION}.tar.gz"
    ACTUAL_SHA=$(sha256sum "$TARBALL" | cut -d" " -f1)
    EXPECTED_SHA=$(awk -v v="$TS_VERSION" '!/^#/ && $2 == v { print $1 }' TAILSCALE_SHA256 2>/dev/null)
    if [ -z "$EXPECTED_SHA" ]; then
        if [ "${TS_TRUST_NEW_TARBALL:-0}" = "1" ]; then
            echo "$ACTUAL_SHA  $TS_VERSION" >> TAILSCALE_SHA256
            echo "-> Recorded the ${TS_VERSION} archive hash in TAILSCALE_SHA256: $ACTUAL_SHA"
        else
            rm -f "$TARBALL"
            echo "❌ No checksum for ${TS_VERSION} in appctr/TAILSCALE_SHA256. The archive hashes to:"
            echo "   $ACTUAL_SHA  $TS_VERSION"
            echo "   Verify it, then add that line (or rerun with TS_TRUST_NEW_TARBALL=1)."
            exit 1
        fi
    elif [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        rm -f "$TARBALL"
        echo "❌ The ${TS_VERSION} archive does not match TAILSCALE_SHA256:"
        echo "   expected $EXPECTED_SHA"
        echo "   got      $ACTUAL_SHA"
        exit 1
    fi
    # git archive records the tag's commit in the tarball; the hash check above
    # already vouches for it. It goes into the version the daemon reports.
    TS_COMMIT=$(gzip -dc "$TARBALL" | git get-tar-commit-id)
    tar -xzf "$TARBALL"
    rm -f "$TARBALL"
    mv tailscale-${TS_VERSION#v} tailscale_src
    echo "$TS_VERSION" > tailscale_src/.build_version
    echo "${TS_COMMIT:?the ${TS_VERSION} archive names no commit}" > tailscale_src/.commit

    echo "-> Applying atomic patches..."
    for p in patches/*.patch; do
        if [ -f "$p" ]; then
            # A zero-byte patch is a lost fix, not a no-op — GNU patch accepts it
            # and exits 0, so it would ship silently missing. Fail loudly instead.
            if [ ! -s "$p" ]; then
                echo "❌ Patch $(basename "$p") is empty — a fix was lost. Regenerate it before building." >&2
                exit 1
            fi
            echo "Applying patch: $(basename "$p")"
            # --batch/--forward keep patch non-interactive (a moved target would
            # otherwise hang waiting for input); -F0 refuses fuzzy placement so a
            # version bump that shifts context fails here rather than landing wrong.
            patch -p1 --batch --forward -F0 -d tailscale_src < "$p"
        fi
    done

    echo "$PATCH_STAMP" > tailscale_src/.patch_stamp
    echo "✅ Sources patched successfully for ${TS_VERSION}."
else
    echo "-> Sources already exist and patched for ${TS_VERSION}. Skipping download."
fi

echo "[2/4] Compiling binaries in PIE mode..."

cd tailscale_src
mkdir -p tmp

# The version the daemon reports — `tailscale version`, the status, and the
# admin console through Hostinfo. -buildvcs=false (below) leaves Go no VCS
# information to derive one from, and without these stamps every build called
# itself "1.104.0-ERR-BuildInfo". Shaped like Tailscale's own Android builds:
# x.y.z-t<tailscale commit>-g<this repo's commit>. Both come from the commits
# being built, never the clock, so a rebuild stamps the same.
TS_COMMIT=$(cat .commit)
APP_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "")
TS_SHORT_VER=${TS_VERSION#v}
VERSION_LDFLAGS="-X tailscale.com/version.shortStamp=${TS_SHORT_VER} -X tailscale.com/version.longStamp=${TS_SHORT_VER}-t${TS_COMMIT:0:9}${APP_COMMIT:+-g${APP_COMMIT:0:9}} -X tailscale.com/version.gitCommitStamp=${TS_COMMIT} -X tailscale.com/version.extraGitCommitStamp=${APP_COMMIT}"

# Let GOTOOLCHAIN fetch whatever the upstream module's `go` directive requires.
# Forcing `-go=1.23` here downgraded the module below what v1.102.1 needs
# (generic type aliases, sync.WaitGroup.Go) and reverted it to the unpruned
# module graph, changing transitive versions from what upstream pinned.
# An environment that already chose — GOTOOLCHAIN=local on an F-Droid builder,
# which provides its own Go and allows no download — keeps its choice.
export GOTOOLCHAIN=${GOTOOLCHAIN:-auto}

# The patches add files that import modules upstream does not require — the
# android netmon fix pulls in github.com/wlynxg/anet — so the module graph has
# to be settled before anything is compiled. This used to happen as a side
# effect of the `go mod edit -go=1.23` block that was removed for downgrading
# the module below what v1.102.1 needs; only the downgrade was wrong, tidying
# was load-bearing. Without it a clean build fails at the first daemon
# compile with "no required module provides package".
#
# tidy alone would take whatever anet release is newest on build day, so two
# builds of one commit could differ. It is pinned to the version appctr/go.mod
# already requires; `go get` verifies it against sum.golang.org.
ANET_VERSION=$(awk '$1 == "github.com/wlynxg/anet" { print $2 }' ../go.mod)
go get "github.com/wlynxg/anet@${ANET_VERSION:?appctr/go.mod names no anet version}"
go mod tidy

TAGS="ts_omit_systray,ts_omit_kube,ts_omit_aws,ts_omit_bird,ts_omit_qrcodes,ts_omit_desktop_sessions,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved,ts_omit_sdnotify,ts_omit_tpm,ts_omit_logtail,ts_omit_synology,ts_omit_syspolicy,ts_omit_ssh,ts_omit_iptables,ts_omit_tap,ts_omit_linuxdnsfight,ts_omit_captiveportal,ts_omit_appconnectors,ts_omit_completion,ts_omit_completion_scripts,ts_omit_oauthkey,ts_omit_syslog,ts_omit_clientupdate,ts_omit_portlist,ts_omit_capture,ts_omit_debugportmapper,ts_omit_wakeonlan,ts_omit_relayserver,ts_omit_serviceclientprefs,ts_omit_androidbin,ts_omit_androiddns,ts_omit_connreject"

# Everything below must come out byte for byte the same wherever it is built:
# F-Droid rebuilds the release and ships our APK only if its build matches.
# -trimpath drops the build paths; -buildvcs=false keeps git state out of the
# binaries, since F-Droid edits the tree before building and Go would stamp it
# "modified"; the core version carries the commit's time, not the clock's.
export GOFLAGS=-buildvcs=false

# TS_ABIS picks the ABIs to build, space-separated; all four by default.
# F-Droid builds one APK per ABI and asks for just the one it is packaging.
TS_ABIS=${TS_ABIS:-"armeabi-v7a arm64-v8a x86 x86_64"}

# abi -> GOARCH, clang target, file suffix (gomobile takes android/<GOARCH>)
abi_goarch()  { case $1 in armeabi-v7a) echo arm;; arm64-v8a) echo arm64;; x86) echo 386;; x86_64) echo amd64;; *) return 1;; esac; }
abi_clang()   { case $1 in armeabi-v7a) echo armv7a-linux-androideabi21;; arm64-v8a) echo aarch64-linux-android21;; x86) echo i686-linux-android21;; x86_64) echo x86_64-linux-android21;; esac; }
abi_suffix()  { case $1 in armeabi-v7a) echo arm;; arm64-v8a) echo arm64;; x86) echo x86;; x86_64) echo x86_64;; esac; }

GOMOBILE_TARGETS=""
for ABI in $TS_ABIS; do
    GOARCH_ABI=$(abi_goarch "$ABI") || { echo "Unknown ABI in TS_ABIS: $ABI" >&2; exit 1; }
    SUFFIX=$(abi_suffix "$ABI")
    GOMOBILE_TARGETS="${GOMOBILE_TARGETS:+$GOMOBILE_TARGETS,}android/$GOARCH_ABI"
    export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/$(abi_clang "$ABI")-clang"
    export CGO_ENABLED=1
    if [ "$GOARCH_ABI" = arm ]; then export GOARM=7; else unset GOARM; fi
    for PART in "tailscaled:libtailscale" "tailscale:libtailscale_cli"; do
        echo "-> Compiling ${PART%%:*} [$ABI]..."
        GOOS=android GOARCH=$GOARCH_ABI go build -v \
            -buildmode=pie \
            -trimpath \
            -tags "$TAGS" \
            -ldflags="-s -w -buildid= -checklinkname=0 ${VERSION_LDFLAGS}" \
            -o "tmp/${PART##*:}_${SUFFIX}.so" "./cmd/${PART%%:*}"
    done
done

cd ..

echo "[3/4] Building appctr.aar (Gomobile Bridge)..."
GIT_HASH=$(git rev-parse --short=7 HEAD 2>/dev/null || echo "dev")
BUILD_TIME=$(TZ=UTC git log -1 --format=%cd --date=format-local:%Y-%m-%d_%H%M%S 2>/dev/null || echo "unknown")
FULL_CORE_VER="${TS_VERSION}-${GIT_HASH}-${BUILD_TIME}"

mkdir -p tmp
unset CC GOARM
go mod tidy
gomobile bind -ldflags="-s -w -buildid= -checklinkname=0 -X appctr.coreVersion=${FULL_CORE_VER} ${VERSION_LDFLAGS}" -trimpath -target="$GOMOBILE_TARGETS" -androidapi 21 -tags "$TAGS" -o tmp/appctr.aar -v .

echo "[4/4] Copying binaries to jniLibs..."
for ABI in $TS_ABIS; do
    SUFFIX=$(abi_suffix "$ABI")
    mkdir -p "../app/src/main/jniLibs/$ABI"
    cp "tailscale_src/tmp/libtailscale_${SUFFIX}.so" "../app/src/main/jniLibs/$ABI/libtailscale.so"
    cp "tailscale_src/tmp/libtailscale_cli_${SUFFIX}.so" "../app/src/main/jniLibs/$ABI/libtailscale_cli.so"
done

echo "✅ Done! Ready to assemble APK."
