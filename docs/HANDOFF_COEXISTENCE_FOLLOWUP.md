# Root Mode coexistence — what the review found and what is still open

Written at the end of the night of 2026-09-06, after the adversarial review of the
coexistence work (six lenses, 33 findings raised, each put to three independent
refuters). The author asked that nothing behavioural be changed while he slept, so
everything below is **queued, not done** — except the documentation and log-wording
fixes, which are already in.

Read [`docs/research/foreign-vpn-measurements.md`](research/foreign-vpn-measurements.md)
first. Every number quoted here was measured on the author's Redmi (APatch root)
against Exclave (`com.github.dyhkwong.sagernet`).

## Already fixed during the night, do not re-raise

Several findings describe code that was rewritten after the review started:

* The apply signature omitting the foreign tunnel's uid ranges and netIds —
  fixed in `b95fd7c` (`foreignRoutingShape`).
* Membership changes going unnoticed — fixed in `89721f4`. The capabilities
  callback cannot carry it (membership is redacted for a non-member, and a client
  that excludes us is exactly the one we care about); the refresh tick reads the
  shape while a foreign tunnel exists.
* Gaps computed from every non-physical table, so `wlan0_local`'s
  `uidrange 0-2147483647` swallowed them all — fixed in `9450f2d`.
* The exit-node catch-all withheld because the daemon's log had no probe line —
  fixed in `cecbcbf` (environ fallback). See F2 below, which is about that fix.

## Open, ordered by what it costs the user

### F1 — We capture the other VPN client's own uid *(blocker)*

A VpnService's owner is always outside its own tunnel, or it would loop its relay
traffic through itself. So its uid is **always** a gap, and the partial yield
therefore always engages: measured with no user bypass at all, the gaps came out
as `10354` (Exclave itself), `20354`, `20375`.

Two consequences:

1. Exclave's own outbound — its trojan connection to its relay — is routed into
   our table 52 and out through our exit node. Nobody asked for that.
2. The full yield became unreachable: with a gap always present, `shared` is
   always true, so T3 (the device-wide DNS DNAT) is reinstalled whenever any
   foreign VPN is up, which is the tier the whole design says is the most
   damaging to hand ourselves.

**Fix.** Carry only gaps that are real user choices. Concretely: intersect the
gaps with the uids of *installed* packages, then subtract every package that
declares a `VpnService` — `PackageManager.queryIntentServices(Intent("android.net.VpnService"))`
gives them without root and without any permission. Subtract our own uid as we do
now. If the intersection is empty, that is the full yield, reached honestly.

This is also the "automatic scan of VPN app ids" the author asked for earlier.

### F2 — The socket-marking environ fallback is inverted, and too trusting *(blocker)*

`daemonMarksByEnviron()` reads `TS_VPN_BYPASS=0` as "does not mark sockets". That
is wrong: patch 16's `socketMark()` returns `TailsocksBypassMark` (bit 25)
*unconditionally* and only adds `androidProtectedFromVpnMark` (bit 17) when the
setting is on. Bit 25 is the one `CATCH_ALL_MASK` tests, so a daemon started with
`TS_VPN_BYPASS=0` still skips our table. The current code withholds the exit node
from it for no reason.

It is also too trusting in the other direction: it accepts any process named
`libtailscale.so`, without checking it is the binary we ship. Verify
`/proc/<pid>/exe` resolves under the app's `nativeLibraryDir`, and match the
daemon by its `--socket=` argument rather than by process name.

### F3 — Cleanup races an in-flight apply *(blocker)*

The not-Running path clears `rootRoutingApplied` and tears the ruleset down after
two ticks. An apply already running in its own thread then finishes and writes
`lastRootRoutingSignature`, so the rules it installed are on the device while the
service believes they were removed — and the signature makes the next apply a
no-op. Guard the cleanup on `rootRoutingInFlight`, and have the apply drop its
result if teardown began while it ran.

### F4 — `reevaluateRootRouting` disarms the cleanup *(major)*

It clears `rootRoutingApplied`, but that flag is also what the not-Running branch
uses to decide there is anything to clean up. Re-arm the latch without losing the
"rules are installed" fact — they are two different questions sharing one field.
The same function clears `rootRoutingFailures`, so the documented "give up after
three attempts" bound never actually holds; decide which behaviour is wanted and
make the code and the comment agree.

### F5 — A failed apply leaves what it already installed *(major)*

`applyTailscale0Routing` returns false and `root_routing_installed` stays false,
but the script has already installed part of the ruleset. Nothing will remove it —
not the next apply, not the stop path. Set the installed marker before the script
runs, or make the failure path tear down what it can.

### F6 — Editing the excluded-apps list does not re-apply *(major)*

The list is reachable from the Root Mode row now, and the rules are built from it,
but changing it does not queue a re-check. The same is true of a package being
installed or removed mid-run: the signature keys on package names while the rules
are built from uids.

### F7 — The UI shows a partial yield as a full one *(blocker for honesty)*

`root_routing_yielded` is written as `foreignVpn && !takeDeviceAnyway`, which is
true in the shared case too. The main screen therefore says the exit node is off
while it is in fact carrying the other tunnel's bypassed apps through it. There
are three states now and the UI has two. The ROOT log was corrected tonight; the
persisted flag and the dashboard were not, because that is behaviour.

### F8 — Smaller, all confirmed

* A foreign tunnel caught mid-establish reads as absent, and nothing asks again
  until the next edge.
* Losing the netId `RETURN` (foreign tunnel restarts with a new netId) leaves the
  DNAT installed and the log still claims its DNS was left alone. The shape now
  covers the netId, so this is narrower than it was — confirm it is closed.
* `ROOT.md`'s "three states" table and the CHANGELOG still describe the full yield
  as the only yield.
* "Ignore other VPNs" is dropped by settings export/import.
* The DNS tier is IPv4-only: no `ip6tables -t nat` anywhere, so DNS to an IPv6
  resolver is neither redirected nor protected.
* Per-app exclusions fail silently where `ip` has no `uidrange` support, and the
  log then claims they are in effect.
* Every routing re-check spawns a root shell before the signature short-circuits,
  and a real apply now probes twice on some paths.

## Method note for whoever picks this up

The author tests on his own devices and is Russian-speaking; answer in Russian.
Never `git push` without his explicit «пушь», never `adb uninstall` without asking.
Root decisions are mirrored to logcat as of `9450f2d`:

```bash
adb shell "su -c 'logcat -d --pid=$(pidof io.github.bropines.tailscaled) | grep RootUtils'"
```

The `coexistence inputs:` line names every input behind the decision — marks,
foreign, override, ranges, gaps, netIds. When something looks wrong, read that
line before theorising: it separates "nobody else is here" from "they claimed
everything" from "we misread what they claimed", and those need different fixes.
