package appctr

// setup.go — signing this device in with a key sent from a phone.
//
// A television has no camera and next to no keyboard. It draws a QR code; a
// phone running TailSocks reads it and sends an auth key — and, for a key of
// another control server, that server's address — over the local network. The
// television shows where the key came from and a check code the phone shows
// too, and uses the key only after the person in front of it says so.
//
// This file is the television's side. The phone's is setup_send.go; the code,
// the bundle and the sealing they share are setup_wire.go, and the choice of
// addresses setup_net.go.
//
// The exchange
//
// The code is "TSP1:" and, in base 36, a one-time X25519 public key (32
// bytes), a one-time secret (16), a port (2) and up to three IPv4 addresses
// (4 each): 89 to 101 capitals and digits, which a QR code holds in its
// compact alphanumeric mode — version 4 (33×33 modules) either way, with
// error correction M for one address and L for two or three.
//
// The phone makes a one-time key of its own and seals the bundle to the
// television's with HPKE (RFC 9180 base mode: X25519, HKDF-SHA256,
// ChaCha20-Poly1305 — crypto/hpke, nothing the module did not already have).
// Everything the code said, the secret included, goes in as HPKE's info, so
// every key of the exchange depends on the secret, on the television's key
// and on the addresses as the phone read them:
//
//	phone → television   "TSP1" | phone's key (32) | length (2) | sealed bundle
//	television → phone   kind (1) | tag (16)        held, taken, unsupported or closed
//	television → phone   kind (1) | tag (16)        confirmed, rejected, expired or cancelled
//
// info is meant for what both sides know publicly. A secret in it takes
// nothing from what HPKE promises — that holds for any info — and adds that
// the television's public key alone, should it ever get out, seals nothing
// this session opens. The standard library's HPKE has no pre-shared-key mode.
//
// The television's answers are not secret, only authentic: a tag is an HMAC,
// under a key exported from the HPKE context, over the kind and a digest of
// the request. The check code is six digits derived from the same context
// and the same digest, so it stands for this bundle from this phone. Someone
// who read the code off the screen but holds neither private key cannot
// predict those digits, and so cannot make a bundle of their own that shows
// the same ones — which a check code computed from what travels in the clear
// would let them do, at ten thousand tries for four digits.
//
// A session takes one bundle. It listens for ten minutes on the device's LAN
// addresses, each bound by itself, and stops listening the moment a bundle
// opens. That bundle waits three minutes for the person. A confirmed key is
// handed to the app once (SetupReceiveTakeKey) and is gone from here: the app
// puts it where a typed key goes and restarts the service, so it signs the
// device in by the one road a key has always taken.
//
// Threat model
//
// What this protects against:
//
//   - Listening on the LAN. The bundle is sealed to a key whose private half
//     exists only in this process for the life of the session, and the
//     phone's key is as short-lived, so a recording opens nothing, then or
//     later. What shows is that an exchange took place, the bundle's size
//     rounded up to 256 bytes, and how it ended.
//   - Injecting on the LAN. Without the code there is neither the public key
//     — it never travels over the network — nor the secret; a bundle made
//     without them does not open, and after setupMaxFailures of those the
//     session ends. The television's answers cannot be forged either, so no
//     one else can tell a phone that its bundle is held or confirmed.
//   - Replay. A request played back meets no listener, or the session that
//     already holds its bundle and does not answer it twice, or a later
//     session with another key and secret, where it is one more request that
//     does not open.
//   - A second sender. Whichever bundle opens first is the only one held; the
//     other sender is told "taken", or finds nothing listening. The
//     television names one address and one check code, and only the phone
//     whose bundle it holds shows that code.
//   - A code that points elsewhere. A phone sends only to private LAN
//     addresses and ports from 1024 up, whatever a code says; a session
//     listens only on such addresses, reads requests only from such
//     addresses, and never from one it listens on itself.
//
// What it does not:
//
//   - Someone who sees the code while it is valid — a photograph, a camera
//     on the screen, a mirrored display. They can send a key of their own
//     first. The television then shows their address and their check code,
//     and the right phone shows "taken" or "closed" and no code: the person
//     has to compare and reject. What they cannot do, even standing between
//     the two devices, is read the right phone's key.
//   - A code that is not this television's. A phone that reads one seals the
//     key to whoever made it, somewhere on the LAN. The phone shows where it
//     is about to send; nothing here can know which screen was scanned.
//   - A hostile app already on the phone or the television. One that reads
//     the screen or this process's memory has the session. One that cannot is
//     where a LAN attacker is, minus a connection from the addresses the
//     session listens on.
//   - A network that will not carry it: client isolation on the access point,
//     a guest network, separate VLANs. The phone reports "unreachable" or
//     "wrong_network"; nothing is loosened to get through.
//   - Denial of service. Anyone on the LAN can use up a session's failed
//     attempts; it costs a new code, never a key.
//   - The sender's choice of control server. It is shown before the person
//     confirms; a key for a stranger's server joins a stranger's network.
//
// Go cannot wipe everything it touched. The bundle's key is held as bytes and
// zeroed when it is handed over, refused or out of time; the private key and
// the secret are dropped and left to the collector, and the string the app is
// handed is the app's to keep short-lived. Nothing here writes to disk, and no
// log line carries a code, a secret, a key, a check code or a server address.
//
// The times are counted on the monotonic clock, which a suspended device does
// not advance: ten minutes are ten minutes awake. A television's wall clock is
// no better a witness — it jumps when the device first learns the time.

