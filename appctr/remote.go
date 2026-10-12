package appctr

// remote.go — remote management: one device's TailSocks (typically a TV) is
// managed from another device (a phone) over the tailnet, after a pairing that
// was confirmed on the managed device's own screen. Experimental and OFF
// unless StartOptions.RemoteManagement is set.
//
// How it is put together
//
//   - The managed device listens on 127.0.0.1:RemotePort, HTTP/JSON under
//     /v1/. Nothing listens on a tailnet or LAN address. A peer's TCP
//     connection to <this node's tailnet address>:RemotePort is terminated by
//     the daemon's netstack, which dials the loopback listener itself and
//     records which peer that loopback connection stands for
//     (wgengine/netstack acceptTCP -> forwardTCP ->
//     proxymap.RegisterIPPortIdentity).
//   - Every request is identified by the daemon, not by anything the caller
//     says: LocalAPI whois on the connection's loopback source address returns
//     the node and its user (ipnlocal.WhoIs, the proxy-map branch). A pairing
//     is bound to the node's StableNodeID — never to an address, which can be
//     handed out again, nor to the node key, which rotates.
//   - A pairing is created only with the six-digit code this device shows:
//     remotePolicy.CodeTTL, single use, CodeAttempts wrong guesses kill it. The
//     pairing it creates is a separate thing: until revoked for an owner (an
//     untagged node of this node's own user), GuestTTL for anyone else.
//   - Pairing hands the caller a random 256-bit token; this device keeps only
//     its SHA-256. Every later request needs the node identity AND the token.
//   - What a paired device may do is exactly remoteRoutes, remoteSettings and
//     remoteActions. Settings live in the Kotlin app and are reached through
//     RemoteDelegate; the bridge never hands it a key or an action that is not
//     in those lists, so the app cannot widen them by accident.
//
// Never remote, whatever the class: stopping the daemon or the app (nothing
// could start it again), signing out, the login server, accounts and profiles,
// auth keys and every other credential, Root Mode, bind addresses and proxy
// passwords, extra daemon arguments, Serve/Funnel/Taildrive shares, anything
// that needs a permission dialog or a gesture on the device, the contents of
// files, and this feature's own switch, codes and pairings.
//
// Threat model
//
//	A tailnet member who was never paired
//	  is a real node, so whois resolves it, but it holds no pairing: it gets
//	  /v1/hello (app, API version, device class, host name, whether a code is
//	  showing) and may try /v1/pair. That needs the code on this device's
//	  screen: 10^6 values, CodeAttempts guesses per code across all callers,
//	  then the code is dead and a new one takes a person at the device — 5 in
//	  a million per code with the defaults. Every wrong guess is shown on the
//	  device and logged with the guesser's node name, which comes from whois.
//	  The tailnet policy can also close the port: the packet filter runs
//	  before netstack sees the connection.
//
//	A paired guest after expiry
//	  has an entry that is deleted the first time it is looked at after its
//	  deadline; its token then matches nothing. The deadline is counted on the
//	  boot-time clock, which no wall-clock change moves, and across reboots a
//	  pairing's remaining time can only shrink (remote_store.go).
//
//	Another app on a paired phone
//	  can send packets as that node — through the phone's SOCKS5 port, or
//	  simply because the phone runs TUN mode — so whois says "the phone". It
//	  has no token: that is in TailSocks' private storage. It is therefore an
//	  unpaired caller, and the code it would need is on the other device's
//	  screen. What it can do is spend the node's request budget.
//
//	A stolen phone
//	  that is unlocked with TailSocks usable IS the paired device: it gets its
//	  class's allow-list until the pairing is revoked here, or the phone's
//	  node is removed or expired in the admin console (no tailnet path, and
//	  whois reports Expired, which is refused). The allow-list is the bound:
//	  no sign-out, no stop, no account or credential, no file contents.
//
//	A compromised node elsewhere in the tailnet
//	  is a tailnet member who was never paired, and cannot arrive as a paired
//	  phone. WireGuard takes a peer's packets only from that peer's own
//	  addresses and routes; where a route does cover someone else's address —
//	  an exit node in use covers every address — the answer still goes to the
//	  node that really holds it (ipnlocal lookupPeerByIP: the exact address
//	  first), so a forged source never completes a TCP handshake. And it has
//	  no token either.
//
// NOT stopped
//
//   - The coordination server, or whoever controls the tailnet's node list.
//     It cannot use a pairing by forging the phone alone (no token), but it
//     can put its own node in the managed device's place, collect the token
//     the phone then sends, and present both. Tailnet Lock narrows this.
//   - An app on the managed device that binds 127.0.0.1:RemotePort first. It
//     receives the phone's requests, token included. It cannot replay them
//     here — a loopback caller has no identity, and through this device's own
//     SOCKS5 port it is this node itself, which is refused — but it can lie
//     to the phone, and the feature is down. RemoteStateJSON reports the bind.
//   - Denial of service by any node the policy lets reach the port: burning a
//     visible code's guesses, or a paired node's request budget from another
//     app on that phone. Rates and sizes are capped; nothing more.
//   - A paired device doing everything its class allows. There are two
//     classes and no per-device rights.
//   - Replay inside WireGuard is not addressed separately: the token is a
//     bearer for whoever also is the node.
//   - Someone at the device. Whoever can read the screen and press its
//     buttons can pair a device; that presence is the consent.
//
// Does a peer reach the endpoint? Read from the code, not measured on devices:
//
//   - userspace-networking — the default proxy mode, TUN with the hev engine,
//     Root Mode without its TUN: yes. netstack takes every packet for the
//     node's own addresses (ProcessLocalIPs = onlyNetstack,
//     cmd/tailscaled/netstack.go) and forwards a TCP port nothing else claims
//     to 127.0.0.1 with the peer's identity registered.
//   - native TUN engine (--tun=android-vpn, TUN's default): NO. There
//     ProcessLocalIPs is false and patch 19 only lets netstack keep the
//     replies to flows it dialed itself (CheckLocalTransportEndpoints); a SYN
//     for <tailnet address>:RemotePort goes to the VpnService device and the
//     host stack, where nothing listens on that address.
//   - Root Mode with tailscale0: NO, for the same reason — the kernel owns
//     the node's addresses and this listener is loopback-only.
//
// Listening on the tailnet address instead would not be an honest fix: the
// app excludes its own package from the VPN (TunVpnService.applyAppExclusions),
// so its replies would leave by the default network, and identity would come
// from a source address seen by a host socket rather than from the daemon's
// proxy map. The fix is a daemon patch that makes netstack claim RemotePort in
// those modes (shouldProcessInbound), after which forwarding and identity work
// as in userspace mode. It is not part of this change; until then
// RemoteStateJSON reports "inbound":"none" there, from the daemon's own TUN
// flag, so the screen can say so instead of showing a code nobody can use.

