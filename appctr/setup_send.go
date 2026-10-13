package appctr

// setup_send.go — the phone's side of setup.go: reading the code a
// television drew and sending it a key.
//
// The phone goes straight to the television's LAN address when it can, and
// through the relay the code names when it cannot (setup_relay.go). The
// tailnet has no part in either, since the television is not in one yet. A
// send runs with or without a daemon and is not stopped with it.

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"
)

// What SetupSend and SetupSendStatus report, besides "idle" and the
// television's own "confirmed", "rejected" and "expired".
const (
	setupSending  = "sending"  // connecting, or waiting for the television's answer
	setupAwaiting = "awaiting" // the television holds the bundle; the person there decides

	setupCancelled = "cancelled" // the television's session ended before a decision
	setupLost      = "lost"      // the connection dropped first; the television knows how it ended

	setupTaken        = "taken"             // another bundle got there first
	setupUnsupported  = "unsupported"       // the television's build cannot use this bundle
	setupUnreachable  = "unreachable"       // no connection, by any road the code offers
	setupWrongNetwork = "wrong_network"     // no connection, the phone is on none of the television's networks, and the code has no relay
	setupRelayDown    = "relay_unavailable" // no connection on the LAN, and this phone does not get to the relay
	setupClosed       = "closed"            // the television is there but no longer listens: the code is used up, expired or cancelled
	setupNoAnswer     = "no_answer"         // sent, and nothing the television could have written came back

	setupBadPayload = "invalid_payload" // not a code this build reads
	setupBadKey     = "invalid_key"     // empty, too long, or not printable ASCII without spaces
	setupBadServer  = "invalid_server"  // not an http(s) address
	setupBusy       = "busy"            // an earlier send is still sending or awaiting
)

const (
	// setupDialTimeout bounds the connection attempt to each LAN address;
	// they are tried together. setupDialTimeoutShort is the same when the
	// relay can follow: a television on this network answers in milliseconds,
	// and every second spent here is one the person waits before the road
	// that will work is tried.
	setupDialTimeout      = 4 * time.Second
	setupDialTimeoutShort = 2500 * time.Millisecond
	// setupAnswerTimeout bounds sending the request and reading the answer
	// the television gives at once; setupAnswerTimeoutShort, again, when the
	// relay can follow.
	setupAnswerTimeout      = 8 * time.Second
	setupAnswerTimeoutShort = 4 * time.Second
	// setupRelayAnswerTimeout is the same through the relay, where the first
	// packets may still be finding their way.
	setupRelayAnswerTimeout = 10 * time.Second
	// setupVerdictWait is how long the phone listens for the decision: as
	// long as the television can take, and a little.
	setupVerdictWait = setupConfirmWindow + 30*time.Second
)

type setupSendConfig struct {
	dialTimeout        time.Duration
	dialTimeoutShort   time.Duration
	answerTimeout      time.Duration
	answerTimeoutShort time.Duration
	relayDial          time.Duration
	relayAnswer        time.Duration
	verdictWait        time.Duration
	locals             func() ([]setupLocal, bool)
	relay              setupRelay // nil: codes are followed on the LAN alone
}

func defaultSetupSendConfig() setupSendConfig {
	return setupSendConfig{
		dialTimeout:        setupDialTimeout,
		dialTimeoutShort:   setupDialTimeoutShort,
		answerTimeout:      setupAnswerTimeout,
		answerTimeoutShort: setupAnswerTimeoutShort,
		relayDial:          setupRelayDialTimeout,
		relayAnswer:        setupRelayAnswerTimeout,
		verdictWait:        setupVerdictWait,
		locals:             systemLocals,
		relay:              tailcatRelay{},
	}
}

// setupParsed is the JSON of SetupParse.
type setupParsed struct {
	OK bool `json:"ok"`
	// Error is "" or why not: "not_a_setup_code" (some other text),
	// "unsupported_version" (a code of a newer build), "malformed" (a code,
	// but damaged or naming addresses outside a local network).
	Error string `json:"error"`
	ID    string `json:"id"` // the session's name, as the television shows it
	// Addresses and Port are the television's on its local network. A
	// television that has none draws a code without them — then Relay is
	// true, or the code would not have been drawn.
	Addresses []string `json:"addresses"`
	Port      int      `json:"port"`
	// Relay says the code can also be followed through the relay, from any
	// network.
	Relay bool `json:"relay"`
}