import (
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/netip"
	"slices"
	"sync"
	"syscall"
	"time"
)

const (
	// setupLifetime is how long a code is good for.
	setupLifetime = 10 * time.Minute
	// setupConfirmWindow is how long a bundle waits for the person at the
	// device. It is counted from the bundle's arrival, so one that comes in
	// the code's last second is not lost to it.
	setupConfirmWindow = 3 * time.Minute
	// setupTakeWindow is how long a confirmed key waits for the app to take
	// it. The app does that in the same breath as confirming.
	setupTakeWindow = 30 * time.Second
	// setupMaxFailures is how many requests may arrive and be refused before
	// the session ends. A connection that says nothing is not one of them: a
	// phone tries every address of the code and uses the first that answers.
	setupMaxFailures = 5
	// setupMaxConns is how many requests are read at once.
	setupMaxConns = 8
	// setupReadTimeout bounds a whole request; setupWriteTimeout an answer.
	setupReadTimeout  = 5 * time.Second
	setupWriteTimeout = 3 * time.Second
	// setupAddrCheck is how often a waiting session looks whether the
	// addresses it listens on are still this device's.
	setupAddrCheck = 5 * time.Second
)

// ─── The television's session ───────────────────────────────────────────────

// The states SetupReceiveStatus reports.
const (
	setupIdle      = "idle"      // no session
	setupWaiting   = "waiting"   // the code is up and the device listens
	setupReceived  = "received"  // a bundle waits for the person
	setupConfirmed = "confirmed" // the person said yes; the key waits for the app to take it
	setupReleased  = "released"  // the app took the key
	setupRejected  = "rejected"  // the person said no
	setupExpired   = "expired"   // nobody sent, or nobody answered, in time
	setupFailed    = "failed"    // see the reason
)

// Why a session failed.
const (
	setupNoLAN      = "no_lan_address"    // the device is on no local network
	setupNoListen   = "listen_failed"     // none of its addresses could be listened on
	setupTooMany    = "too_many_attempts" // setupMaxFailures refused requests
	setupNetChanged = "network_changed"   // the addresses in the code are no longer the device's
	setupDaemonStop = "daemon_stopped"    // the daemon was stopped or restarted meanwhile
	setupNotTaken   = "not_taken"         // confirmed, and the app never took the key
	setupWasStopped = "cancelled"         // SetupReceiveCancel; the session is forgotten with it
)

// setupConfig is a session's timing. The values are the constants above;
// tests shorten them.
type setupConfig struct {
	lifetime      time.Duration
	confirmWindow time.Duration
	takeWindow    time.Duration
	maxFailures   int
	readTimeout   time.Duration
	writeTimeout  time.Duration
	addrCheck     time.Duration
	tick          time.Duration
	locals        func() ([]setupLocal, bool)
}

func defaultSetupConfig() setupConfig {
	return setupConfig{
		lifetime:      setupLifetime,
		confirmWindow: setupConfirmWindow,
		takeWindow:    setupTakeWindow,
		maxFailures:   setupMaxFailures,
		readTimeout:   setupReadTimeout,
		writeTimeout:  setupWriteTimeout,
		addrCheck:     setupAddrCheck,
		tick:          time.Second,
		locals:        systemLocals,
	}
}

