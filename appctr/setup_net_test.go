package appctr

import (
	"net"
	"net/netip"
	"slices"
	"testing"
)

func addrs(ss ...string) []netip.Addr {
	out := make([]netip.Addr, len(ss))
	for i, s := range ss {
		out[i] = netip.MustParseAddr(s)
	}
	return out
}

// Home and office networks, and nothing else: not the tunnel, not a carrier,
// not the internet, not this machine.
func TestSetupLANAddr(t *testing.T) {
	for _, a := range addrs("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.0.1", "192.168.43.1", "169.254.7.7") {
		if !setupLANAddr(a) {
			t.Errorf("%v: refused", a)
		}
	}
	for _, a := range addrs(
		"100.64.0.1", "100.101.102.103", "100.127.255.254", // the tailnet, carrier NAT
		"127.0.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.251",
		"8.8.8.8", "172.15.0.1", "172.32.0.1", "192.167.1.1", "192.169.1.1", "11.0.0.1", "198.18.0.1",
		"fd7a:115c:a1e0::1", "fe80::1", "fc00::1", "::1", "::ffff:192.168.1.1",
	) {
		if setupLANAddr(a) {
			t.Errorf("%v: accepted", a)
		}
	}
	if setupLANAddr(netip.Addr{}) {
		t.Error("no address: accepted")
	}
}

func TestSetupIfaceUsable(t *testing.T) {
	lan := net.FlagUp | net.FlagBroadcast | net.FlagMulticast | net.FlagRunning
	for _, tc := range []struct {
		name  string
		flags net.Flags
		known bool
		want  bool
	}{
		{"wlan0", lan, true, true},
		{"eth0", lan, true, true},
		{"ap0", lan, true, true},
		{"p2p-wlan0-0", lan, true, true},
		{"rndis0", lan, true, true},
		{"br0", lan, true, true},
		{"wlan0", lan &^ net.FlagUp, true, false},
		{"lo", net.FlagUp | net.FlagLoopback, true, false},
		{"tun0", net.FlagUp | net.FlagPointToPoint, true, false},
		// A VPN's device under a name of its own: the flag gives it away.
		{"vpn-office", net.FlagUp | net.FlagPointToPoint, true, false},
		// And with a tunnel's name, whatever the flags claim.
		{"tun0", lan, true, false},
		{"tailscale0", lan, true, false},
		{"wg0", lan, true, false},
		{"rmnet_data0", net.FlagUp, true, false},
		{"ccmni1", net.FlagUp, true, false},
		{"v4-wlan0", lan, true, false},
		{"ppp0", lan, true, false},
		// From the injected list there are no flags, only names.
		{"wlan0", 0, false, true},
		{"eth1", 0, false, true},
		{"tun0", 0, false, false},
		{"TUN0", 0, false, false},
		{"rmnet0", 0, false, false},
		{"lo", 0, false, false},
	} {
		if got := setupIfaceUsable(tc.name, tc.flags, tc.known); got != tc.want {
			t.Errorf("%s flags=%v known=%v: %v, want %v", tc.name, tc.flags, tc.known, got, tc.want)
		}
	}
}