// SetupParse reads a scanned code without sending anything, so a screen can
// say where a key would go before it goes.
func SetupParse(payload string) string {
	res := setupParsed{Addresses: []string{}}
	c, err := parseSetupCode(payload)
	switch {
	case err == nil:
		res.OK, res.ID, res.Port, res.Relay = true, setupSessionID(c.key), int(c.port), c.relay != 0
		for _, a := range c.addrs {
			res.Addresses = append(res.Addresses, a.String())
		}
	case errors.Is(err, errSetupNotCode):
		res.Error = "not_a_setup_code"
	case errors.Is(err, errSetupVersion):
		res.Error = "unsupported_version"
	default:
		res.Error = "malformed"
	}
	return setupJSON(res)
}

// setupTxStatus is the JSON of SetupSend and SetupSendStatus.
type setupTxStatus struct {
	State string `json:"state"`
	// Path is the road: "lan" or "relay". While "sending", the one being
	// tried; after that, the one the television answered by — or, when it did
	// not answer, the last one tried. "" before any was.
	Path string `json:"path"`
	// Code is the check code, from "awaiting" on: the television shows the
	// same six digits for this bundle and for no other.
	Code string `json:"code"`
	// Address is where the bundle went on the LAN, "ip:port"; "" through the
	// relay, where there is no address worth showing.
	Address string `json:"address"`
}

// setupTx is the phone's side of one send. Guarded by setupMu.
type setupTx struct {
	state   string
	path    string
	code    string
	address string
	stop    context.CancelFunc // ends the send and closes its connection
}

var setupSend *setupTx

// SetupSend seals an auth key — with the control server it belongs to, or ""
// to leave the television's as it is, and a name for this phone — to the
// television whose code was scanned, and sends it: over the LAN first, and
// through the relay if the code names one and the LAN did not get an answer.
// It returns once the television has answered or cannot be reached. On the
// LAN that is a moment, through the relay a few seconds; a code without a
// relay is given up within about twelve seconds, one with a relay within
// about thirty, most of it the relay's own time. SetupSendStatus says
// meanwhile which road is being tried.
//
//	"awaiting"          the television holds the bundle and shows Code; poll
//	                    SetupSendStatus for "confirmed", "rejected",
//	                    "expired", "cancelled" or "lost"
//	"taken"             it already holds someone else's
//	"unsupported"       its build cannot use this bundle
//	"closed"            it no longer listens: the code is used up, expired or cancelled
//	"unreachable"       no connection: not on the LAN, and — if the code has a
//	                    relay — no television answering through it either
//	"wrong_network"     no connection, this phone is on none of its networks,
//	                    and the code has no relay
//	"relay_unavailable" no connection on the LAN, and this phone does not get
//	                    to the relay: no way out, or the relay is blocked
//	"no_answer"         the request went out and no answer of the television's
//	                    came back
//	"invalid_payload", "invalid_key", "invalid_server", "busy"
//	                    nothing was sent
//
// The key is not kept: it is sealed, sent and zeroed here.
func SetupSend(payload, authKey, loginServer, senderName string) string {
	return setupJSON(setupSendWith(defaultSetupSendConfig(), payload, authKey, loginServer, senderName))
}

func setupSendWith(cfg setupSendConfig, payload, authKey, loginServer, senderName string) setupTxStatus {
	c, err := parseSetupCode(payload)
	if err != nil {
		return setupTxStatus{State: setupBadPayload}
	}
	b := setupBundle{
		key:    []byte(strings.TrimSpace(authKey)),
		server: strings.TrimSpace(loginServer),
		name:   setupCleanName(senderName),
	}
	defer clear(b.key)
	if !setupKeyOK(b.key) {
		return setupTxStatus{State: setupBadKey}
	}
	if !setupServerOK(b.server) {
		return setupTxStatus{State: setupBadServer}
	}
	plain, err := encodeSetupBundle(b)
	if err != nil {
		return setupTxStatus{State: setupBadKey}
	}
	defer clear(plain)

	ctx, cancel := context.WithCancel(context.Background())
	tx := &setupTx{state: setupSending, stop: cancel}
	setupMu.Lock()
	if cur := setupSend; cur != nil && (cur.state == setupSending || cur.state == setupAwaiting) {
		setupMu.Unlock()
		cancel()
		return setupTxStatus{State: setupBusy}
	}
	setupSend = tx
	setupMu.Unlock()
	slog.Info("Setup: sending a key", "session", setupSessionID(c.key), "addresses", len(c.addrs), "relay", c.relay != 0, "with_server", b.server != "")

	conn, x, st := setupDeliver(ctx, cfg, c, plain, func(path string) {
		setupMu.Lock()
		tx.path = path
		setupMu.Unlock()
	})

	setupMu.Lock()
	defer setupMu.Unlock()
	if setupSend != tx {
		// SetupSendCancel came while this was on its way.
		cancel()
		return setupTxStatus{State: setupIdle}
	}
	tx.state, tx.path, tx.code, tx.address = st.State, st.Path, st.Code, st.Address
	slog.Info("Setup: sent", "state", st.State, "path", st.Path)
	if st.State == setupAwaiting {
		go tx.await(cfg, conn, x)
	} else {
		cancel()
	}
	return st
}

