package appctr

// setup_net.go — which of this device's addresses a setup session (setup.go)
// may listen on, and which addresses a phone may be sent to.

import (
	"cmp"
	"encoding/json"
	"net"
	"net/netip"
	"slices"
	"strings"
	"sync/atomic"

	"github.com/wlynxg/anet"
)

// setupMaxAddrs is how many addresses a code carries: wired, wireless and one
// to spare. Each costs four bytes of a QR code that has to be read off a
// television from across a room.
const setupMaxAddrs = 3

// setupLoopbackOK lets a session listen on, and a phone send to, 127.0.0.1.
// Only the tests set it: they run both ends on one machine.
var setupLoopbackOK atomic.Bool

// setupLocal is one address of this device with the network it is in.
type setupLocal struct {
	name   string
	prefix netip.Prefix // /32 when the mask is not known
}

// setupLANAddr reports whether a is an address of a home or office network:
// IPv4, in a private range (10/8, 172.16/12, 192.168/16) or link-local
// (169.254/16). That leaves out, on purpose, the Tailscale address and
// carrier NAT (100.64/10 is neither), every public address, and IPv6 — a code
// has no room for it, and a LAN without IPv4 is rarer than a television
// without a remote.
func setupLANAddr(a netip.Addr) bool {
	if !a.Is4() {
		return false
	}
	if a.IsLoopback() {
		return setupLoopbackOK.Load()
	}
	return a.IsPrivate() || a.IsLinkLocalUnicast()
}

// setupTunnelNames are the interface names a session never listens on, by
// prefix: tunnels, cellular data and other point-to-point links. The flags say
// the same where they can be read; the names cover the fallback that has none.
var setupTunnelNames = []string{
	"lo", "tun", "tap", "tailscale", "wg", "ppp", "ipsec", "xfrm", "utun",
	"rmnet", "r_rmnet", "rev_rmnet", "ccmni", "ccinet", "pdp", "wwan", "seth",
	"clat", "v4-", "dummy", "sit", "gre", "ip6tnl",
}

// setupIfaceUsable reports whether an interface is a local network: up, not
// loopback, not point-to-point (a VPN's device is — the Tailscale one too),
// and not named like a tunnel. flags are ignored when they could not be read.
func setupIfaceUsable(name string, flags net.Flags, flagsKnown bool) bool {
	if flagsKnown {
		if flags&net.FlagUp == 0 {
			return false
		}
		if flags&net.FlagLoopback != 0 {
			return setupLoopbackOK.Load()
		}
		if flags&net.FlagPointToPoint != 0 {
			return false
		}
	}
	lower := strings.ToLower(name)
	for _, p := range setupTunnelNames {
		if strings.HasPrefix(lower, p) {
			return p == "lo" && setupLoopbackOK.Load()
		}
	}
	return true
}

// systemLocals lists the addresses of this device's local networks. The
// second result is false when the system would not say, which is not the same
// as having none.
//
// The answer has to be current — a session checks it to notice that its
// address is gone — so the kernel is asked first: anet, because Go's own
// net.Interfaces is refused to an app since Android 11. What Kotlin last
// injected is the fallback; it is only refreshed while a daemon runs and has
// neither flags nor masks.
func systemLocals() ([]setupLocal, bool) {
	ifs, err := anet.Interfaces()
	if err == nil && len(ifs) > 0 {
		var out []setupLocal
		for i := range ifs {
			if !setupIfaceUsable(ifs[i].Name, ifs[i].Flags, true) {
				continue
			}
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				continue
			}
			for _, a := range addrs {
				if ipn, ok := a.(*net.IPNet); ok {
					if l, ok := setupLocalOf(ifs[i].Name, ipn); ok {
						out = append(out, l)
					}
				}
			}
		}
		return out, true
	}
	stateMu.Lock()
	state := latestInterfaceState
	stateMu.Unlock()
	return injectedLocals(state)
}