func TestSetupListenAddrs(t *testing.T) {
	l := func(name, prefix string) setupLocal {
		return setupLocal{name: name, prefix: netip.MustParsePrefix(prefix)}
	}
	for _, tc := range []struct {
		name   string
		locals []setupLocal
		want   []netip.Addr
	}{
		{name: "nothing"},
		{name: "wireless only", locals: []setupLocal{l("wlan0", "192.168.1.50/24"), l("wlan0", "fe80::1/64")}, want: addrs("192.168.1.50")},
		{name: "wired first", locals: []setupLocal{l("wlan0", "192.168.1.50/24"), l("eth0", "192.168.1.60/24")}, want: addrs("192.168.1.60", "192.168.1.50")},
		{name: "the tunnel's address is left out", locals: []setupLocal{l("wlan0", "100.64.3.4/32"), l("eth0", "100.101.102.103/10"), l("wlan0", "10.0.0.7/8")}, want: addrs("10.0.0.7")},
		{name: "a public address is left out", locals: []setupLocal{l("eth0", "203.0.113.9/24"), l("eth0", "2001:db8::1/64")}},
		{name: "loopback is left out", locals: []setupLocal{l("lo", "127.0.0.1/8")}},
		{name: "the same address twice", locals: []setupLocal{l("wlan0", "192.168.1.50/24"), l("wlan1", "192.168.1.50/24")}, want: addrs("192.168.1.50")},
		{name: "no more than a code holds", locals: []setupLocal{
			l("ap0", "192.168.43.1/24"), l("wlan0", "192.168.1.50/24"), l("eth0", "10.0.0.2/24"), l("eth1", "10.1.0.2/24"), l("usb0", "192.168.42.129/24"),
		}, want: addrs("10.0.0.2", "10.1.0.2", "192.168.1.50")},
		{name: "link-local when that is all there is", locals: []setupLocal{l("eth0", "169.254.10.20/16")}, want: addrs("169.254.10.20")},
	} {
		if got := setupListenAddrs(tc.locals); !slices.Equal(got, tc.want) {
			t.Errorf("%s: %v, want %v", tc.name, got, tc.want)
		}
	}
}

// The list Kotlin injects: java.net.NetworkInterface's, scoped IPv6 and all.
func TestInjectedLocals(t *testing.T) {
	const state = `[
		{"name":"wlan0","addresses":["fe80::a1b2:c3ff:fed4:e5f6%wlan0","192.168.1.50"],"up":true,"mtu":1500},
		{"name":"tun0","addresses":["100.101.102.103"],"up":true,"mtu":1280},
		{"name":"rmnet_data1","addresses":["10.33.44.55"],"up":true,"mtu":1400},
		{"name":"eth0","addresses":["192.168.1.60","not an address"],"up":false,"mtu":1500},
		{"name":"dummy0","addresses":["10.9.9.9"],"up":true,"mtu":1500}
	]`
	locals, ok := injectedLocals(state)
	if !ok {
		t.Fatal("not read")
	}
	var got []string
	for _, l := range locals {
		got = append(got, l.name+" "+l.prefix.String())
	}
	want := []string{"wlan0 fe80::a1b2:c3ff:fed4:e5f6/128", "wlan0 192.168.1.50/32"}
	if !slices.Equal(got, want) {
		t.Errorf("locals %q, want %q", got, want)
	}
	if listen := setupListenAddrs(locals); !slices.Equal(listen, addrs("192.168.1.50")) {
		t.Errorf("listening on %v", listen)
	}
	// Without masks nothing can be said about being on the same network.
	if same, known := setupSameNetwork(locals, true, addrs("192.168.1.60")); same || known {
		t.Errorf("same=%v known=%v from addresses without masks", same, known)
	}
	for _, bad := range []string{"", "{}", "null?", `{"name":"wlan0"}`} {
		if l, ok := injectedLocals(bad); ok || l != nil {
			t.Errorf("%q: read as %v", bad, l)
		}
	}
	if l, ok := injectedLocals("[]"); !ok || len(l) != 0 {
		t.Errorf("an empty list: %v, %v", l, ok)
	}
}

func TestSetupLocalOf(t *testing.T) {
	for _, tc := range []struct {
		ip   net.IP
		mask net.IPMask
		want string
	}{
		{net.IPv4(192, 168, 1, 50), net.CIDRMask(24, 32), "192.168.1.50/24"},
		{net.IPv4(192, 168, 1, 50).To4(), net.CIDRMask(24, 32), "192.168.1.50/24"},
		{net.IPv4(10, 0, 0, 7), net.CIDRMask(104, 128), "10.0.0.7/8"},
		{net.IPv4(10, 0, 0, 7), nil, "10.0.0.7/32"},
		{net.IPv4(10, 0, 0, 7), net.IPMask{255, 0, 255, 0}, "10.0.0.7/32"},
		{net.ParseIP("fe80::1"), net.CIDRMask(64, 128), "fe80::1/64"},
	} {
		got, ok := setupLocalOf("wlan0", &net.IPNet{IP: tc.ip, Mask: tc.mask})
		if !ok || got.prefix.String() != tc.want || got.name != "wlan0" {
			t.Errorf("%v %v: %v (%v), want %s", tc.ip, tc.mask, got.prefix, ok, tc.want)
		}
	}
	if _, ok := setupLocalOf("wlan0", &net.IPNet{IP: net.IP{1, 2, 3}}); ok {
		t.Error("three bytes read as an address")
	}
}