import (
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"time"

	"golang.org/x/net/netutil"
)

// RemotePort is the TCP port of the remote-management endpoint: fixed, so a
// phone needs nothing but the peer's address, below the kernel's ephemeral
// range (32768 and up) so no outgoing connection can be sitting on it, and
// clear of what the app and the daemon use — 1053 DNS proxy, 1055 and 48115
// SOCKS5, 5252 the daemon's web client, 8080 Web UI and the Taildrive service
// port, 33445 Taildrive proxy.
const RemotePort = 28115

// remoteAPIVersion is what /v1/hello reports; paths carry the major version.
const remoteAPIVersion = 1

// Limits of one exchange. The bodies are a few dozen bytes of JSON.
const (
	remoteMaxBody       = 4 << 10
	remoteMaxHeader     = 8 << 10
	remoteMaxConns      = 32
	remoteHeaderTimeout = 5 * time.Second
	remoteReadTimeout   = 10 * time.Second
	remoteWriteTimeout  = 20 * time.Second
	remoteIdleTimeout   = 30 * time.Second
	// remoteDelegateTimeout bounds one call into the app.
	remoteDelegateTimeout = 5 * time.Second
)

// RemoteDelegate is the app's half of remote management: the settings a paired
// device may read or change live in Kotlin, and so does everything that
// restarts the connection. Kotlin implements it and registers it with
// SetRemoteDelegate.
//
// The bridge enforces the allow-lists (remoteSettings, remoteActions in
// remote_policy.go) and validates every value before calling, so an
// implementation only ever sees the keys listed there — and must refuse, by
// throwing, any key or action it does not know: that keeps the two lists from
// drifting apart silently.
//
// Calls come on a Go thread, one at a time, and are abandoned after five
// seconds; anything slow (a daemon restart) must be scheduled, not awaited.
// The message of a thrown exception is shown to the paired device: keep it
// short and free of secrets.
type RemoteDelegate interface {
	// GetSetting returns the current value of a setting. Today's keys:
	// "accept_dns", "accept_routes", "exit_node_lan_access" — "true" or
	// "false"; "device_class" — "tv", "phone" or "tablet"; "app_version" —
	// the app's version name. The last two are asked once and remembered.
	GetSetting(key string) (string, error)
	// SetSetting stores the value where the app keeps it and applies it: one
	// of the three switches above with "true" or "false", or RemoteKeyExitNode
	// with a peer's StableNodeID, "" for none — an id the bridge has already
	// found among the daemon's exit nodes.
	SetSetting(key, value string) error
	// PerformAction carries out one named action. Today: "reconnect" —
	// restart the connection, as the service's own restart does.
	PerformAction(name string) error
}

