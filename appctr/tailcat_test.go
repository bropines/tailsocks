package appctr

import "testing"

func TestTcParseMappings(t *testing.T) {
	good := map[string][]tcMapping{
		"8080":                     {{listen: "127.0.0.1:8080", port: 8080}},
		"18080:8080":               {{listen: "127.0.0.1:18080", port: 8080}},
		"0:8080":                   {{listen: "127.0.0.1:0", port: 8080}},
		"8080, 9000:22\n443":       {{listen: "127.0.0.1:8080", port: 8080}, {listen: "127.0.0.1:9000", port: 22}, {listen: "127.0.0.1:443", port: 443}},
		"3001:192.168.1.5:3001":    {{listen: "127.0.0.1:3001"}},
		"5555:[fd7a:115c::1]:5555": {{listen: "127.0.0.1:5555"}},
	}
	for in, want := range good {
		got, err := tcParseMappings(in)
		if err != nil {
			t.Errorf("%q: %v", in, err)
			continue
		}
		if len(got) != len(want) {
			t.Errorf("%q: %d mappings, want %d", in, len(got), len(want))
			continue
		}
		for i := range got {
			if got[i].listen != want[i].listen || (want[i].port != 0 && got[i].port != want[i].port) {
				t.Errorf("%q[%d] = %+v, want %+v", in, i, got[i], want[i])
			}
			if want[i].port == 0 && !got[i].target.IsValid() {
				t.Errorf("%q[%d]: no target address", in, i)
			}
		}
	}
	for _, in := range []string{"", "  ", "abc", "0", "70000", "8080:", ":8080", "1:host.example:22", "1:192.168.1.5", "1:192.168.1.5:0"} {
		if _, err := tcParseMappings(in); err == nil {
			t.Errorf("%q: accepted", in)
		}
	}
}

func TestTailcatKeys(t *testing.T) {
	priv := TailcatGenerateClientKey()
	pub := TailcatPublicKey(priv)
	if len(pub) < len("nodekey:") || pub[:8] != "nodekey:" {
		t.Fatalf("public key of %q = %q", priv, pub)
	}
	if TailcatPublicKey("nonsense") != "" {
		t.Error("a malformed key gave a public key")
	}
}

func TestTcQuiet(t *testing.T) {
	for _, l := range []string{
		"[v1] using fake (no-op) tun device",
		"wg: [v2] peer(Zjxp…nGik) - Sending keepalive packet",
		"netcheck: [v1] report: udp=true v6=false",
		"monitor: monitor_linux: AF_NETLINK RTMGRP failed, falling back to polling",
		"magicsock: [warning] failed to force-set UDP read buffer size to 7340032: operation not permitted",
		`NetworkMap: {"Cached":false,"SelfNode":{"ID":2}}`,
		"Bringing WireGuard device up...",
		"Engine created.",
		"dns: Set: {DefaultResolvers:[] Routes:{}}",
		"wgengine: Reconfig: configuring router",
		"magicsock: disco key = d:788864f031b1a337",
		"magicsock: 1 active derp conns: derp-303=cr0s,wr0s",
		"ping(fd7a:115c:a1e0:663c:6922:bc1c:3a04:3a09): sending TSMP ping to [ZjxpI]  ...",
		"wgengine: got TSMP pong 5bb4686ce47bea64, peerAPIPort=0; cb=true",
	} {
		if !tcQuiet.MatchString(l) {
			t.Errorf("kept: %s", l)
		}
	}
	for _, l := range []string{
		"magicsock: home is now derp-303 (fra)",
		"derphttp.Client.Connect: connecting to derp-303 (fra)",
		"magicsock: endpoints changed: 95.165.172.169:39177 (stun)",
		"magicsock: derp-303 connected; connGen=1",
		"magicsock: home DERP changing from derp-0 [0ms] to derp-303 [52ms] (forced=false)",
		"link state: interfaces.State{defaultRoute=wlan0}",
		"the server answered through the relay in 253ms",
		"path: fra, 100 ms",
		"127.0.0.1:28000 -> 28000: context deadline exceeded",
	} {
		if tcQuiet.MatchString(l) {
			t.Errorf("dropped: %s", l)
		}
	}
}