// setupDeliver takes one sealed bundle to the television — over the LAN,
// then through the relay — and reads what it says at once. On "awaiting" the
// connection comes back open: the decision arrives over it, and it is closed
// when ctx is cancelled. trying is told each road as it is taken.
//
// The LAN comes first whenever the code has an address: it needs nobody
// else. The relay follows only if no answer of the television's came back
// that way — no connection, or a connection to something that is not it, as
// when the phone's own network has that address too. Whatever carries the
// television's tag is final by either road. The request is the same on both,
// sealed once, so a television that got it over the LAN and was not heard
// recognises it when it comes again (hold).
func setupDeliver(ctx context.Context, cfg setupSendConfig, c *setupCode, plain []byte, trying func(path string)) (net.Conn, *setupExchange, setupTxStatus) {
	request, x, err := sealSetupRequest(c, plain)
	if err != nil {
		// A key no television generates: a point of small order.
		return nil, nil, setupTxStatus{State: setupBadPayload}
	}
	relay := c.relay != 0 && cfg.relay != nil

	var lan setupTxStatus
	if len(c.addrs) > 0 {
		trying(setupViaLAN)
		dial, answer := cfg.dialTimeout, cfg.answerTimeout
		if relay {
			dial, answer = cfg.dialTimeoutShort, cfg.answerTimeoutShort
		}
		conn, answered, st := setupOverLAN(ctx, cfg, c, request, x, dial, answer)
		if answered || !relay || ctx.Err() != nil {
			return conn, x, st
		}
		lan = st
	} else if !relay {
		// A code for the relay alone, in a build that has none.
		return nil, nil, setupTxStatus{State: setupRelayDown}
	}

	trying(setupViaRelay)
	st := setupTxStatus{Path: setupViaRelay}
	dctx, cancel := context.WithTimeout(ctx, cfg.relayDial)
	keys, err := setupRelayKeysOf(c.key, c.secret())
	var conn net.Conn
	if err == nil {
		conn, err = cfg.relay.dial(dctx, keys, c.relay)
	}
	cancel()
	switch {
	case err == nil:
		if conn, answered, state := setupExchangeOver(ctx, conn, request, x, cfg.relayAnswer); answered {
			st.State = state
			if state == setupAwaiting {
				st.Code = x.code
				return conn, x, st
			}
			return nil, nil, st
		}
		st.State = setupNoAnswer
	case lan.State == setupNoAnswer:
		// Something on the LAN took the request and said nothing of the
		// television's; the relay adds nothing to that.
		return nil, nil, lan
	case errors.Is(err, errSetupRelayNobody):
		// The relay is there and no television behind it. If something on the
		// LAN turned the connection down as well, that was the television.
		st.State = setupUnreachable
		if lan.State == setupClosed {
			st.State = setupClosed
		}
	default:
		st.State = setupRelayDown
	}
	return nil, nil, st
}

// setupOverLAN tries the code's LAN addresses. answered says the television's
// own tag came back, which makes the status final.
func setupOverLAN(ctx context.Context, cfg setupSendConfig, c *setupCode, request []byte, x *setupExchange, dial, answer time.Duration) (net.Conn, bool, setupTxStatus) {
	st := setupTxStatus{Path: setupViaLAN}
	conn, state := setupDial(ctx, cfg, c, dial)
	if conn == nil {
		st.State = state
		return nil, false, st
	}
	st.Address = conn.RemoteAddr().String()
	conn, answered, state := setupExchangeOver(ctx, conn, request, x, answer)
	st.State = state
	if state == setupAwaiting {
		st.Code = x.code
	}
	return conn, answered, st
}