// setupHeld is the one bundle a session took.
type setupHeld struct {
	key       []byte // zeroed when it is used, refused or out of time
	keyPrefix string
	server    string
	name      string
	from      netip.Addr
	code      string
	digest    [sha256.Size]byte // of the request it came in
	conn      net.Conn          // the phone, waiting to hear what was decided
	verdict   chan byte         // carries one value, once
	told      bool
}

// setupRx is the television's side of one session.
type setupRx struct {
	cfg setupConfig
	id  string

	// Guarded by setupMu, like the pointer to the session itself.
	state    string
	reason   string
	priv     hpke.PrivateKey
	info     []byte
	text     string         // the code as drawn
	port     uint16         // the port in it
	addrs    []netip.Addr   // the addresses still listened on, and
	lns      []net.Listener // their listeners: open only while waiting
	conns    map[net.Conn]struct{}
	failures int
	until    time.Time // when the current state runs out
	held     *setupHeld
	outsider bool // a connection was turned away for where it came from

	wake chan struct{} // the network changed: look at the addresses now
	done chan struct{} // closed when nothing can happen to the session any more
}

// setupMu guards both sides' sessions. Nothing under it waits: no reading
// from the network, no listing of interfaces.
var (
	setupMu   sync.Mutex
	setupRecv *setupRx
)

// setupRxStatus is the JSON of the SetupReceive… calls. Every field is always
// there; which ones mean something depends on the state.
type setupRxStatus struct {
	State  string `json:"state"`
	Reason string `json:"reason"` // failed: why
	Error  string `json:"error"`  // why this call did nothing, "" when it did
	ID     string `json:"id"`

	// waiting
	Payload        string   `json:"payload"` // the text to draw
	Addresses      []string `json:"addresses"`
	Port           int      `json:"port"`
	FailedAttempts int      `json:"failedAttempts"`

	// waiting, received, confirmed: seconds left in that state
	ExpiresIn int `json:"expiresIn"`

	// received and after: the bundle, never its key
	From        string `json:"from"`
	SenderName  string `json:"senderName"`
	Code        string `json:"code"`
	KeyPrefix   string `json:"keyPrefix"`
	LoginServer string `json:"loginServer"`
}

func setupJSON(v any) string {
	out, _ := json.Marshal(v)
	return string(out)
}

// SetupReceiveStart opens a session on this device and returns its status:
// "waiting" with the text to draw as a QR code, or "failed" with the reason.
//
// While a session is open — waiting, holding a bundle, or holding a confirmed
// key — this returns that session and starts nothing, so a screen that is
// built twice does not throw away what the first one was given. A new code
// is SetupReceiveCancel and then this.
func SetupReceiveStart() string {
	return setupJSON(startSetupRx(defaultSetupConfig()))
}

func startSetupRx(cfg setupConfig) setupRxStatus {
	// Asked before the lock is taken: this goes to the kernel.
	locals, _ := cfg.locals()
	addrs := setupListenAddrs(locals)

	setupMu.Lock()
	defer setupMu.Unlock()
	now := time.Now()
	if old := setupRecv; old != nil {
		old.expireLocked(now)
		if !old.over() {
			return old.statusLocked(now)
		}
	}
	s := &setupRx{
		cfg:   cfg,
		conns: map[net.Conn]struct{}{},
		wake:  make(chan struct{}, 1),
		done:  make(chan struct{}),
	}
	setupRecv = s
	if reason := s.openLocked(addrs); reason != "" {
		s.state, s.reason = setupFailed, reason
		close(s.done)
		slog.Warn("Setup: no session", "reason", reason)
	}
	return s.statusLocked(time.Now())
}