// setupLocalOf is an interface address as the kernel gave it, with its mask.
func setupLocalOf(name string, ipn *net.IPNet) (setupLocal, bool) {
	ip, ok := netip.AddrFromSlice(ipn.IP)
	if !ok {
		return setupLocal{}, false
	}
	ip = ip.Unmap()
	ones, bits := ipn.Mask.Size()
	if bits == 128 && ip.Is4() {
		// The mask of the IPv4-mapped form: its last 32 bits are the IPv4 one.
		ones -= 96
	}
	if bits == 0 || ones < 0 || ones > ip.BitLen() {
		ones = ip.BitLen()
	}
	return setupLocal{name: name, prefix: netip.PrefixFrom(ip, ones)}, true
}

// injectedLocals reads the interface list Kotlin injects (InjectNetworkState):
// names and addresses of the interfaces that are up, without masks.
func injectedLocals(state string) ([]setupLocal, bool) {
	if state == "" {
		return nil, false
	}
	var list []struct {
		Name      string   `json:"name"`
		Addresses []string `json:"addresses"`
		Up        bool     `json:"up"`
	}
	if err := json.Unmarshal([]byte(state), &list); err != nil {
		return nil, false
	}
	var out []setupLocal
	for _, iface := range list {
		if !iface.Up || !setupIfaceUsable(iface.Name, 0, false) {
			continue
		}
		for _, s := range iface.Addresses {
			// Java prints a scoped address as "fe80::1%wlan0".
			s, _, _ = strings.Cut(s, "%")
			ip, err := netip.ParseAddr(s)
			if err != nil {
				continue
			}
			ip = ip.Unmap()
			out = append(out, setupLocal{name: iface.Name, prefix: netip.PrefixFrom(ip, ip.BitLen())})
		}
	}
	return out, true
}

// setupListenAddrs picks, from this device's addresses, the ones a session
// listens on: LAN addresses only, wired before wireless before the rest, at
// most setupMaxAddrs.
func setupListenAddrs(locals []setupLocal) []netip.Addr {
	rank := func(name string) int {
		switch lower := strings.ToLower(name); {
		case strings.HasPrefix(lower, "eth"), strings.HasPrefix(lower, "en"):
			return 0
		case strings.HasPrefix(lower, "wlan"), strings.HasPrefix(lower, "wl"):
			return 1
		}
		return 2
	}
	sorted := slices.Clone(locals)
	slices.SortStableFunc(sorted, func(a, b setupLocal) int {
		return cmp.Or(
			cmp.Compare(rank(a.name), rank(b.name)),
			cmp.Compare(a.name, b.name),
			a.prefix.Addr().Compare(b.prefix.Addr()),
		)
	})
	var out []netip.Addr
	for _, l := range sorted {
		a := l.prefix.Addr()
		if !setupLANAddr(a) || slices.Contains(out, a) {
			continue
		}
		out = append(out, a)
		if len(out) == setupMaxAddrs {
			break
		}
	}
	return out
}

// setupSourceOK reports whether a connection that arrived at local from
// remote is one a session reads at all. The sender has to be on a local
// network too: a listener bound to a LAN address cannot be reached through
// the tunnel, but a router that forwards a port to it would hand over an
// internet address. And it has to be another device — a connection from the
// address it arrived at, or from another the session listens on, is some
// other app on this one, never a phone.
func setupSourceOK(local, remote netip.Addr, own []netip.Addr) bool {
	if !setupLANAddr(remote) {
		return false
	}
	if local.IsLoopback() {
		// Only a test's session listens here, and its phone is this machine.
		return remote.IsLoopback()
	}
	return remote != local && !slices.Contains(own, remote)
}

// setupSameNetwork reports whether any of this device's local networks holds
// one of the targets. known is false when that cannot be said: the system did
// not list its addresses (listed), or listed one without its mask. A device
// with no IPv4 address on a local network at all — a phone on mobile data —
// is known not to be on the targets' network.
func setupSameNetwork(locals []setupLocal, listed bool, targets []netip.Addr) (same, known bool) {
	if !listed {
		return false, false
	}
	known = true
	for _, l := range locals {
		if !l.prefix.Addr().Is4() {
			continue
		}
		if l.prefix.Bits() >= l.prefix.Addr().BitLen() {
			known = false
			continue
		}
		for _, t := range targets {
			if l.prefix.Contains(t) {
				return true, true
			}
		}
	}
	return false, known
}