// RemoteKeyExitNode is the key under which SetSetting receives an exit node
// chosen remotely. It is not one of the /v1/settings keys: the bridge accepts
// it only on /v1/exit-node, after checking the node against the daemon's list.
const RemoteKeyExitNode = "exit_node"

// remoteManager is the managed side: the listener, the pending pairing code,
// the store. One per process (remote); tests build their own.
type remoteManager struct {
	policy remotePolicy
	clock  remoteClock
	api    remoteLocalAPI
	// What the handlers read from the rest of the bridge, replaceable in tests.
	taildropDir func() string
	logTail     func(n int) []LogEntry

	global  *remoteLimiter // every request, before whois
	perNode *remoteLimiter // per calling node
	pairs   *remoteLimiter // pairing attempts per calling node

	// delegateSlot serialises calls into the app and delegateTimeout bounds
	// one; see callDelegate.
	delegateSlot    chan struct{}
	delegateTimeout time.Duration

	mu        sync.Mutex
	enabled   bool
	delegate  RemoteDelegate
	store     *remoteStore
	window    *remoteWindow
	spent     *remoteWindow // the last code that paired, until its own deadline
	outcome   *remoteOutcome
	listener  *remoteListener
	listenErr string
	rev       uint64
	self      remoteSelf
	selfAt    time.Time
	names     []remoteExitNode // the last exit-node list, for naming one
	namesAt   time.Time
	facts     map[string]string // read-only settings, cached per delegate
}

func newRemoteManager(policy remotePolicy, clock remoteClock, api remoteLocalAPI) *remoteManager {
	return &remoteManager{
		policy:          policy,
		clock:           clock,
		api:             api,
		taildropDir:     remoteTaildropDir,
		logTail:         remoteLogTail,
		global:          newRemoteLimiter(40, 80),
		perNode:         newRemoteLimiter(8, 24),
		pairs:           newRemoteLimiter(1, 3),
		delegateSlot:    make(chan struct{}, 1),
		delegateTimeout: remoteDelegateTimeout,
	}
}

var remote = newRemoteManager(remoteDefaultPolicy(), remoteSystemClock{}, remoteDaemonAPI{})

func remoteTaildropDir() string {
	if opt := getLastOptions(); opt != nil {
		return opt.TaildropDir
	}
	return ""
}

