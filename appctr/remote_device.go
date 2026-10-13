package appctr

// remote_device.go — what a status answer says of the device beyond its
// connection: what it is, and what it offers the tailnet to route.

import (
	"encoding/json"
	"net/netip"
	"strings"
)

// remoteOffered is one thing the device offers to route and whether the
// tailnet has agreed: an advertised route does nothing until the tailnet's
// admin — or its autoApprovers — approves it.
type remoteOffered struct {
	On       bool `json:"on"`
	Approved bool `json:"approved"`
}

type remoteOfferedRoute struct {
	CIDR     string `json:"cidr"`
	Approved bool   `json:"approved"`
}

// remoteAdvertise is what the device advertises: itself as an exit node, and
// subnets.
type remoteAdvertise struct {
	ExitNode remoteOffered        `json:"exitNode"`
	Routes   []remoteOfferedRoute `json:"routes"`
}

func remoteIsDefaultRoute(p netip.Prefix) bool { return p.Bits() == 0 }

// advertise reads what the daemon advertises from its prefs and what the
// tailnet approved from this node's own allowed addresses; nil when the
// daemon does not say.
func (m *remoteManager) advertise(self remoteSelf) *remoteAdvertise {
	raw, err := m.api.get("/localapi/v0/prefs")
	if err != nil {
		return nil
	}
	var prefs struct {
		AdvertiseRoutes []netip.Prefix
	}
	if json.Unmarshal(raw, &prefs) != nil {
		return nil
	}
	approved := map[netip.Prefix]bool{}
	for _, p := range self.AllowedIPs {
		approved[p.Masked()] = true
	}
	out := &remoteAdvertise{Routes: []remoteOfferedRoute{}}
	for _, p := range prefs.AdvertiseRoutes {
		p = p.Masked()
		if remoteIsDefaultRoute(p) {
			out.ExitNode.On = true
			// An exit node is both default routes; either approved is both.
			out.ExitNode.Approved = out.ExitNode.Approved || approved[p]
			continue
		}
		if len(out.Routes) < remoteMaxRoutes {
			out.Routes = append(out.Routes, remoteOfferedRoute{CIDR: p.String(), Approved: approved[p]})
		}
	}
	return out
}

// remoteRoutesValue reads a list of routes as a caller wrote it — prefixes
// separated by commas — and returns it as the app is to store it: each one
// masked and written the one way, no route twice. An empty list is "". The
// two default routes are not routes to advertise: offering them is being an
// exit node, which is a switch of its own.
func remoteRoutesValue(value string) (string, bool) {
	if strings.TrimSpace(value) == "" {
		return "", true
	}
	if len(value) > 1024 {
		return "", false
	}
	var out []string
	seen := map[netip.Prefix]bool{}
	for _, part := range strings.Split(value, ",") {
		p, err := netip.ParsePrefix(strings.TrimSpace(part))
		if err != nil || remoteIsDefaultRoute(p) {
			return "", false
		}
		if p = p.Masked(); seen[p] {
			continue
		}
		seen[p] = true
		out = append(out, p.String())
	}
	if len(out) > remoteMaxRoutes {
		return "", false
	}
	return strings.Join(out, ","), true
}