// setupExchangeOver sends the request down conn and reads the television's
// answer to it. answered is false — and the state "no_answer" — unless what
// came back carries the television's tag. The connection is returned open
// only for "awaiting", and then closes with ctx; otherwise it is closed here.
//
// No check code comes out of this before the television has said, under its
// tag, that it holds the bundle: a code shown for a request that may never
// have arrived is one that somebody who read the code and can see the phone's
// screen could make a bundle of their own to match.
func setupExchangeOver(ctx context.Context, conn net.Conn, request []byte, x *setupExchange, timeout time.Duration) (net.Conn, bool, string) {
	unhook := context.AfterFunc(ctx, func() { conn.Close() })
	_ = conn.SetDeadline(time.Now().Add(timeout))
	reply := make([]byte, setupReplyLen)
	if _, err := conn.Write(request); err == nil {
		if _, err = io.ReadFull(conn, reply); err == nil {
			// Anything but the television's own tag is no answer at all.
			switch kind, ok := x.check(reply, setupSaysHeld, setupSaysTaken, setupSaysUnsupported, setupSaysClosed); {
			case !ok:
			case kind == setupSaysHeld:
				return conn, true, setupAwaiting
			case kind == setupSaysTaken:
				unhook()
				conn.Close()
				return nil, true, setupTaken
			case kind == setupSaysUnsupported:
				unhook()
				conn.Close()
				return nil, true, setupUnsupported
			case kind == setupSaysClosed:
				unhook()
				conn.Close()
				return nil, true, setupClosed
			}
		}
	}
	unhook()
	conn.Close()
	return nil, false, setupNoAnswer
}

// setupDial connects to the first of the code's addresses that answers. With
// none it says why, as far as that can be told.
func setupDial(ctx context.Context, cfg setupSendConfig, c *setupCode, timeout time.Duration) (net.Conn, string) {
	type result struct {
		conn net.Conn
		err  error
	}
	dialCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan result, len(c.addrs))
	d := net.Dialer{Timeout: timeout}
	for _, a := range c.addrs {
		go func() {
			conn, err := d.DialContext(dialCtx, "tcp4", netip.AddrPortFrom(a, c.port).String())
			results <- result{conn, err}
		}()
	}
	var won net.Conn
	refused := false
	for range c.addrs {
		r := <-results
		switch {
		case r.err != nil:
			// A reset is the same news as a refusal: a listener that closed
			// while this connection was being set up.
			refused = refused || errors.Is(r.err, syscall.ECONNREFUSED) || errors.Is(r.err, syscall.ECONNRESET)
		case won == nil:
			// The others are given up; a connection that says nothing does
			// not count against the television's session.
			won = r.conn
			cancel()
		default:
			r.conn.Close()
		}
	}
	if won != nil {
		return won, ""
	}
	if refused {
		// Something at that address turned the connection down, so the
		// network carried it: the television is there and not listening —
		// or, on a network that is not the television's, somebody else's
		// device is. With a relay in the code the caller finds out which.
		return nil, setupClosed
	}
	locals, listed := cfg.locals()
	if same, known := setupSameNetwork(locals, listed, c.addrs); known && !same {
		return nil, setupWrongNetwork
	}
	return nil, setupUnreachable
}

// await reads the decision, however long the person at the television takes.
func (tx *setupTx) await(cfg setupSendConfig, conn net.Conn, x *setupExchange) {
	defer tx.stop()
	defer conn.Close()
	state := setupLost
	_ = conn.SetDeadline(time.Now().Add(cfg.verdictWait))
	reply := make([]byte, setupReplyLen)
	if _, err := io.ReadFull(conn, reply); err == nil {
		if kind, ok := x.check(reply, setupSaysConfirmed, setupSaysRejected, setupSaysExpired, setupSaysCancelled); ok {
			state = map[byte]string{
				setupSaysConfirmed: setupConfirmed,
				setupSaysRejected:  setupRejected,
				setupSaysExpired:   setupExpired,
				setupSaysCancelled: setupCancelled,
			}[kind]
		}
	}
	setupMu.Lock()
	mine := setupSend == tx
	if mine {
		tx.state = state
	}
	setupMu.Unlock()
	if mine {
		slog.Info("Setup: the television decided", "state", state)
	}
}

// SetupSendStatus is where the last send stands: "idle" with none, or one of
// SetupSend's states — "sending" with the road being tried in "path" while
// SetupSend has not returned — or, after "awaiting", how it ended:
//
//	"confirmed"  the person at the television said yes
//	"rejected"   they said no
//	"expired"    they did not answer in time
//	"cancelled"  the television's session ended some other way
//	"lost"       the connection dropped first; the television knows the rest
func SetupSendStatus() string {
	setupMu.Lock()
	defer setupMu.Unlock()
	if setupSend == nil {
		return setupJSON(setupTxStatus{State: setupIdle})
	}
	return setupJSON(setupTxStatus{State: setupSend.state, Path: setupSend.path, Code: setupSend.code, Address: setupSend.address})
}

// SetupSendCancel stops waiting and forgets the send; the status is "idle"
// afterwards. It does not take back a bundle the television already holds:
// that is rejected there.
func SetupSendCancel() {
	setupMu.Lock()
	tx := setupSend
	setupSend = nil
	setupMu.Unlock()
	if tx != nil {
		tx.stop()
	}
}