// openLocked makes the session's key, secret and listeners and starts
// serving; it returns why not when it cannot.
func (s *setupRx) openLocked(addrs []netip.Addr) (reason string) {
	if len(addrs) == 0 {
		return setupNoLAN
	}
	priv, err := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if err != nil {
		return setupNoListen
	}
	secret := make([]byte, setupSecretLen)
	if _, err := rand.Read(secret); err != nil {
		return setupNoListen
	}
	lns, bound, port, err := listenSetup(addrs)
	if err != nil {
		slog.Warn("Setup: could not listen", "err", err)
		return setupNoListen
	}
	pub := priv.PublicKey().Bytes()
	text, raw := encodeSetupCode(pub, secret, port, bound)
	clear(secret)

	s.id = setupSessionID(pub)
	s.priv, s.info, s.text = priv, setupInfo(raw), text
	s.port, s.addrs, s.lns = port, bound, lns
	s.state, s.until = setupWaiting, time.Now().Add(s.cfg.lifetime)
	for _, ln := range lns {
		go s.accept(ln)
	}
	go s.watch()
	slog.Info("Setup: waiting for a phone", "session", s.id, "addresses", len(bound), "port", port)
	return ""
}

// listenSetup listens on every one of addrs, on one port they all had free.
//
// Each address is bound by itself. A wildcard listener would be simpler and
// would also answer on the tunnel's address, on a carrier's network, on
// whatever interface appears later; this one answers where a phone on the
// same local network can be, and nowhere else. An address that cannot be
// bound is left out of the code; the session needs one.
func listenSetup(addrs []netip.Addr) (lns []net.Listener, bound []netip.Addr, port uint16, err error) {
	for range 8 {
		lns, bound, port = nil, nil, 0
		taken := false
		for _, a := range addrs {
			ln, lerr := net.Listen("tcp4", netip.AddrPortFrom(a, port).String())
			if lerr != nil {
				err = lerr
				if port != 0 && errors.Is(lerr, syscall.EADDRINUSE) {
					// The port the first address drew is in use on this one.
					taken = true
					break
				}
				continue
			}
			if port == 0 {
				port = ln.Addr().(*net.TCPAddr).AddrPort().Port()
			}
			lns = append(lns, ln)
			bound = append(bound, a)
		}
		if !taken && len(lns) > 0 && port >= setupMinPort {
			return lns, bound, port, nil
		}
		for _, ln := range lns {
			ln.Close()
		}
		if !taken && len(lns) == 0 {
			break
		}
	}
	if err == nil {
		err = errors.New("no port")
	}
	return nil, nil, 0, err
}

func (s *setupRx) accept(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			// Out of descriptors, most likely. The listener is still there;
			// it is closed with the session.
			select {
			case <-s.done:
				return
			case <-time.After(200 * time.Millisecond):
			}
			continue
		}
		go s.serve(c)
	}
}

// serve reads one request and, if it holds the bundle the session keeps,
// stays to tell the phone what became of it.
func (s *setupRx) serve(c net.Conn) {
	defer c.Close()
	local, remote := setupConnAddrs(c)
	priv, info, ok := s.admit(c, local, remote)
	if !ok {
		return
	}
	defer s.forget(c)

	_ = c.SetDeadline(time.Now().Add(s.cfg.readTimeout))
	header, sealed, got, err := readSetupRequest(c)
	if err != nil {
		if got {
			s.refused(remote, "is not a setup request")
		}
		return
	}
	plain, x, err := openSetupRequest(priv, info, header, sealed)
	if err != nil {
		s.refused(remote, "does not open")
		return
	}
	// The answer gets its own time, however long the request took to come.
	_ = c.SetDeadline(time.Now().Add(s.cfg.writeTimeout))
	b, err := parseSetupBundle(plain)
	clear(plain)
	if err != nil {
		// Whoever sealed this read the code, so they are owed an answer —
		// and it does not count against the session.
		slog.Warn("Setup: a bundle this build cannot use", "session", s.id, "from", remote)
		_, _ = c.Write(x.reply(setupSaysUnsupported))
		return
	}
	h, answer := s.hold(c, remote, b, x)
	if answer != 0 {
		_, _ = c.Write(x.reply(answer))
	}
	if h == nil {
		return
	}

	// However long the person takes; every way a session ends answers this.
	verdict := <-h.verdict
	_ = c.SetDeadline(time.Now().Add(s.cfg.writeTimeout))
	_, _ = c.Write(x.reply(verdict))
}

func setupConnAddrs(c net.Conn) (local, remote netip.Addr) {
	if a, ok := c.LocalAddr().(*net.TCPAddr); ok {
		local = a.AddrPort().Addr().Unmap()
	}
	if a, ok := c.RemoteAddr().(*net.TCPAddr); ok {
		remote = a.AddrPort().Addr().Unmap()
	}
	return local, remote
}

