package appctr

// setup_send.go — the phone's side of setup.go: reading the code a
// television drew and sending it a key.
//
// The phone goes straight to the television's LAN address; the tailnet has
// no part in it, since the television is not in one yet. It runs with or
// without a daemon and is not stopped with it.

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

	setupTaken        = "taken"         // another bundle got there first
	setupUnsupported  = "unsupported"   // the television's build cannot use this bundle
	setupUnreachable  = "unreachable"   // no connection
	setupWrongNetwork = "wrong_network" // no connection, and the phone is on none of the television's networks
	setupClosed       = "closed"        // the television is there but no longer listens: the code is used up, expired or cancelled
	setupNoAnswer     = "no_answer"     // sent, and nothing the television could have written came back

	setupBadPayload = "invalid_payload" // not a code this build reads
	setupBadKey     = "invalid_key"     // empty, too long, or not printable ASCII without spaces
	setupBadServer  = "invalid_server"  // not an http(s) address
	setupBusy       = "busy"            // an earlier send is still sending or awaiting
)

const (
	// setupDialTimeout bounds the connection attempt to each address; they
	// are tried together.
	setupDialTimeout = 4 * time.Second
	// setupAnswerTimeout bounds sending the request and reading the answer
	// the television gives at once.
	setupAnswerTimeout = 8 * time.Second
	// setupVerdictWait is how long the phone listens for the decision: as
	// long as the television can take, and a little.
	setupVerdictWait = setupConfirmWindow + 30*time.Second
)

type setupSendConfig struct {
	dialTimeout   time.Duration
	answerTimeout time.Duration
	verdictWait   time.Duration
	locals        func() ([]setupLocal, bool)
}

func defaultSetupSendConfig() setupSendConfig {
	return setupSendConfig{
		dialTimeout:   setupDialTimeout,
		answerTimeout: setupAnswerTimeout,
		verdictWait:   setupVerdictWait,
		locals:        systemLocals,
	}
}

// setupParsed is the JSON of SetupParse.
type setupParsed struct {
	OK bool `json:"ok"`
	// Error is "" or why not: "not_a_setup_code" (some other text),
	// "unsupported_version" (a code of a newer build), "malformed" (a code,
	// but damaged or naming addresses outside a local network).
	Error     string   `json:"error"`
	ID        string   `json:"id"` // the session's name, as the television shows it
	Addresses []string `json:"addresses"`
	Port      int      `json:"port"`
}

// SetupParse reads a scanned code without sending anything, so a screen can
// say where a key would go before it goes.
func SetupParse(payload string) string {
	res := setupParsed{Addresses: []string{}}
	c, err := parseSetupCode(payload)
	switch {
	case err == nil:
		res.OK, res.ID, res.Port = true, setupSessionID(c.key), int(c.port)
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
	// Code is the check code, from "awaiting" on: the television shows the
	// same six digits for this bundle and for no other.
	Code string `json:"code"`
	// Address is where the bundle went, "ip:port".
	Address string `json:"address"`
}

// setupTx is the phone's side of one send. Guarded by setupMu.
type setupTx struct {
	state   string
	code    string
	address string
	stop    context.CancelFunc // ends the send and closes its connection
}

var setupSend *setupTx

// SetupSend seals an auth key — with the control server it belongs to, or ""
// to leave the television's as it is, and a name for this phone — to the
// television whose code was scanned, and sends it. It returns once the
// television has answered or cannot be reached, within about twelve seconds:
//
//	"awaiting"      the television holds the bundle and shows Code; poll
//	                SetupSendStatus for "confirmed", "rejected", "expired",
//	                "cancelled" or "lost"
//	"taken"         it already holds someone else's
//	"unsupported"   its build cannot use this bundle
//	"closed"        it no longer listens: the code is used up, expired or cancelled
//	"unreachable"   no connection
//	"wrong_network" no connection, and this phone is on none of its networks
//	"no_answer"     the request went out and no answer of the television's
//	                came back
//	"invalid_payload", "invalid_key", "invalid_server", "busy"
//	                nothing was sent
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
	slog.Info("Setup: sending a key", "session", setupSessionID(c.key), "addresses", len(c.addrs), "with_server", b.server != "")

	conn, x, st := setupDeliver(ctx, cfg, c, plain)

	setupMu.Lock()
	defer setupMu.Unlock()
	if setupSend != tx {
		// SetupSendCancel came while this was on its way.
		cancel()
		return setupTxStatus{State: setupIdle}
	}
	tx.state, tx.code, tx.address = st.State, st.Code, st.Address
	slog.Info("Setup: sent", "state", st.State)
	if st.State == setupAwaiting {
		go tx.await(cfg, conn, x)
	} else {
		cancel()
	}
	return st
}

// setupDeliver takes one sealed bundle to the television and reads what it
// says at once. On "awaiting" the connection comes back open — the decision
// arrives over it — and is closed when ctx is cancelled.
func setupDeliver(ctx context.Context, cfg setupSendConfig, c *setupCode, plain []byte) (net.Conn, *setupExchange, setupTxStatus) {
	request, x, err := sealSetupRequest(c, plain)
	if err != nil {
		// A key no television generates: a point of small order.
		return nil, nil, setupTxStatus{State: setupBadPayload}
	}
	conn, state := setupDial(ctx, cfg, c)
	if conn == nil {
		return nil, nil, setupTxStatus{State: state}
	}
	unhook := context.AfterFunc(ctx, func() { conn.Close() })
	// No check code yet. It is shown only once the television has said, under
	// its tag, that it holds this bundle: a code shown for a request that may
	// never have arrived is one that somebody who read the code and can see
	// this screen could make a bundle of their own to match.
	st := setupTxStatus{State: setupNoAnswer, Address: conn.RemoteAddr().String()}

	_ = conn.SetDeadline(time.Now().Add(cfg.answerTimeout))
	reply := make([]byte, setupReplyLen)
	if _, err := conn.Write(request); err == nil {
		_, err = io.ReadFull(conn, reply)
		if err == nil {
			// Anything but the television's own tag is no answer at all.
			switch kind, ok := x.check(reply, setupSaysHeld, setupSaysTaken, setupSaysUnsupported, setupSaysClosed); {
			case !ok:
			case kind == setupSaysHeld:
				st.State, st.Code = setupAwaiting, x.code
				return conn, x, st
			case kind == setupSaysTaken:
				st.State = setupTaken
			case kind == setupSaysUnsupported:
				st.State = setupUnsupported
			case kind == setupSaysClosed:
				st.State = setupClosed
			}
		}
	}
	unhook()
	conn.Close()
	return nil, nil, st
}

// setupDial connects to the first of the code's addresses that answers. With
// none it says why, as far as that can be told.
func setupDial(ctx context.Context, cfg setupSendConfig, c *setupCode) (net.Conn, string) {
	type result struct {
		conn net.Conn
		err  error
	}
	dialCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan result, len(c.addrs))
	d := net.Dialer{Timeout: cfg.dialTimeout}
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
		// network carried it: the television is there and not listening.
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
// SetupSend's states, or — after "awaiting" — how it ended:
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
	return setupJSON(setupTxStatus{State: setupSend.state, Code: setupSend.code, Address: setupSend.address})
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
