package appctr

// remote_auth.go — who is calling the remote-management endpoint.
//
// The answer always comes from the daemon. The listener is on loopback, so the
// source address of a connection is 127.0.0.1:<port> — the socket the daemon's
// netstack opened to forward a peer's connection — and LocalAPI whois maps that
// socket back to the peer through the proxy map (ipnlocal.WhoIs). netstack
// registers the mapping before it completes the peer's handshake
// (forwardTCP), so by the time a request has been read the lookup cannot be
// early; the mapping goes when the connection does.
//
// What that answer is worth: it is NECESSARY and never SUFFICIENT.
//
//   - The proxy map is keyed by the daemon's local address and port alone,
//     not by where that socket is connected to. The kernel will give a second
//     connection the same source port when it goes to a different destination
//     port. So a process on this device that connects straight to this
//     listener can find itself on the source port of a connection the daemon
//     is forwarding for some peer to another loopback port — a web interface,
//     a proxy — and whois then names that peer. Ordinarily such a process has
//     no mapping and is nobody; it cannot be relied on to stay nobody.
//   - On the peer's side, every app that can send tailnet packets — through
//     its SOCKS5 port, or because the device runs TUN mode — is that node.
//   - A process that goes through this device's own SOCKS5 port arrives as
//     this node itself (the daemon's address table includes the self node)
//     and is refused for that.
//
// So nothing is granted on whois alone. It decides which pairing a request is
// measured against, what class a pairing gets, and whose budget a request is
// counted on; what opens a door is the token issued to that node, and what
// issues a token is the code on this device's screen.

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"net/url"
	"strings"
	"time"
)

// remoteLocalAPI is the daemon as the endpoint needs it: GET a LocalAPI path,
// return the body of a 200. A test substitutes a fake daemon.
type remoteLocalAPI interface {
	get(path string) ([]byte, error)
}

type remoteDaemonAPI struct{}

func (remoteDaemonAPI) get(path string) ([]byte, error) {
	if !IsRunning() {
		return nil, ErrDaemonNotRunning
	}
	return doLocalRequest("GET", path, nil)
}

// remoteCaller is the node behind a request, as whois describes it.
type remoteCaller struct {
	NodeID string // StableNodeID
	UserID int64
	Login  string
	Name   string
	// Tagged: the node runs under ACL tags, so its user is not a person.
	Tagged bool
	// Shared: the node is in the netmap through node sharing — another
	// tailnet's.
	Shared bool
}

// remoteSelf is this node, from the daemon's status.
type remoteSelf struct {
	NodeID  string
	UserID  int64
	Tagged  bool
	Name    string
	DNSName string
	// TUN is the daemon's own flag for "not userspace-networking"; see the
	// note on reachability in remote.go.
	TUN          bool
	BackendState string
	Version      string
	ExitNodeID   string
	ExitOnline   bool
	Health       []string
}

var (
	errRemoteNoCaller = errors.New("the caller is not a tailnet node")
	errRemoteNoSelf   = errors.New("this node has no identity yet")
)

// remoteSelfTTL is how long this node's identity is reused. Every request
// needs it, the status call behind it is not free, and what it guards against
// — this node changing user or gaining tags — does not happen within seconds.
const remoteSelfTTL = 5 * time.Second

// whois resolves the remote address of a connection to the node behind it.
func (m *remoteManager) whois(remoteAddr string) (remoteCaller, error) {
	ap, err := netip.ParseAddrPort(remoteAddr)
	if err != nil || !ap.Addr().Unmap().IsLoopback() || ap.Port() == 0 {
		// The listener is loopback-only; anything else means it was rebound
		// without this code being reconsidered. A tailnet address here would
		// be resolved by address alone, with no proxy map behind it.
		return remoteCaller{}, errRemoteNoCaller
	}
	q := url.Values{}
	q.Set("proto", "tcp")
	q.Set("addr", netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port()).String())
	raw, err := m.api.get("/localapi/v0/whois?" + q.Encode())
	if err != nil {
		return remoteCaller{}, errRemoteNoCaller
	}
	// apitype.WhoIsResponse: Node is a tailcfg.Node, UserProfile a
	// tailcfg.UserProfile.
	var w struct {
		Node *struct {
			StableID            string
			Name                string
			User                int64
			Sharer              int64
			Tags                []string
			Expired             bool
			UnsignedPeerAPIOnly bool
			ComputedName        string
			Hostinfo            *struct {
				Hostname   string
				ShareeNode bool
			}
		}
		UserProfile *struct {
			LoginName string
		}
	}
	if err := json.Unmarshal(raw, &w); err != nil || w.Node == nil || w.Node.StableID == "" {
		return remoteCaller{}, errRemoteNoCaller
	}
	n := w.Node
	if n.Expired || n.UnsignedPeerAPIOnly {
		// An expired key or a node tailnet lock has not signed should have no
		// path here at all; if one does, it still is nobody.
		return remoteCaller{}, errRemoteNoCaller
	}
	c := remoteCaller{
		NodeID: n.StableID,
		UserID: n.User,
		Tagged: len(n.Tags) > 0,
		Shared: n.Sharer != 0,
		Name:   n.ComputedName,
	}
	if n.Hostinfo != nil {
		c.Shared = c.Shared || n.Hostinfo.ShareeNode
		if c.Name == "" {
			c.Name = n.Hostinfo.Hostname
		}
	}
	if c.Name == "" {
		c.Name, _, _ = strings.Cut(n.Name, ".")
	}
	c.Name = remoteClean(c.Name, 64)
	if w.UserProfile != nil {
		c.Login = remoteClean(w.UserProfile.LoginName, 128)
	}
	return c, nil
}

