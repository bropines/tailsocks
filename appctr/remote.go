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
//   - Who is calling is asked of the daemon, never of the caller: LocalAPI
//     whois on the connection's loopback source address returns the node and
//     its user (ipnlocal.WhoIs, the proxy-map branch). That answer is
//     necessary and never sufficient — remote_auth.go says why — so nothing is
//     granted on it alone. A pairing is bound to the node's StableNodeID,
//     never to an address, which can be handed out again, nor to the node
//     key, which rotates.
//   - A pairing is created only with the six-digit code this device shows —
//     for every device, this account's own included: remotePolicy.CodeTTL,
//     single use, CodeAttempts wrong guesses kill it. The pairing it creates
//     is a separate thing: until revoked for an owner (an untagged node of
//     this node's own user), GuestTTL for anyone else.
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
// files, the log — it quotes keys, sign-in links, proxy credentials and paths
// in more forms than a filter can be trusted to know — and this feature's own
// switch, codes and pairings.
//
// Threat model
//
//	A tailnet member who was never paired
//	  is a real node, so whois resolves it, but it holds no pairing: it gets
//	  /v1/hello — the same few facts for everyone, nothing about the caller —
//	  and may try /v1/pair. That needs the code on this device's screen: 10^6
//	  values, CodeAttempts guesses per code across all callers, then the code
//	  is dead and a new one takes a person at the device — 5 in a million per
//	  code with the defaults. Any node the tailnet policy lets reach the port
//	  can spend those guesses, and keep spending them: that denies pairing, it
//	  does not get round it, and every wrong guess is shown on the device and
//	  logged under the name whois gave. The policy can also close the port:
//	  the packet filter runs before netstack sees the connection.
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
//	A process on the managed device
//	  reaches the listener with no tailnet in between. Ordinarily whois has
//	  nothing for it and its connection is closed before it takes a slot
//	  (remoteGate). But it can come to share a source port with a connection
//	  the daemon is forwarding for a peer to another loopback port, and is
//	  then taken for that peer (remote_auth.go). As that peer it still has
//	  neither of the things identity does not supply: the peer's token, and
//	  the code. It can spend that peer's request budget and connection slots.
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
//   - A hostile app on the managed device that goes to the trouble. If it
//     binds 127.0.0.1:RemotePort before the bridge does, the phone's requests
//     come to it, token included; and by sharing a source port with one of
//     that phone's forwarded connections (above) it can later present the
//     token here as the phone. Both halves take luck or patience, neither is
//     prevented. RemoteStateJSON reports a port that could not be opened. The
//     token is a bearer; a proof of possession in its place would close the
//     first half, and a daemon that kept the destination in its proxy map the
//     second.
//   - Denial of service by any node the policy lets reach the port — burning
//     a visible code's guesses, filling its own share of connections — by
//     another app on a paired phone spending that node's request budget, and
//     by a process on the device that opens connections faster than the
//     daemon can be asked whose they are. Rates, sizes, connections per node
//     and waiting lookups are capped; nothing more.
//   - A paired device doing everything its class allows. There are two
//     classes and no per-device rights.
//   - Replay inside WireGuard is not addressed separately.
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
	remoteMaxBody   = 4 << 10
	remoteMaxHeader = 8 << 10
	// Connections: so many in all, so many for one node, and so many waiting
	// to be told who they are (remoteGate).
	remoteMaxConns        = 32
	remoteMaxConnsPerNode = 4
	remoteMaxLookups      = 64
	remoteHeaderTimeout   = 5 * time.Second
	remoteReadTimeout     = 10 * time.Second
	remoteWriteTimeout    = 20 * time.Second
	remoteIdleTimeout     = 10 * time.Second
	// A call into the app: how long a request waits for the app to be free —
	// a call that has not started by then was never made — and how long for
	// the answer of one that has. Together under the phone's own timeout.
	remoteDelegateWait   = 3 * time.Second
	remoteDelegateAnswer = 7 * time.Second
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
// Calls come on a Go thread, one at a time. A call cannot be taken back once
// it is made, so return quickly: anything slow (a daemon restart) must be
// scheduled, not awaited. A request waits a few seconds for the answer and is
// then told "pending" — never "failed" — and nothing else is asked of the app
// until the call returns. The message of a thrown exception is shown to the
// paired device: keep it short and free of secrets.
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
	// What the handlers read from the rest of the bridge, and where a pairing
	// code comes from; replaceable in tests.
	taildropDir func() string
	newCode     func() (string, error)

	global  *remoteLimiter // every request, before whois
	perNode *remoteLimiter // per calling node
	pairs   *remoteLimiter // pairing attempts per calling node
	asks    *remoteLimiter // requests for a permission's page per calling node

	// delegateSlot serialises calls into the app; delegateWait is how long a
	// request waits for its turn and delegateAnswer how long for the answer of
	// a call that has started. See callDelegate. reportWait and reportAnswer
	// are the same two for the app's report, which a status answer carries
	// when it is to be had and never waits long for.
	delegateSlot   chan struct{}
	delegateWait   time.Duration
	delegateAnswer time.Duration
	reportWait     time.Duration
	reportAnswer   time.Duration

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
	// facts are the read-only settings as the registered delegate told them;
	// delegateGen counts registrations, so an answer from an earlier one is
	// not kept.
	facts       map[string]string
	delegateGen uint64
	// report is the app's last account of itself (remote_app.go), reused for
	// remoteReportTTL: a status answer is what a phone polls.
	report   *remoteAppReport
	reportAt time.Time
}