// admit decides whether a connection is read at all, and hands out what
// opening its request takes. A session that already holds a bundle still
// reads the requests that were on their way, to tell their senders "taken".
func (s *setupRx) admit(c net.Conn, local, remote netip.Addr) (hpke.PrivateKey, []byte, bool) {
	setupMu.Lock()
	defer setupMu.Unlock()
	s.expireLocked(time.Now())
	if s.state != setupWaiting && s.state != setupReceived {
		return nil, nil, false
	}
	if !setupSourceOK(local, remote, s.addrs) {
		// Said once: whoever it is can come back as often as they like.
		if !s.outsider {
			s.outsider = true
			slog.Warn("Setup: a connection that is not from another device on the local network", "session", s.id, "from", remote)
		}
		return nil, nil, false
	}
	if len(s.conns) >= setupMaxConns {
		return nil, nil, false
	}
	s.conns[c] = struct{}{}
	return s.priv, s.info, true
}

func (s *setupRx) forget(c net.Conn) {
	setupMu.Lock()
	delete(s.conns, c)
	setupMu.Unlock()
}

// refused counts a request that was turned away, and ends the session at
// setupMaxFailures of them.
func (s *setupRx) refused(from netip.Addr, why string) {
	setupMu.Lock()
	defer setupMu.Unlock()
	if s.state != setupWaiting {
		return
	}
	s.failures++
	slog.Warn("Setup: a request that "+why, "session", s.id, "from", from, "refused", s.failures, "of", s.cfg.maxFailures)
	if s.failures >= s.cfg.maxFailures {
		s.endLocked(setupFailed, setupTooMany, setupSaysCancelled)
	}
}

// hold takes b as the session's bundle and says what to answer its sender:
// held, taken when the session has another, closed when it is over. The held
// request over again is a recording and gets no answer at all — there is
// then no second one to hand the phone in place of the first. Taking a bundle
// is what closes the listeners.
func (s *setupRx) hold(c net.Conn, from netip.Addr, b setupBundle, x *setupExchange) (h *setupHeld, answer byte) {
	setupMu.Lock()
	defer setupMu.Unlock()
	now := time.Now()
	s.expireLocked(now)
	if s.state != setupWaiting {
		clear(b.key)
		switch {
		case s.held != nil && s.held.digest == x.digest:
			return nil, 0
		case s.state == setupReceived:
			return nil, setupSaysTaken
		}
		return nil, setupSaysClosed
	}
	h = &setupHeld{
		key:       b.key,
		keyPrefix: setupKeyPrefix(b.key),
		server:    b.server,
		name:      b.name,
		from:      from,
		code:      x.code,
		digest:    x.digest,
		conn:      c,
		verdict:   make(chan byte, 1),
	}
	s.held = h
	s.state, s.until = setupReceived, now.Add(s.cfg.confirmWindow)
	s.closeListenersLocked()
	slog.Info("Setup: a key arrived and waits for confirmation", "session", s.id, "from", from, "with_server", b.server != "")
	return h, setupSaysHeld
}

func (s *setupRx) closeListenersLocked() {
	for _, ln := range s.lns {
		ln.Close()
	}
	s.lns = nil
}

// tellLocked sends the phone that waits its one answer.
func (s *setupRx) tellLocked(verdict byte) {
	if h := s.held; h != nil && !h.told {
		h.told = true
		h.verdict <- verdict
	}
}

func (s *setupRx) over() bool {
	switch s.state {
	case setupWaiting, setupReceived, setupConfirmed:
		return false
	}
	return true
}

// endLocked is the one way a session ends: the listeners close, requests
// being read are cut off, the key is zeroed, and the phone — if one waits —
// is told. What the bundle was, apart from the key, stays for the status.
func (s *setupRx) endLocked(state, reason string, verdict byte) {
	if s.over() {
		return
	}
	s.closeListenersLocked()
	for c := range s.conns {
		if s.held == nil || c != s.held.conn {
			c.Close()
		}
	}
	if s.held != nil {
		clear(s.held.key)
		s.held.key = nil
		s.tellLocked(verdict)
	}
	// Dropped, not zeroed: a request being opened may still read them.
	s.priv, s.info, s.text = nil, nil, ""
	s.state, s.reason = state, reason
	close(s.done)
	slog.Info("Setup: session over", "session", s.id, "state", state, "reason", reason)
}

