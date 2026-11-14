#!/bin/bash
# The Go tests both workflows run: ci.yml on every push to main and pull
# request, android.yml before a release is published. One list, so the gate on
# a tag is the gate on main.
#
#   bash .github/workflows/go-tests.sh <appctr directory>
#
# Run after appctr/build.sh, which leaves the patched Tailscale tree in
# appctr/tailscale_src and settles its module graph; with the Go build.sh used
# (GOTOOLCHAIN from appctr/go.mod). Linux only: on darwin the bridge does not
# compile for the host (patch 21's netmon.WakePollingMonitors is not built there).
set -euo pipefail
cd "$1"

# The bridge: vet and its tests. The live tests (TC_BIN, a real tailcat CLI)
# skip without their binaries.
go vet .
go test -count=1 -timeout 10m .

# The patched Tailscale packages, for the host. -checklinkname=0 as in the
# build: the vendored anet links into the standard library by name.
cd tailscale_src
FLAGS=(-ldflags=-checklinkname=0 -count=1 -timeout 10m)

# Those our patches touch and that test in seconds — only those: a flaky
# upstream test elsewhere (compositedav's goroutine count) would block releases
# for code we do not change.
go test "${FLAGS[@]}" \
  ./net/androidvpn/ \
  ./net/netmon/ \
  ./net/netns/ \
  ./net/tstun/ \
  ./net/socks5/ \
  ./net/dnscache/ \
  ./net/dns/ \
  ./net/dns/resolver/ \
  ./drive/ \
  ./drive/driveimpl/ \
  ./ipn/ipnlocal/ \
  ./ipn/ipnstate/ \
  ./ipn/localapi/ \
  ./safesocket/ \
  ./util/osuser/ \
  ./wgengine/netstack/

# Taildrop's integration tests build cmd/tailscaled for the host, and the
# patched daemon compiles only for Android (setAndroidNetMon); the rest runs.
go test "${FLAGS[@]}" -skip Integration ./feature/taildrop/

# Compiled with their tests, not run: tailssh's suite takes over three minutes,
# osrouter's needs netlink rights a runner does not give. cmd/tailscaled itself
# is compiled for Android by build.sh, which is the build that matters.
go test "${FLAGS[@]}" -run '^$' \
  ./ssh/tailssh/ \
  ./wgengine/router/osrouter/