// SetRemoteDelegate registers the app's half of remote management; nil
// removes it, after which settings and actions answer "unavailable".
func SetRemoteDelegate(d RemoteDelegate) { remote.setDelegate(d) }

func (m *remoteManager) setDelegate(d RemoteDelegate) {
	m.mu.Lock()
	m.delegate = d
	m.facts = nil
	m.rev++
	m.mu.Unlock()
}

// remoteProfileKey is the pattern of a state directory's base name that may
// name a store file: the app's account ids.
var remoteProfileKey = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// remoteStorePath is where a run keeps its pairings: files/remote/<profile>.json
// beside the daemon's socket. Not under the state directory — in Root Mode
// that one is root's — and one file per profile, because a StableNodeID means
// something only within one control server.
func remoteStorePath(opt *StartOptions) string {
	if opt == nil || opt.SocketPath == "" {
		return ""
	}
	profile := filepath.Base(opt.StatePath)
	if !remoteProfileKey.MatchString(profile) {
		profile = "default"
	}
	return filepath.Join(filepath.Dir(opt.SocketPath), "remote", profile+".json")
}

// remoteListenAddr is where the endpoint listens: this device only.
func remoteListenAddr() string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(RemotePort))
}

// remoteApply brings the endpoint in line with the options of a run that is
// starting — Start and AttachExternal: the switch, and the pairings of the
// profile the daemon is started for.
func remoteApply(opt *StartOptions) {
	if opt == nil {
		return
	}
	remote.apply(opt.RemoteManagement, remoteStorePath(opt), remoteListenAddr())
}

// remoteSwitch follows the switch alone, for settings applied to a daemon that
// keeps running (ApplySettings). The pairings stay those of the profile that
// daemon runs: options naming another profile take effect with the restart
// they cause, and until then must not put its pairings in front of this node.
func remoteSwitch(opt *StartOptions) {
	if opt == nil {
		return
	}
	remote.apply(opt.RemoteManagement, "", remoteListenAddr())
}

// remoteRelease closes the listener when the bridge lets go of a daemon. The
// pending code and the store stay: a relaunch (native TUN, a restart) is not
// a reason to make someone ask for a new code.
func remoteRelease() {
	if remote.release() {
		slog.Info("Remote management: listener closed")
	}
}

// remoteListener is the endpoint while it is up. Both halves are closed on
// the way down: the server for the connections it holds, the listener itself
// because the port must be free again before this returns — a restart binds
// it straight away.
type remoteListener struct {
	srv *http.Server
	ln  net.Listener
}

func (l *remoteListener) close() {
	_ = l.srv.Close()
	_ = l.ln.Close()
}

// release closes the listener and reports whether there was one.
func (m *remoteManager) release() bool {
	m.mu.Lock()
	l := m.listener
	m.listener = nil
	m.selfAt = time.Time{}
	m.rev++
	m.mu.Unlock()
	if l == nil {
		return false
	}
	l.close()
	return true
}

// configure records whether the endpoint is wanted and where its pairings
// are, closes it when it is not, and reports whether a listener is missing.
func (m *remoteManager) configure(enabled bool, storePath string) (needListener bool) {
	m.mu.Lock()
	if storePath != "" && (m.store == nil || m.store.path != storePath) {
		// Another profile: its own pairings, and no code carried over.
		m.store = openRemoteStore(storePath, m.clock)
		m.closeWindowLocked("cancelled")
		m.spent = nil
	}
	was := m.enabled
	m.enabled = enabled && m.store != nil
	var old *remoteListener
	if !m.enabled {
		old, m.listener = m.listener, nil
		m.listenErr = ""
		m.closeWindowLocked("cancelled")
		m.spent = nil
	}
	needListener = m.enabled && m.listener == nil
	now := m.enabled
	m.rev++
	m.mu.Unlock()

	if old != nil {
		old.close()
	}
	if was && !now {
		slog.Info("Remote management: off")
	}
	return needListener
}