// expireLocked ends a session whose current state has run out. Every entry
// point calls it, so a timer that fires late — a process that was frozen —
// never leaves a stale session answering. The clock is the monotonic one: a
// television's wall clock jumps when it first learns the time.
func (s *setupRx) expireLocked(now time.Time) {
	if s.over() || now.Before(s.until) {
		return
	}
	switch s.state {
	case setupWaiting, setupReceived:
		s.endLocked(setupExpired, "", setupSaysExpired)
	case setupConfirmed:
		s.endLocked(setupFailed, setupNotTaken, setupSaysCancelled)
	}
}

// watch ends the session when its time is up, and when the network under it
// changes: a listener bound to an address the device no longer has answers
// nobody, and the code that names it is stale.
func (s *setupRx) watch() {
	tick := time.NewTicker(s.cfg.tick)
	defer tick.Stop()
	next := time.Now().Add(s.cfg.addrCheck)
	for {
		look := false
		select {
		case <-s.done:
			return
		case <-s.wake:
			look = true
		case <-tick.C:
		}
		now := time.Now()
		if !now.Before(next) {
			look = true
		}
		setupMu.Lock()
		s.expireLocked(now)
		look = look && s.state == setupWaiting
		setupMu.Unlock()
		if !look {
			continue
		}
		next = now.Add(s.cfg.addrCheck)
		locals, known := s.cfg.locals()
		if !known {
			continue
		}
		present := make([]netip.Addr, 0, len(locals))
		for _, l := range locals {
			present = append(present, l.prefix.Addr())
		}
		setupMu.Lock()
		s.dropLostLocked(present)
		setupMu.Unlock()
	}
}

// dropLostLocked stops listening on the addresses the device no longer has,
// and ends the session when none is left. The code is not redrawn for the
// ones that remain: a phone tries them all.
func (s *setupRx) dropLostLocked(present []netip.Addr) {
	if s.state != setupWaiting {
		return
	}
	var addrs []netip.Addr
	var lns []net.Listener
	for i, a := range s.addrs {
		if slices.Contains(present, a) {
			addrs, lns = append(addrs, a), append(lns, s.lns[i])
			continue
		}
		s.lns[i].Close()
		slog.Info("Setup: an address of this device is gone", "session", s.id)
	}
	s.addrs, s.lns = addrs, lns
	if len(lns) == 0 {
		s.endLocked(setupFailed, setupNetChanged, setupSaysCancelled)
	}
}

func (s *setupRx) statusLocked(now time.Time) setupRxStatus {
	st := setupRxStatus{State: s.state, Reason: s.reason, ID: s.id, Addresses: []string{}}
	if s.state == setupWaiting {
		st.Payload, st.Port, st.FailedAttempts = s.text, int(s.port), s.failures
		for _, a := range s.addrs {
			st.Addresses = append(st.Addresses, a.String())
		}
	}
	if !s.over() {
		st.ExpiresIn = max(0, int((s.until.Sub(now)+time.Second-1)/time.Second))
	}
	if h := s.held; h != nil {
		st.From, st.SenderName, st.Code = h.from.String(), h.name, h.code
		st.KeyPrefix, st.LoginServer = h.keyPrefix, h.server
	}
	return st
}

// setupRxNow is the status of the session there is, or "idle".
func setupRxNow(callError string) setupRxStatus {
	if setupRecv == nil {
		return setupRxStatus{State: setupIdle, Error: callError, Addresses: []string{}}
	}
	st := setupRecv.statusLocked(time.Now())
	st.Error = callError
	return st
}

// SetupReceiveStatus is the session's state, for a screen to poll. It stays
// readable after the session ended, until the next start or cancel.
func SetupReceiveStatus() string {
	setupMu.Lock()
	defer setupMu.Unlock()
	if setupRecv != nil {
		setupRecv.expireLocked(time.Now())
	}
	return setupJSON(setupRxNow(""))
}

