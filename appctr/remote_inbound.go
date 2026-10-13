package appctr

// remote_inbound.go — whether a peer's connection can reach the listener in
// the mode the daemon runs in. The reasoning is in remote.go's header, under
// "Does a peer reach the endpoint?".

import (
	"encoding/json"
	"errors"
)

// What RemoteStateJSON reports as "inbound".
const (
	// The daemon's netstack takes the connection and forwards it here.
	remoteInboundNetstack = "netstack"
	// It does not, and nothing else leads to a loopback listener.
	remoteInboundNone = "none"
	// The daemon does not answer.
	remoteInboundUnknown = "unknown"
)

// remoteForwardPath is the daemon's own word on the port: patch 22,
// cmd/tailscaled/android_remote.go answers {"Port":28115,"Forwarded":true}.
const remoteForwardPath = "/localapi/v0/remote-management-port"

// inbound says how a peer's connection gets to the listener, from this node's
// status or the error of asking for it.
//
// In userspace-networking netstack has every port of the node's addresses, and
// the daemon's TUN flag being off is that mode. On a real TUN netstack keeps
// this one port only while the daemon sees the switch — and only a daemon
// built to. So the daemon is asked, every time: its answer moves with the
// switch, and one that cannot give it — an older daemon still running in Root
// Mode after the app was updated — reads as "none" rather than as a code on
// the screen that nobody could use.
func (m *remoteManager) inbound(self remoteSelf, err error) string {
	if err != nil {
		return remoteInboundUnknown
	}
	if !self.TUN {
		return remoteInboundNetstack
	}
	raw, err := m.api.get(remoteForwardPath)
	if errors.Is(err, ErrDaemonNotRunning) {
		return remoteInboundUnknown
	}
	if err != nil {
		return remoteInboundNone
	}
	var f struct {
		Port      int
		Forwarded bool
	}
	if json.Unmarshal(raw, &f) != nil || f.Port != RemotePort || !f.Forwarded {
		return remoteInboundNone
	}
	return remoteInboundNetstack
}