// apply switches the endpoint on or off and points it at a store file.
func (m *remoteManager) apply(enabled bool, storePath, listenAddr string) {
	if !m.configure(enabled, storePath) {
		return
	}
	ln, err := net.Listen("tcp4", listenAddr)
	if err != nil {
		// Most likely another app holds the port; see "NOT stopped" above.
		slog.Error("Remote management: cannot listen", "addr", listenAddr, "err", err)
		m.mu.Lock()
		m.listenErr = err.Error()
		m.rev++
		m.mu.Unlock()
		return
	}
	if m.serve(ln) {
		slog.Info("Remote management: listening", "addr", ln.Addr().String())
	}
}

// serve runs the endpoint on ln until it is released; false, with ln closed,
// when the endpoint was switched off or started meanwhile.
func (m *remoteManager) serve(ln net.Listener) bool {
	srv := &http.Server{
		Handler:           m.handler(),
		ReadHeaderTimeout: remoteHeaderTimeout,
		ReadTimeout:       remoteReadTimeout,
		WriteTimeout:      remoteWriteTimeout,
		IdleTimeout:       remoteIdleTimeout,
		MaxHeaderBytes:    remoteMaxHeader,
		// net/http reports malformed requests and aborted handshakes here, with
		// whatever the client sent; none of it belongs in the app's log.
		ErrorLog: remoteDiscardLog,
	}
	l := &remoteListener{srv: srv, ln: ln}
	m.mu.Lock()
	if m.listener != nil || !m.enabled {
		m.mu.Unlock()
		_ = ln.Close()
		return false
	}
	m.listener = l
	m.listenErr = ""
	m.rev++
	m.mu.Unlock()
	go func() {
		if err := srv.Serve(netutil.LimitListener(ln, remoteMaxConns)); err != nil && err != http.ErrServerClosed {
			slog.Error("Remote management: listener stopped", "err", err)
		}
		m.mu.Lock()
		if m.listener == l {
			m.listener = nil
			m.rev++
		}
		m.mu.Unlock()
	}()
	return true
}

// ─── The managed device's screen ────────────────────────────────────────────