// selfIdentity returns this node as the daemon's status has it, reusing an
// answer for remoteSelfTTL.
func (m *remoteManager) selfIdentity() (remoteSelf, error) {
	m.mu.Lock()
	if !m.selfAt.IsZero() && time.Since(m.selfAt) < remoteSelfTTL {
		s := m.self
		m.mu.Unlock()
		return s, nil
	}
	m.mu.Unlock()

	raw, err := m.api.get("/localapi/v0/status?peers=false")
	if err != nil {
		return remoteSelf{}, err
	}
	s, err := remoteSelfFromStatus(raw)
	if err != nil {
		return remoteSelf{}, err
	}
	m.mu.Lock()
	m.self, m.selfAt = s, time.Now()
	m.mu.Unlock()
	return s, nil
}

// remoteSelfFromStatus reads this node out of /localapi/v0/status
// (ipnstate.Status). A node without a StableNodeID — not logged in, no network
// map yet — is an error: nobody can be compared with it.
func remoteSelfFromStatus(raw []byte) (remoteSelf, error) {
	var st struct {
		Version      string
		TUN          bool
		BackendState string
		Health       []string
		Self         *struct {
			ID       string
			UserID   int64
			HostName string
			DNSName  string
			Tags     []string
		}
		ExitNodeStatus *struct {
			ID     string
			Online bool
		}
	}
	if err := json.Unmarshal(raw, &st); err != nil {
		return remoteSelf{}, err
	}
	if st.Self == nil || st.Self.ID == "" {
		return remoteSelf{}, errRemoteNoSelf
	}
	s := remoteSelf{
		NodeID:       st.Self.ID,
		UserID:       st.Self.UserID,
		Tagged:       len(st.Self.Tags) > 0,
		Name:         remoteClean(st.Self.HostName, 64),
		DNSName:      remoteClean(strings.TrimSuffix(st.Self.DNSName, "."), 255),
		TUN:          st.TUN,
		BackendState: st.BackendState,
		Version:      remoteClean(st.Version, 64),
		Health:       st.Health,
	}
	if st.ExitNodeStatus != nil {
		s.ExitNodeID = st.ExitNodeStatus.ID
		s.ExitOnline = st.ExitNodeStatus.Online
	}
	return s, nil
}

// classOf is what the caller may become on this node.
//
// Owner means the same person: an untagged node of this node's own user, not
// shared in — the test the daemon itself applies before it lets a peer debug
// it (peerapi IsSelfUntagged) or use its web client. A tagged node's user is
// whoever created it, not who runs it, and a shared-in node belongs to another
// tailnet, so neither can be an owner; and when this node is tagged it has no
// person to share with anyone, so nobody is its owner.
func (p remotePolicy) classOf(c remoteCaller, self remoteSelf) remoteClass {
	if c.NodeID == "" || self.NodeID == "" || c.NodeID == self.NodeID {
		return remoteNobody
	}
	if !self.Tagged && !c.Tagged && !c.Shared && c.UserID != 0 && c.UserID == self.UserID {
		return remoteOwner
	}
	if p.AllowGuests {
		return remoteGuest
	}
	return remoteNobody
}

// remoteHostIsAddress reports whether a request's Host header is a bare IP
// address, with or without a port. The phone dials the node's tailnet address,
// so a name here is something else: a browser sent to this port by a web page
// whose own name was re-pointed at the device.
func remoteHostIsAddress(host string) bool {
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
	_, err := netip.ParseAddr(host)
	return err == nil
}