// SetupReceiveConfirm is the person saying yes to the bundle on the screen.
// The status becomes "confirmed", and the key waits setupTakeWindow for
// SetupReceiveTakeKey; left there, it is zeroed and the session fails with
// "not_taken". With nothing to confirm the status comes back as it is, with
// "error":"nothing_to_confirm".
func SetupReceiveConfirm() string {
	setupMu.Lock()
	defer setupMu.Unlock()
	s := setupRecv
	now := time.Now()
	if s != nil {
		s.expireLocked(now)
	}
	if s == nil || s.state != setupReceived {
		return setupJSON(setupRxNow("nothing_to_confirm"))
	}
	s.state, s.until = setupConfirmed, now.Add(s.cfg.takeWindow)
	// No request is read from here on: the session's own key has done its work.
	s.priv, s.info = nil, nil
	slog.Info("Setup: confirmed", "session", s.id)
	return setupJSON(setupRxNow(""))
}

// SetupReceiveTakeKey hands the confirmed key to the app, once: the key, or
// "" when there is none to take. The bridge's copy is zeroed, the phone is
// told "confirmed", and the status is "released" — with the bundle's control
// server still in it, never the key.
//
// What the app does with it is what it does with a key that was typed: the
// profile's control server first, if the bundle named one, then the key, then
// a restart of the service. That is deliberate. The bridge could carry the
// key into the next daemon start itself and spare it the preference file, and
// in a first version did, as the AuthKey of a copy of the start's options.
// But those options are compared and replaced by code that takes them for
// the app's: ApplySettings saw a key that had changed and logged in again
// with the profile's stored one, or put the app's keyless options in its
// place before the node had registered; the native TUN relaunch could bring
// the key back after it had been taken out. One road for a key is worth more
// than one file it is not written to — and the app can clear that preference
// as soon as the node is signed in.
func SetupReceiveTakeKey() string {
	setupMu.Lock()
	defer setupMu.Unlock()
	s := setupRecv
	if s != nil {
		s.expireLocked(time.Now())
	}
	if s == nil || s.state != setupConfirmed {
		return ""
	}
	key := string(s.held.key)
	s.endLocked(setupReleased, "", setupSaysConfirmed)
	return key
}

// SetupReceiveReject is the person saying no: the key is zeroed, the phone
// is told, the session is over. It also takes back a yes, for as long as the
// app has not taken the key.
func SetupReceiveReject() string {
	setupMu.Lock()
	defer setupMu.Unlock()
	s := setupRecv
	if s != nil {
		s.expireLocked(time.Now())
	}
	if s == nil || (s.state != setupReceived && s.state != setupConfirmed) {
		return setupJSON(setupRxNow("nothing_to_reject"))
	}
	s.endLocked(setupRejected, "", setupSaysRejected)
	return setupJSON(setupRxNow(""))
}

// SetupReceiveCancel is the screen that drew the code going away: a session
// that waits, or holds a bundle nobody has answered, ends, and a finished one
// is forgotten — the status is "idle" afterwards.
//
// A confirmed key is left alone: the screen may well go away between the yes
// and SetupReceiveTakeKey. Saying no after saying yes is SetupReceiveReject.
func SetupReceiveCancel() {
	setupMu.Lock()
	defer setupMu.Unlock()
	s := setupRecv
	if s == nil {
		return
	}
	s.expireLocked(time.Now())
	if s.state == setupConfirmed {
		return
	}
	s.endLocked(setupFailed, setupWasStopped, setupSaysCancelled)
	setupRecv = nil
}

// setupDaemonReleased ends a session that is still open when the bridge lets
// go of its daemon — a stop, a restart, a detach: nothing of the bridge's
// keeps listening past that. A confirmed key is left alone: nothing listens
// for it, and the app is about to take it.
func setupDaemonReleased() {
	setupMu.Lock()
	defer setupMu.Unlock()
	if s := setupRecv; s != nil && (s.state == setupWaiting || s.state == setupReceived) {
		s.endLocked(setupFailed, setupDaemonStop, setupSaysCancelled)
	}
}

// setupNetworkChanged makes a waiting session look at its addresses now
// rather than at its next check.
func setupNetworkChanged() {
	setupMu.Lock()
	s := setupRecv
	setupMu.Unlock()
	if s != nil {
		select {
		case s.wake <- struct{}{}:
		default:
		}
	}
}