// remoteReply marshals v, or says that it could not.
func remoteReply(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"ok":false,"error":"cannot encode the answer"}`
	}
	return string(b)
}

type remotePolicyView struct {
	OwnerNeedsCode bool  `json:"ownerNeedsCode"`
	AllowGuests    bool  `json:"allowGuests"`
	CodeSeconds    int64 `json:"codeSeconds"`
	CodeAttempts   int   `json:"codeAttempts"`
	GuestSeconds   int64 `json:"guestSeconds"`
	OwnerSeconds   int64 `json:"ownerSeconds"`
	MaxPairings    int   `json:"maxPairings"`
}

type remoteStateView struct {
	// Available: a run has named the data directory, so there is a store.
	// False only before the first start in this process.
	Available bool `json:"available"`
	Enabled   bool `json:"enabled"`
	Listening bool `json:"listening"`
	Port      int  `json:"port"`
	// Error is why the port could not be opened, when it could not.
	Error string `json:"error,omitempty"`
	// Inbound says whether a peer's connection can reach the listener in the
	// daemon's current mode: "netstack" yes, "none" no (native TUN, Root Mode
	// with its TUN), "unknown" while the daemon does not answer.
	Inbound string `json:"inbound"`
	// Delegate: the app has registered its RemoteDelegate.
	Delegate bool `json:"delegate"`
	Paired   int  `json:"paired"`
	// Pairing is the code on show and what happened to it so far.
	Pairing *remoteWindowView `json:"pairing,omitempty"`
	// LastPairing is how the previous code ended, for a toast.
	LastPairing *remoteOutcome   `json:"lastPairing,omitempty"`
	Policy      remotePolicyView `json:"policy"`
	// Rev changes whenever anything above, the paired list or the action log
	// may have changed; a screen that polls can skip the rest on an equal Rev.
	Rev int64 `json:"rev"`
}

// RemoteStateJSON is what the managed device's own screen shows: whether the
// endpoint is on and reachable, the pending pairing code with the attempts
// made on it, and the policy numbers:
//
//	{"available","enabled","listening","port","error","inbound","delegate",
//	 "paired","rev",
//	 "pairing":{"code","qr","left","expires","attemptsLeft",
//	            "lastWrong":{"name","login","nodeId","time"}},
//	 "lastPairing":{"result","time","name","login","nodeId","class"},
//	 "policy":{"ownerNeedsCode","allowGuests","codeSeconds","codeAttempts",
//	           "guestSeconds","ownerSeconds","maxPairings"}}
//
// "pairing" is absent while no code is on show, "lastPairing" until one has
// ended. "inbound" is "netstack" when peers can reach the endpoint, "none" when
// the daemon's mode keeps them out (native TUN, Root Mode with its TUN) and
// "unknown" while the daemon does not answer. "rev" changes whenever this, the
// paired list or the action log may have: poll this, re-read those on a change.
func RemoteStateJSON() string { return remoteReply(remote.state()) }

func (m *remoteManager) state() remoteStateView {
	self, selfErr := m.selfIdentity()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.expireLocked()
	v := remoteStateView{
		Available: m.store != nil,
		Enabled:   m.enabled,
		Listening: m.listener != nil,
		Port:      RemotePort,
		Error:     m.listenErr,
		Inbound:   "unknown",
		Delegate:  m.delegate != nil,
		Pairing:   m.windowViewLocked(),
		Policy: remotePolicyView{
			OwnerNeedsCode: m.policy.OwnerNeedsCode,
			AllowGuests:    m.policy.AllowGuests,
			CodeSeconds:    int64(m.policy.CodeTTL / time.Second),
			CodeAttempts:   m.policy.CodeAttempts,
			GuestSeconds:   int64(m.policy.GuestTTL / time.Second),
			OwnerSeconds:   int64(m.policy.OwnerTTL / time.Second),
			MaxPairings:    m.policy.MaxPairings,
		},
		LastPairing: m.outcome,
		Rev:         int64(m.rev),
	}
	if selfErr == nil {
		v.Inbound = "netstack"
		if self.TUN {
			v.Inbound = "none"
		}
	}
	if m.store != nil {
		v.Paired = len(m.store.data.Pairings)
	}
	return v
}

type remoteCodeView struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	// Code is the six digits; QR is the same code with this node's
	// StableNodeID and name, for RemoteParseQR on the phone.
	Code string `json:"code,omitempty"`
	QR   string `json:"qr,omitempty"`
	// Expires is by this device's clock, in Unix seconds; Left is the same
	// moment as seconds from now, which is what a countdown should use.
	Expires int64 `json:"expires,omitempty"`
	Left    int64 `json:"left,omitempty"`
}

// RemoteStartPairing puts a fresh pairing code on show, replacing any other,
// and returns {"ok","code","qr","expires","left"}; {"ok":false,"error"} when
// remote management is off or this node is not connected. The screen should
// call RemoteCancelPairing when it is left.
func RemoteStartPairing() string { return remoteReply(remote.startPairing()) }

// RemoteCancelPairing withdraws the pending code, if there is one.
func RemoteCancelPairing() { remote.cancelPairing() }

func (m *remoteManager) cancelPairing() {
	m.mu.Lock()
	m.closeWindowLocked("cancelled")
	m.mu.Unlock()
}

type remotePairedView struct {
	NodeID string `json:"nodeId"`
	Name   string `json:"name"`
	Login  string `json:"login"`
	Class  string `json:"class"`
	// Created and LastUsed are Unix seconds by this device's clock; LastUsed
	// is 0 until the device has made an authenticated request.
	Created  int64 `json:"created"`
	LastUsed int64 `json:"lastUsed"`
	// Left is the seconds the pairing has left, -1 for "until revoked";
	// Expires is the same as a Unix time, 0 for "until revoked".
	Left    int64 `json:"left"`
	Expires int64 `json:"expires"`
}

// RemotePairedJSON lists the devices that may manage this one, oldest first:
// [{"nodeId","name","login","class","created","lastUsed","left","expires"}].
// Expired pairings are dropped before the list is made.
func RemotePairedJSON() string { return remoteReply(remote.paired()) }

func (m *remoteManager) paired() []remotePairedView {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []remotePairedView{}
	if m.store == nil {
		return out
	}
	m.expireLocked()
	now := m.clock.Now().Unix()
	for i := range m.store.data.Pairings {
		p := &m.store.data.Pairings[i]
		v := remotePairedView{
			NodeID: p.NodeID, Name: p.Name, Login: p.Login, Class: p.Class,
			Created: p.Created, LastUsed: p.LastUsed, Left: m.store.left(p),
		}
		if v.Left >= 0 {
			v.Expires = now + v.Left
		}
		out = append(out, v)
	}
	return out
}

// RemoteRevoke removes one pairing by the device's StableNodeID and reports
// whether there was one. The device's token is worthless from then on.
func RemoteRevoke(nodeID string) bool { return remote.revoke(nodeID) }

func (m *remoteManager) revoke(nodeID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return false
	}
	p := m.store.find(nodeID)
	if p == nil {
		return false
	}
	ev := remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "revoked", OK: true}
	m.store.remove(nodeID)
	m.auditLocked(ev)
	m.saveLocked()
	slog.Info("Remote management: pairing revoked", "device", ev.Name, "node", ev.NodeID)
	return true
}

// RemoteRevokeAll removes every pairing and returns how many there were.
func RemoteRevokeAll() int { return remote.revokeAll() }

func (m *remoteManager) revokeAll() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return 0
	}
	n := len(m.store.data.Pairings)
	if n == 0 {
		return 0
	}
	m.store.data.Pairings = nil
	m.auditLocked(remoteEvent{Op: "revoked_all", Detail: strconv.Itoa(n), OK: true})
	m.saveLocked()
	slog.Info("Remote management: all pairings revoked", "count", n)
	return n
}

// RemoteActionsJSON returns what paired devices changed and what was refused,
// newest first, at most a hundred entries:
// [{"time","nodeId","name","class","op","detail","ok"}] — op is one of paired,
// pair_failed, pair_locked, unpaired, revoked, revoked_all, expired, reset,
// exit_node, setting, action, file_deleted, denied. Looking is not recorded.
func RemoteActionsJSON() string { return remoteReply(remote.actions()) }

func (m *remoteManager) actions() []remoteEvent {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []remoteEvent{}
	if m.store != nil {
		a := m.store.data.Audit
		for i := len(a) - 1; i >= 0; i-- {
			out = append(out, a[i])
		}
	}
	return out
}

// auditLocked records ev with the current time. Caller holds m.mu and saves.
func (m *remoteManager) auditLocked(ev remoteEvent) {
	if m.store == nil {
		return
	}
	ev.Time = m.clock.Now().Unix()
	m.store.audit(ev)
	m.rev++
}

// saveLocked writes the store, logging rather than returning a failure for
// the callers that have nothing better to do with it.
func (m *remoteManager) saveLocked() bool {
	if m.store == nil {
		return false
	}
	if err := m.store.save(); err != nil {
		slog.Error("Remote management: cannot save the pairings", "err", err)
		return false
	}
	return true
}

// expireLocked drops the pairings whose time is up and the code whose time is
// up. Caller holds m.mu.
func (m *remoteManager) expireLocked() {
	if w := m.window; w != nil && m.windowDeadLocked(w) {
		m.closeWindowLocked("expired")
	}
	if m.store == nil {
		return
	}
	gone := m.store.expire()
	for _, p := range gone {
		m.auditLocked(remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "expired", OK: true})
		slog.Info("Remote management: pairing expired", "device", p.Name, "node", p.NodeID)
	}
	if len(gone) > 0 {
		m.saveLocked()
	}
}