// Who a session reads a request from: another device on a local network.
func TestSetupSourceOK(t *testing.T) {
	own := addrs("192.168.1.50", "10.0.0.2")
	for _, tc := range []struct {
		local, remote string
		want          bool
	}{
		{"192.168.1.50", "192.168.1.23", true},
		{"192.168.1.50", "10.9.8.7", true}, // routed between two private networks
		{"192.168.1.50", "169.254.3.4", true},
		{"192.168.1.50", "192.168.1.50", false}, // an app on this device
		{"192.168.1.50", "10.0.0.2", false},     // the same, by its other address
		{"192.168.1.50", "203.0.113.5", false},  // through a forwarded port
		{"192.168.1.50", "100.101.102.103", false},
		{"192.168.1.50", "127.0.0.1", false},
	} {
		local, remote := netip.MustParseAddr(tc.local), netip.MustParseAddr(tc.remote)
		if got := setupSourceOK(local, remote, own); got != tc.want {
			t.Errorf("%s from %s: %v, want %v", tc.local, tc.remote, got, tc.want)
		}
	}
	if setupSourceOK(netip.MustParseAddr("192.168.1.50"), netip.Addr{}, own) {
		t.Error("a connection without an address was read")
	}
}

func TestSetupSameNetwork(t *testing.T) {
	l := func(prefix string) setupLocal {
		return setupLocal{name: "wlan0", prefix: netip.MustParsePrefix(prefix)}
	}
	for _, tc := range []struct {
		name        string
		locals      []setupLocal
		unlisted    bool
		targets     []netip.Addr
		same, known bool
	}{
		{name: "same network", locals: []setupLocal{l("192.168.1.23/24")}, targets: addrs("192.168.1.50"), same: true, known: true},
		{name: "another network", locals: []setupLocal{l("192.168.2.23/24")}, targets: addrs("192.168.1.50"), known: true},
		{name: "one of two targets", locals: []setupLocal{l("10.0.0.5/8")}, targets: addrs("192.168.1.50", "10.200.1.1"), same: true, known: true},
		{name: "one of two networks", locals: []setupLocal{l("10.0.0.5/8"), l("192.168.1.23/24")}, targets: addrs("192.168.1.50"), same: true, known: true},
		{name: "no masks", locals: []setupLocal{l("192.168.1.23/32")}, targets: addrs("192.168.1.50")},
		{name: "a mask missing on one of two", locals: []setupLocal{l("10.0.0.5/8"), l("192.168.1.23/32")}, targets: addrs("192.168.1.50")},
		// Mobile data is not listed as a local network, so a phone on it alone
		// has none — and is known not to be on the television's.
		{name: "no local network", targets: addrs("192.168.1.50"), known: true},
		{name: "only IPv6", locals: []setupLocal{l("fe80::1/64")}, targets: addrs("192.168.1.50"), known: true},
		{name: "the system did not say", unlisted: true, targets: addrs("192.168.1.50")},
		{name: "the system did not say, whatever is left over", locals: []setupLocal{l("192.168.1.23/24")}, unlisted: true, targets: addrs("192.168.1.50")},
	} {
		same, known := setupSameNetwork(tc.locals, !tc.unlisted, tc.targets)
		if same != tc.same || known != tc.known {
			t.Errorf("%s: same=%v known=%v, want %v %v", tc.name, same, known, tc.same, tc.known)
		}
	}
}

// Whatever this machine has, the answer names no loopback and no tunnel.
func TestSystemLocals(t *testing.T) {
	locals, ok := systemLocals()
	if !ok {
		t.Skip("this machine does not list its interfaces")
	}
	for _, l := range locals {
		if l.prefix.Addr().IsLoopback() || l.name == "lo" || !l.prefix.IsValid() {
			t.Errorf("%s %v", l.name, l.prefix)
		}
	}
}