func newRemoteManager(policy remotePolicy, clock remoteClock, api remoteLocalAPI) *remoteManager {
	return &remoteManager{
		policy:         policy,
		clock:          clock,
		api:            api,
		taildropDir:    remoteTaildropDir,
		newCode:        remoteNewCode,
		global:         newRemoteLimiter(40, 80),
		perNode:        newRemoteLimiter(8, 24),
		pairs:          newRemoteLimiter(1, 3),
		asks:           newRemoteLimiter(remoteAskRate, remoteAskBurst),
		delegateSlot:   make(chan struct{}, 1),
		delegateWait:   remoteDelegateWait,
		delegateAnswer: remoteDelegateAnswer,
		reportWait:     remoteReportWait,
		reportAnswer:   remoteReportAnswer,
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
// removes it, after which settings and actions answer "unavailable". The two
// read-only facts — device_class, app_version — are asked for right away, on a
// thread of the bridge's own, and remembered: /v1/hello is answered to callers
// that have proved nothing yet, and must never reach into the app for them.
func SetRemoteDelegate(d RemoteDelegate) {
	remote.setDelegate(d)
	if d != nil {
		go remote.loadFacts()
	}
}

func (m *remoteManager) setDelegate(d RemoteDelegate) {
	m.mu.Lock()
	m.delegate = d
	m.delegateGen++
	m.facts = nil
	m.report, m.reportAt = nil, time.Time{}
	m.rev++
	m.mu.Unlock()
}

// loadFacts asks the app for the read-only settings and remembers them.
func (m *remoteManager) loadFacts() {
	for _, s := range remoteSettings {
		if !s.writable {
			m.fact(s.key)
		}
	}
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
	// And the managing side: its connections ran through that daemon's proxy.
	remoteClient.closeIdle()
}

// remoteListener is the endpoint while it is up. Both halves are closed on
// the way down: the server for the connections it holds, the listener itself
// because the port must be free again before this returns — a restart binds
// it straight away.
type remoteListener struct {
	srv  *http.Server
	ln   net.Listener
	gate *remoteGate
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
	if m.configure(enabled, storePath) {
		m.listen(listenAddr)
	}
}

// listen opens the port and serves on it.
func (m *remoteManager) listen(listenAddr string) {
	ln, err := net.Listen("tcp4", listenAddr)
	if err != nil {
		m.mu.Lock()
		// Two runs can get here together; the one that lost the port to the
		// other has nothing to report. Otherwise another app most likely
		// holds it; see "NOT stopped" above.
		if m.listener == nil {
			slog.Error("Remote management: cannot listen", "addr", listenAddr, "err", err)
			m.listenErr = err.Error()
			m.rev++
		}
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
	gate := newRemoteGate(ln, m)
	l.gate = gate
	m.listener = l
	m.listenErr = ""
	m.rev++
	m.mu.Unlock()
	go func() {
		if err := srv.Serve(gate); err != nil && err != http.ErrServerClosed {
			slog.Error("Remote management: listener stopped", "err", err)
		}
		_ = gate.Close()
		m.mu.Lock()
		if m.listener == l {
			m.listener = nil
			m.rev++
		}
		m.mu.Unlock()
	}()
	return true
}

// remoteGate stands between the socket and the HTTP server and lets a
// connection through only once the daemon has said whose it is.
//
// net/http gives every accepted connection a slot and a few seconds to speak.
// Without the gate a process on this device — which reaches the port without
// any tailnet in between — could open as many silent connections as there are
// slots and keep paired devices out. Here a connection the daemon does not
// attribute to a peer is closed before it costs a slot, and a peer gets
// remoteMaxConnsPerNode of the remoteMaxConns, so no single node — and no
// local process borrowing one node's name, see remote_auth.go — can hold them
// all. The lookup is the daemon's whois, which waits a moment itself for a
// forwarded connection to be registered; remoteMaxLookups bounds how many may
// be waiting, and one that would exceed it is closed unasked.
type remoteGate struct {
	ln      net.Listener
	m       *remoteManager
	out     chan net.Conn
	done    chan struct{}
	once    sync.Once
	lookups chan struct{}

	mu    sync.Mutex
	total int
	nodes map[string]int
}

func newRemoteGate(ln net.Listener, m *remoteManager) *remoteGate {
	g := &remoteGate{
		ln:      ln,
		m:       m,
		out:     make(chan net.Conn),
		done:    make(chan struct{}),
		lookups: make(chan struct{}, remoteMaxLookups),
		nodes:   map[string]int{},
	}
	go g.run()
	return g
}

func (g *remoteGate) run() {
	for {
		c, err := g.ln.Accept()
		if err != nil {
			_ = g.Close()
			return
		}
		select {
		case g.lookups <- struct{}{}:
			go g.admit(c)
		default:
			_ = c.Close()
		}
	}
}

// admit asks who the connection is and hands it on, or closes it.
func (g *remoteGate) admit(c net.Conn) {
	defer func() { <-g.lookups }()
	caller, err := g.m.whois(c.RemoteAddr().String())
	if err != nil {
		_ = c.Close()
		return
	}
	if self, err := g.m.selfIdentity(); err != nil || self.NodeID == caller.NodeID {
		_ = c.Close()
		return
	}
	if !g.take(caller.NodeID) {
		_ = c.Close()
		return
	}
	gc := &remoteGatedConn{Conn: c, gate: g, node: caller.NodeID}
	select {
	case g.out <- gc:
	case <-g.done:
		_ = gc.Close()
	}
}

func (g *remoteGate) take(node string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.total >= remoteMaxConns || g.nodes[node] >= remoteMaxConnsPerNode {
		return false
	}
	g.total++
	g.nodes[node]++
	return true
}

func (g *remoteGate) give(node string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.total--
	if g.nodes[node]--; g.nodes[node] <= 0 {
		delete(g.nodes, node)
	}
}

// Accept is net/http's side: the next connection that was let through.
func (g *remoteGate) Accept() (net.Conn, error) {
	select {
	case c := <-g.out:
		return c, nil
	case <-g.done:
		return nil, net.ErrClosed
	}
}

func (g *remoteGate) Close() error {
	g.once.Do(func() { close(g.done) })
	return g.ln.Close()
}

func (g *remoteGate) Addr() net.Addr { return g.ln.Addr() }

// remoteGatedConn gives its place back when it is closed.
type remoteGatedConn struct {
	net.Conn
	gate *remoteGate
	node string
	once sync.Once
}

func (c *remoteGatedConn) Close() error {
	c.once.Do(func() { c.gate.give(c.node) })
	return c.Conn.Close()
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
	AllowGuests  bool  `json:"allowGuests"`
	CodeSeconds  int64 `json:"codeSeconds"`
	CodeAttempts int   `json:"codeAttempts"`
	GuestSeconds int64 `json:"guestSeconds"`
	OwnerSeconds int64 `json:"ownerSeconds"`
	MaxPairings  int   `json:"maxPairings"`
}

type remoteStateView struct {
	// Available: a run has named the data directory, so there is a store.
	// False only before the first start in this process.
	Available bool `json:"available"`
	Enabled   bool `json:"enabled"`
	Listening bool `json:"listening"`
	Port      int  `json:"port"`
	// Error is why the port could not be opened; absent while it is open.
	Error string `json:"error,omitempty"`
	// Unsaved: the last change to the pairings could not be written to disk
	// and is being retried. Nothing more is granted than the lists show, but a
	// change may be lost with the app's process.
	Unsaved bool `json:"unsaved,omitempty"`
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
//	{"available","enabled","listening","port","error","unsaved","inbound",
//	 "delegate","paired","rev",
//	 "pairing":{"code","qr","left","expires","attemptsLeft",
//	            "lastWrong":{"name","login","nodeId","time"}},
//	 "lastPairing":{"result","time","name","login","nodeId","class"},
//	 "policy":{"allowGuests","codeSeconds","codeAttempts","guestSeconds",
//	           "ownerSeconds","maxPairings"}}
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
		Inbound:   "unknown",
		Delegate:  m.delegate != nil,
		Pairing:   m.windowViewLocked(),
		Policy: remotePolicyView{
			AllowGuests:  m.policy.AllowGuests,
			CodeSeconds:  int64(m.policy.CodeTTL / time.Second),
			CodeAttempts: m.policy.CodeAttempts,
			GuestSeconds: int64(m.policy.GuestTTL / time.Second),
			OwnerSeconds: int64(m.policy.OwnerTTL / time.Second),
			MaxPairings:  m.policy.MaxPairings,
		},
		LastPairing: m.outcome,
		Rev:         int64(m.rev),
	}
	if !v.Listening {
		// An error is about a port that is not open; while one is, whatever
		// an earlier attempt ran into is history.
		v.Error = m.listenErr
	}
	if selfErr == nil {
		v.Inbound = "netstack"
		if self.TUN {
			v.Inbound = "none"
		}
	}
	if m.store != nil {
		v.Paired = len(m.store.data.Pairings)
		v.Unsaved = m.store.unsaved
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

// remoteRevoked is what RemoteRevoke and RemoteRevokeAll return.
type remoteRevoked struct {
	// OK: the pairings are gone and that is on disk.
	OK bool `json:"ok"`
	// Removed is how many pairings were removed from the list in memory.
	Removed int `json:"removed"`
	// Error is "not_paired" when there was nothing to remove, or "not_saved":
	// the devices are refused from now on and the write is being retried, but
	// until it succeeds they would be back with the app's next start — say so.
	Error string `json:"error,omitempty"`
}

// RemoteRevoke removes one pairing by the device's StableNodeID; the device's
// token is worthless from then on. It returns {"ok","removed","error"}: ok
// only when the removal is also on disk — see remoteRevoked for "not_saved".
func RemoteRevoke(nodeID string) string { return remoteReply(remote.revoke(nodeID)) }

func (m *remoteManager) revoke(nodeID string) remoteRevoked {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return remoteRevoked{Error: "not_paired"}
	}
	p := m.store.find(nodeID)
	if p == nil {
		return remoteRevoked{Error: "not_paired"}
	}
	ev := remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "revoked", OK: true}
	m.store.remove(nodeID)
	m.auditLocked(ev)
	slog.Info("Remote management: pairing revoked", "device", ev.Name, "node", ev.NodeID)
	if !m.saveRemovalLocked() {
		return remoteRevoked{Removed: 1, Error: "not_saved"}
	}
	return remoteRevoked{OK: true, Removed: 1}
}

// RemoteRevokeAll removes every pairing and returns {"ok","removed","error"}
// as RemoteRevoke does; an empty list is ok with nothing removed.
func RemoteRevokeAll() string { return remoteReply(remote.revokeAll()) }

func (m *remoteManager) revokeAll() remoteRevoked {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return remoteRevoked{OK: true}
	}
	n := len(m.store.data.Pairings)
	if n == 0 && !m.store.owed {
		return remoteRevoked{OK: true}
	}
	m.store.data.Pairings = nil
	if n > 0 {
		m.auditLocked(remoteEvent{Op: "revoked_all", Detail: strconv.Itoa(n), OK: true})
		slog.Info("Remote management: all pairings revoked", "count", n)
	}
	if !m.saveRemovalLocked() {
		return remoteRevoked{Removed: n, Error: "not_saved"}
	}
	return remoteRevoked{OK: true, Removed: n}
}

// RemoteActionsJSON returns what paired devices changed and what was refused,
// newest first, at most a hundred entries:
// [{"time","nodeId","name","class","op","detail","ok"}] — op is one of paired,
// pair_failed, pair_locked, unpaired, revoked, revoked_all, expired, reset,
// exit_node, setting, action, file_deleted, denied. Looking is not recorded.
// A change the app had not answered when its request gave up is logged as not
// ok with "(no answer from the app yet)", and again when the app does answer,
// as "(applied late)" or "(failed late)".
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

// saveLocked writes the store after a grant or a line of the log, and reports
// whether it is on disk; a grant that is not is the caller's to take back.
func (m *remoteManager) saveLocked() bool {
	if m.store == nil {
		return false
	}
	if err := m.store.save(false); err != nil {
		slog.Error("Remote management: cannot save the pairings", "err", err)
		m.rev++
		return false
	}
	return true
}

// saveRemovalLocked writes the store after something was taken away, by force
// if need be (remoteStore.save), and reports whether what was removed is gone
// from the disk too. When it is not, the list in memory still holds — the
// device is refused — and the write is retried from expireLocked.
func (m *remoteManager) saveRemovalLocked() bool {
	if m.store == nil {
		return false
	}
	if err := m.store.save(true); err != nil {
		slog.Error("Remote management: a removal could not be written to disk; it holds until the app restarts, and is being retried", "err", err)
		m.rev++
		return false
	}
	return true
}

// expireLocked drops the pairings whose time is up and the code whose time is
// up, and retries a write that failed. Nearly every entry point passes through
// here. Caller holds m.mu.
func (m *remoteManager) expireLocked() {
	if w := m.window; w != nil && m.windowDeadLocked(w) {
		m.closeWindowLocked("expired")
	}
	if m.store == nil {
		return
	}
	if was := m.store.unsaved; was {
		if m.store.flush(); !m.store.unsaved {
			m.rev++
		}
	}
	gone := m.store.expire()
	for _, p := range gone {
		m.auditLocked(remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "expired", OK: true})
		slog.Info("Remote management: pairing expired", "device", p.Name, "node", p.NodeID)
	}
	if len(gone) > 0 {
		m.saveRemovalLocked()
	}
}
