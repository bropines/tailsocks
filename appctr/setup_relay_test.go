package appctr

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
)

// fakeRelay is a relay that never leaves the process: what a television
// opens, a phone that derived the same keys and names the same region finds.
// Everything a session does with the relay goes through it, so the tests
// below are about the session and the phone — which road, when, and what is
// left behind — and not about tailcat, which TestSetupRelayLive is for.
type fakeRelay struct {
	mu       sync.Mutex
	region   uint16
	ends     map[key.NodePublic]*fakeRelayEnd
	last     *fakeRelayEnd
	opens    int
	dials    int
	openErr  error         // open fails with it
	openWait chan struct{} // open waits for it
	stubborn bool          // …even when it is told to stop
	dialErr  error         // dial fails with it
}

func newFakeRelay() *fakeRelay {
	return &fakeRelay{region: testRelayRegion, ends: map[key.NodePublic]*fakeRelayEnd{}}
}

// fakeRelayEnd is a television's end of it.
type fakeRelayEnd struct {
	relay  *fakeRelay
	pub    key.NodePublic
	handle func(net.Conn)
	closed chan struct{}
	once   sync.Once
}

func (e *fakeRelayEnd) Close() error {
	e.once.Do(func() {
		e.relay.mu.Lock()
		delete(e.relay.ends, e.pub)
		e.relay.mu.Unlock()
		close(e.closed)
	})
	return nil
}

func (f *fakeRelay) open(ctx context.Context, keys setupRelayKeys, handle func(net.Conn)) (io.Closer, uint16, error) {
	f.mu.Lock()
	f.opens++
	wait, stubborn, err := f.openWait, f.stubborn, f.openErr
	f.mu.Unlock()
	if wait != nil {
		stop := ctx.Done()
		if stubborn {
			stop = nil
		}
		select {
		case <-wait:
		case <-stop:
			return nil, 0, ctx.Err()
		}
	}
	if err != nil {
		return nil, 0, err
	}
	e := &fakeRelayEnd{relay: f, pub: keys.node.Public(), handle: handle, closed: make(chan struct{})}
	f.mu.Lock()
	f.ends[e.pub], f.last = e, e
	f.mu.Unlock()
	return e, f.region, nil
}

func (f *fakeRelay) dial(ctx context.Context, keys setupRelayKeys, region uint16) (net.Conn, error) {
	f.mu.Lock()
	f.dials++
	err, e := f.dialErr, f.ends[keys.node.Public()]
	f.mu.Unlock()
	if err != nil {
		return nil, err
	}
	if e == nil || region != f.region || keys.psk.IsZero() {
		return nil, errSetupRelayNobody
	}
	phone, tv := net.Pipe()
	go e.handle(tv)
	return phone, nil
}

func (f *fakeRelay) counts() (opens, dials int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.opens, f.dials
}

// end is the television's end that was opened last, once there is one.
func (f *fakeRelay) end(t *testing.T) *fakeRelayEnd {
	t.Helper()
	var e *fakeRelayEnd
	waitFor(t, "the relay to be opened", func() bool {
		f.mu.Lock()
		defer f.mu.Unlock()
		e = f.last
		return e != nil
	})
	return e
}

func (e *fakeRelayEnd) open() bool {
	select {
	case <-e.closed:
		return false
	default:
		return true
	}
}

// waitClosed fails the test unless the television's end of the relay goes
// away.
func waitClosed(t *testing.T, e *fakeRelayEnd) {
	t.Helper()
	select {
	case <-e.closed:
	case <-time.After(5 * time.Second):
		t.Fatalf("the relay's end is still open (television %s)", SetupReceiveStatus())
	}
}

func noLocals() ([]setupLocal, bool) { return nil, true }

// startRelaySetup opens a session that offers the relay and waits for its
// code.
func startRelaySetup(t *testing.T, cfg setupConfig) setupRxStatus {
	t.Helper()
	resetSetup()
	first := startSetupRx(cfg)
	if first.State != setupPreparing || first.Relay != setupRelayStarting || first.Payload != "" || first.ID == "" {
		t.Fatalf("a session that asks for the relay began as %+v", first)
	}
	waitFor(t, "the code", func() bool { return rxNow(t).State != setupPreparing })
	st := rxNow(t)
	if st.State != setupWaiting {
		t.Fatalf("session did not start: %+v", st)
	}
	if st.ID != first.ID {
		t.Fatalf("the session changed its name: %s, then %s", first.ID, st.ID)
	}
	return st
}

func relayConfigs(relay setupRelay) (setupConfig, setupSendConfig) {
	rx, tx := testSetupConfig(), testSendConfig()
	rx.relay, tx.relay = relay, relay
	return rx, tx
}

// closeLAN shuts the session's LAN listeners behind its back: what a phone
// on another network meets at that address, where nothing of the television's
// listens.
func closeLAN(t *testing.T) {
	t.Helper()
	setupMu.Lock()
	defer setupMu.Unlock()
	if setupRecv == nil || len(setupRecv.lns) == 0 {
		t.Fatal("no LAN listener to close")
	}
	for _, ln := range setupRecv.lns {
		ln.Close()
	}
}

// The code of a session with both roads, and what the two ends make of it.
func TestSetupRelayCode(t *testing.T) {
	setupOnLoopback(t)
	relay := newFakeRelay()
	cfg, _ := relayConfigs(relay)
	rx := startRelaySetup(t, cfg)

	if rx.Relay != setupRelayOn || len(rx.Addresses) != 1 || rx.Port == 0 || rx.ExpiresIn != 30 {
		t.Fatalf("waiting status: %+v", rx)
	}
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	if code.version != 2 || code.relay != testRelayRegion || int(code.port) != rx.Port || code.port != setupPortOf(code.key) {
		t.Errorf("the code: %+v, the status says port %d", code, rx.Port)
	}
	// The relay is in the code for nothing: as long as a code without it.
	if len(rx.Payload) != 89 || !strings.HasPrefix(rx.Payload, setupPrefix) {
		t.Errorf("a code of %d characters: %s", len(rx.Payload), rx.Payload[:5])
	}
	var parsed setupParsed
	if err := json.Unmarshal([]byte(SetupParse(rx.Payload)), &parsed); err != nil {
		t.Fatal(err)
	}
	if !parsed.OK || !parsed.Relay || parsed.Port != rx.Port || len(parsed.Addresses) != 1 || parsed.ID != rx.ID {
		t.Errorf("SetupParse: %+v", parsed)
	}
	// Asking again while it waits is asking about the same session.
	if again := startSetupRx(cfg); !equalRx(again, rxNow(t)) || again.ID != rx.ID {
		t.Errorf("a second start: %+v", again)
	}
	if opens, dials := relay.counts(); opens != 1 || dials != 0 {
		t.Errorf("the relay was opened %d times and dialled %d", opens, dials)
	}
	// The television's end is the one the phone's keys lead to.
	keys, err := setupRelayKeysOf(code.key, code.secret())
	if err != nil {
		t.Fatal(err)
	}
	if e := relay.end(t); e.pub != keys.node.Public() || !e.open() {
		t.Error("the phone derives another relay end than the television opened")
	}
}

// Which road a send takes.
func TestSetupRelayFallback(t *testing.T) {
	setupOnLoopback(t)

	t.Run("the LAN answers: the relay is not asked", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		rx := startRelaySetup(t, cfg)
		tx := setupSendWith(send, rx.Payload, testSetupKey, "", "Pixel 9")
		if tx.State != setupAwaiting || tx.Path != setupViaLAN || tx.Address != setupAddr(rx) || len(tx.Code) != 6 {
			t.Fatalf("send: %+v", tx)
		}
		if _, dials := relay.counts(); dials != 0 {
			t.Errorf("the relay was dialled %d times with the LAN working", dials)
		}
		if st := rxNow(t); st.State != setupReceived || st.Via != setupViaLAN || st.From != "127.0.0.1" || st.Code != tx.Code {
			t.Errorf("the television: %+v", st)
		}
	})

	t.Run("nothing of the television's on the LAN: the relay carries it", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		rx := startRelaySetup(t, cfg)
		closeLAN(t)
		tx := setupSendWith(send, rx.Payload, testSetupKey, testSetupServer, "Pixel 9")
		if tx.State != setupAwaiting || tx.Path != setupViaRelay || tx.Address != "" || len(tx.Code) != 6 {
			t.Fatalf("send: %+v", tx)
		}
		if got := txNow(t); got != tx {
			t.Errorf("SetupSendStatus %+v, SetupSend said %+v", got, tx)
		}
		if _, dials := relay.counts(); dials != 1 {
			t.Errorf("the relay was dialled %d times", dials)
		}
		// No address for a bundle that came through the relay: there is none
		// that would mean anything to the person asked.
		st := rxNow(t)
		if st.State != setupReceived || st.Via != setupViaRelay || st.From != "" || st.Code != tx.Code || st.SenderName != "Pixel 9" || st.LoginServer != testSetupServer {
			t.Fatalf("the television: %+v", st)
		}
		// The relay stays for the answer, and goes with the session.
		e := relay.end(t)
		if !e.open() {
			t.Fatal("the relay's end closed while its phone still waits")
		}
		SetupReceiveConfirm()
		if key := SetupReceiveTakeKey(); key != testSetupKey {
			t.Fatalf("the app was handed %q", key)
		}
		waitTx(t, setupConfirmed)
		waitClosed(t, e)
		if st := txNow(t); st.Path != setupViaRelay || st.Code != tx.Code {
			t.Errorf("the phone at the end: %+v", st)
		}
	})

	t.Run("the LAN does not answer in time", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		send.dialTimeoutShort = time.Nanosecond
		rx := startRelaySetup(t, cfg)
		tx := setupSendWith(send, rx.Payload, testSetupKey, "", "")
		if tx.State != setupAwaiting || tx.Path != setupViaRelay {
			t.Fatalf("send: %+v", tx)
		}
		if st := rxNow(t); st.Via != setupViaRelay {
			t.Errorf("the television: %+v", st)
		}
	})

	// The phone's own network has that address too, and something there
	// takes the connection: it is not the television, so it does not say so.
	t.Run("something else answers at that address", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		send.answerTimeoutShort = 150 * time.Millisecond
		rx := startRelaySetup(t, cfg)
		closeLAN(t)
		var other net.Listener
		waitFor(t, "the television's port to be free", func() bool {
			var err error
			other, err = net.Listen("tcp4", setupAddr(rx))
			return err == nil
		})
		defer other.Close()
		got := make(chan int, 1)
		go func() {
			c, err := other.Accept()
			if err != nil {
				return
			}
			defer c.Close()
			n, _ := io.Copy(io.Discard, c)
			got <- int(n)
		}()
		tx := setupSendWith(send, rx.Payload, testSetupKey, "", "")
		if tx.State != setupAwaiting || tx.Path != setupViaRelay || tx.Address != "" {
			t.Fatalf("send: %+v", tx)
		}
		// What the stranger got is sealed like everything else.
		if n := <-got; n != setupHeaderLen+setupPadBlock+setupSealOverhead {
			t.Errorf("the other device was sent %d bytes", n)
		}
		if st := rxNow(t); st.State != setupReceived || st.Via != setupViaRelay || st.FailedAttempts != 0 {
			t.Errorf("the television: %+v", st)
		}
	})

	for _, tc := range []struct {
		name     string
		lan      string // "refused", "silent" or "timeout"
		relayErr error
		want     string
		wantPath string
	}{
		{"no way to the relay, the LAN refusing", "refused", errSetupRelayDown, setupRelayDown, setupViaRelay},
		{"no way to the relay, the LAN silent", "timeout", errSetupRelayDown, setupRelayDown, setupViaRelay},
		{"no television behind the relay, the LAN refusing", "refused", errSetupRelayNobody, setupClosed, setupViaRelay},
		{"no television behind the relay, the LAN silent", "timeout", errSetupRelayNobody, setupUnreachable, setupViaRelay},
		{"a relay error of no kind", "timeout", errors.New("something else"), setupRelayDown, setupViaRelay},
	} {
		t.Run(tc.name, func(t *testing.T) {
			relay := newFakeRelay()
			cfg, send := relayConfigs(relay)
			rx := startRelaySetup(t, cfg)
			if tc.lan == "refused" {
				closeLAN(t)
			} else {
				send.dialTimeoutShort = time.Nanosecond
			}
			relay.mu.Lock()
			relay.dialErr = tc.relayErr
			relay.mu.Unlock()
			tx := setupSendWith(send, rx.Payload, testSetupKey, "", "")
			if tx.State != tc.want || tx.Path != tc.wantPath || tx.Code != "" || tx.Address != "" {
				t.Errorf("send: %+v, want %s by %s", tx, tc.want, tc.wantPath)
			}
			if got := txNow(t); got != tx {
				t.Errorf("SetupSendStatus %+v, SetupSend said %+v", got, tx)
			}
			if _, dials := relay.counts(); dials != 1 {
				t.Errorf("the relay was dialled %d times", dials)
			}
		})
	}

	// Something on the LAN took the request and said nothing; the relay is
	// out of reach. What is known is the first of the two.
	t.Run("silence on the LAN and no relay", func(t *testing.T) {
		relay := newFakeRelay()
		relay.dialErr = errSetupRelayDown
		cfg, send := relayConfigs(relay)
		send.answerTimeoutShort = 100 * time.Millisecond
		rx := startRelaySetup(t, cfg)
		closeLAN(t)
		var other net.Listener
		waitFor(t, "the television's port to be free", func() bool {
			var err error
			other, err = net.Listen("tcp4", setupAddr(rx))
			return err == nil
		})
		defer other.Close()
		go func() {
			if c, err := other.Accept(); err == nil {
				defer c.Close()
				io.Copy(io.Discard, c)
			}
		}()
		tx := setupSendWith(send, rx.Payload, testSetupKey, "", "")
		if tx.State != setupNoAnswer || tx.Path != setupViaLAN || tx.Code != "" {
			t.Errorf("send: %+v", tx)
		}
	})

	// A second phone is told so over the LAN; that is an answer of the
	// television's, and the relay has nothing to add to it.
	t.Run("an answer over the LAN is final", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		rx := startRelaySetup(t, cfg)
		code, err := parseSetupCode(rx.Payload)
		if err != nil {
			t.Fatal(err)
		}
		late := dialSetup(t, setupAddr(rx))
		waitAdmitted(t, 1)
		if tx := setupSendWith(send, rx.Payload, testSetupKey, "", ""); tx.State != setupAwaiting || tx.Path != setupViaLAN {
			t.Fatalf("the first phone: %+v", tx)
		}
		plain, _ := encodeSetupBundle(setupBundle{key: []byte("tskey-auth-second")})
		request, x, err := sealSetupRequest(code, plain)
		if err != nil {
			t.Fatal(err)
		}
		if _, answered, state := setupExchangeOver(context.Background(), late, request, x, 3*time.Second); !answered || state != setupTaken {
			t.Errorf("the second phone: %s, answered %v", state, answered)
		}
		if _, dials := relay.counts(); dials != 0 {
			t.Errorf("the relay was dialled %d times", dials)
		}
	})

	t.Run("a build without the relay follows the LAN alone", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		send.relay = nil
		rx := startRelaySetup(t, cfg)
		if tx := setupSendWith(send, rx.Payload, testSetupKey, "", ""); tx.State != setupAwaiting || tx.Path != setupViaLAN {
			t.Errorf("send: %+v", tx)
		}
		// …and has nowhere to go with a code for the relay alone.
		cfg.locals = noLocals
		only := startRelaySetup(t, cfg)
		if tx := setupSendWith(send, only.Payload, testSetupKey, "", ""); tx.State != setupRelayDown || tx.Code != "" {
			t.Errorf("a code for the relay alone: %+v", tx)
		}
	})
}

// A television with no address on a local network is reached through the
// relay alone; one with neither has no session.
func TestSetupRelayAlone(t *testing.T) {
	setupOnLoopback(t)
	relay := newFakeRelay()
	cfg, send := relayConfigs(relay)
	cfg.locals = noLocals
	rx := startRelaySetup(t, cfg)

	if rx.Relay != setupRelayOn || len(rx.Addresses) != 0 || rx.Port != 0 || len(rx.Payload) != 83 {
		t.Fatalf("waiting status: %+v", rx)
	}
	var parsed setupParsed
	if err := json.Unmarshal([]byte(SetupParse(rx.Payload)), &parsed); err != nil {
		t.Fatal(err)
	}
	if !parsed.OK || !parsed.Relay || parsed.Addresses == nil || len(parsed.Addresses) != 0 || parsed.Port != 0 {
		t.Errorf("SetupParse: %+v", parsed)
	}
	// A network change means nothing to a session that has no address.
	setupNetworkChanged()
	time.Sleep(30 * time.Millisecond)
	if st := rxNow(t); st.State != setupWaiting {
		t.Fatalf("after a network change: %+v", st)
	}

	tx := setupSendWith(send, rx.Payload, testSetupKey, "", "Pixel 9")
	if tx.State != setupAwaiting || tx.Path != setupViaRelay || tx.Address != "" {
		t.Fatalf("send: %+v", tx)
	}
	st := rxNow(t)
	if st.State != setupReceived || st.Via != setupViaRelay || st.From != "" || st.Code != tx.Code {
		t.Fatalf("the television: %+v", st)
	}
	SetupReceiveReject()
	waitTx(t, setupRejected)
	waitClosed(t, relay.end(t))

	t.Run("and no relay either", func(t *testing.T) {
		relay := newFakeRelay()
		relay.openErr = errors.New("no way out")
		cfg, _ := relayConfigs(relay)
		cfg.locals = noLocals
		resetSetup()
		startSetupRx(cfg)
		waitRx(t, setupFailed, setupNoLAN)
		if st := rxNow(t); st.Relay != setupRelayNone || st.Payload != "" {
			t.Errorf("status: %+v", st)
		}
	})
}

// A television that cannot have the relay draws a code for the LAN alone, and
// says so.
func TestSetupRelayUnavailable(t *testing.T) {
	setupOnLoopback(t)

	check := func(t *testing.T, relay *fakeRelay, cfg setupConfig, send setupSendConfig) {
		t.Helper()
		resetSetup()
		startSetupRx(cfg)
		waitFor(t, "the code", func() bool { return rxNow(t).State != setupPreparing })
		rx := rxNow(t)
		if rx.State != setupWaiting || rx.Relay != setupRelayNone || len(rx.Addresses) != 1 {
			t.Fatalf("status: %+v", rx)
		}
		code, err := parseSetupCode(rx.Payload)
		if err != nil || code.relay != 0 {
			t.Fatalf("the code names relay %d: %v", code.relay, err)
		}
		tx := setupSendWith(send, rx.Payload, testSetupKey, "", "")
		if tx.State != setupAwaiting || tx.Path != setupViaLAN {
			t.Errorf("send: %+v", tx)
		}
		if _, dials := relay.counts(); dials != 0 {
			t.Errorf("a relay that is not in the code was dialled %d times", dials)
		}
	}

	t.Run("it says no", func(t *testing.T) {
		relay := newFakeRelay()
		relay.openErr = errors.New("relay map: no route to host")
		cfg, send := relayConfigs(relay)
		check(t, relay, cfg, send)
	})

	t.Run("it does not answer in time", func(t *testing.T) {
		relay := newFakeRelay()
		relay.openWait = make(chan struct{})
		cfg, send := relayConfigs(relay)
		cfg.relayOpen = 60 * time.Millisecond
		started := time.Now()
		check(t, relay, cfg, send)
		if took := time.Since(started); took > 2*time.Second {
			t.Errorf("the code waited %v for the relay", took)
		}
	})

	t.Run("it names no region", func(t *testing.T) {
		relay := newFakeRelay()
		relay.region = 0
		cfg, send := relayConfigs(relay)
		check(t, relay, cfg, send)
		// What was opened for nothing is closed again.
		waitClosed(t, relay.end(t))
	})
}

// However a session ends, the relay's end goes with it.
func TestSetupRelayTeardown(t *testing.T) {
	setupOnLoopback(t)

	// begin opens a session with both roads; with a phone, its bundle has
	// come through the relay and waits.
	begin := func(t *testing.T, edit func(*setupConfig), phone bool) (*fakeRelay, *fakeRelayEnd, setupRxStatus) {
		t.Helper()
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		if edit != nil {
			edit(&cfg)
		}
		rx := startRelaySetup(t, cfg)
		e := relay.end(t)
		if phone {
			closeLAN(t)
			if tx := setupSendWith(send, rx.Payload, testSetupKey, "", ""); tx.State != setupAwaiting || tx.Path != setupViaRelay {
				t.Fatalf("send: %+v", tx)
			}
		}
		if !e.open() {
			t.Fatal("the relay's end closed before the session ended")
		}
		return relay, e, rx
	}

	for _, tc := range []struct {
		name  string
		edit  func(*setupConfig)
		phone bool
		end   func(t *testing.T)
		rx    string // the television's state afterwards
		tx    string // the phone's, if there is one
	}{
		{name: "cancel while waiting", end: func(*testing.T) { SetupReceiveCancel() }, rx: setupIdle},
		{name: "cancel with a bundle held", phone: true, end: func(*testing.T) { SetupReceiveCancel() }, rx: setupIdle, tx: setupCancelled},
		{name: "reject", phone: true, end: func(*testing.T) { SetupReceiveReject() }, rx: setupRejected, tx: setupRejected},
		{name: "the key is taken", phone: true, end: func(t *testing.T) {
			SetupReceiveConfirm()
			if SetupReceiveTakeKey() != testSetupKey {
				t.Error("no key")
			}
		}, rx: setupReleased, tx: setupConfirmed},
		{name: "a yes taken back", phone: true, end: func(*testing.T) {
			SetupReceiveConfirm()
			SetupReceiveReject()
		}, rx: setupRejected, tx: setupRejected},
		{name: "nobody sends", edit: func(c *setupConfig) { c.lifetime = 80 * time.Millisecond }, end: func(*testing.T) {}, rx: setupExpired},
		{name: "nobody answers", edit: func(c *setupConfig) { c.confirmWindow = 80 * time.Millisecond }, phone: true, end: func(*testing.T) {}, rx: setupExpired, tx: setupExpired},
		{name: "the key is not taken", edit: func(c *setupConfig) { c.takeWindow = 80 * time.Millisecond }, phone: true, end: func(*testing.T) { SetupReceiveConfirm() }, rx: setupFailed, tx: setupCancelled},
		{name: "the daemon stops", end: func(*testing.T) { releaseGoResources() }, rx: setupFailed},
		{name: "the daemon stops with a bundle held", phone: true, end: func(*testing.T) { releaseGoResources() }, rx: setupFailed, tx: setupCancelled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, e, rx := begin(t, tc.edit, tc.phone)
			tc.end(t)
			waitClosed(t, e)
			waitFor(t, "the television to be "+tc.rx, func() bool { return rxNow(t).State == tc.rx })
			if tc.tx != "" {
				waitTx(t, tc.tx)
			}
			nothingListens(t, setupAddr(rx))
			// And it is closed once.
			e.Close()
		})
	}

	// Refused requests end a session by either road.
	t.Run("too many attempts through the relay", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, _ := relayConfigs(relay)
		cfg.maxFailures = 3
		rx := startRelaySetup(t, cfg)
		e := relay.end(t)
		code, err := parseSetupCode(rx.Payload)
		if err != nil {
			t.Fatal(err)
		}
		keys, _ := setupRelayKeysOf(code.key, code.secret())
		for i := 1; i <= cfg.maxFailures; i++ {
			c, err := relay.dial(context.Background(), keys, code.relay)
			if err != nil {
				t.Fatal(err)
			}
			_ = c.SetDeadline(time.Now().Add(3 * time.Second))
			// Not a request, and long enough to be read as one.
			c.Write([]byte(strings.Repeat("GET / HTTP/1.1\r\n", 4)))
			if out, _ := io.ReadAll(c); len(out) != 0 {
				t.Fatalf("garbage through the relay was answered: %q", out)
			}
			c.Close()
			if i < cfg.maxFailures {
				waitFor(t, "the attempt to be counted", func() bool { return rxNow(t).FailedAttempts == i })
			}
		}
		waitRx(t, setupFailed, setupTooMany)
		waitClosed(t, e)
		nothingListens(t, setupAddr(rx))
	})

	// A session that is cancelled before the relay has answered: what the
	// relay then hands over is closed, not kept.
	t.Run("cancel while the relay is being asked", func(t *testing.T) {
		relay := newFakeRelay()
		relay.openWait, relay.stubborn = make(chan struct{}), true
		cfg, _ := relayConfigs(relay)
		resetSetup()
		first := startSetupRx(cfg)
		if first.State != setupPreparing {
			t.Fatalf("start: %+v", first)
		}
		if again := startSetupRx(cfg); again.State != setupPreparing || again.ID != first.ID {
			t.Errorf("a second start while the first prepares: %+v", again)
		}
		if st := decodeRx(t, SetupReceiveConfirm()); st.State != setupPreparing || st.Error == "" {
			t.Errorf("confirm with no code yet: %+v", st)
		}
		SetupReceiveCancel()
		if st := rxNow(t); st.State != setupIdle {
			t.Fatalf("after cancel: %+v", st)
		}
		close(relay.openWait)
		waitClosed(t, relay.end(t))
		time.Sleep(20 * time.Millisecond)
		if st := rxNow(t); st.State != setupIdle {
			t.Errorf("the relay's late answer brought the session back: %+v", st)
		}
		if opens, _ := relay.counts(); opens != 1 {
			t.Errorf("the relay was opened %d times", opens)
		}
	})

	t.Run("the daemon stops while the relay is being asked", func(t *testing.T) {
		relay := newFakeRelay()
		relay.openWait = make(chan struct{})
		cfg, _ := relayConfigs(relay)
		resetSetup()
		startSetupRx(cfg)
		releaseGoResources()
		waitRx(t, setupFailed, setupDaemonStop)
		relay.mu.Lock()
		ends := len(relay.ends)
		relay.mu.Unlock()
		if ends != 0 {
			t.Errorf("%d relay ends left open", ends)
		}
	})

	// The device's address goes: the LAN listener with it, the session not.
	t.Run("the LAN address is lost and the relay stays", func(t *testing.T) {
		relay := newFakeRelay()
		cfg, send := relayConfigs(relay)
		gone := make(chan struct{})
		cfg.locals = func() ([]setupLocal, bool) {
			select {
			case <-gone:
				return []setupLocal{{name: "wlan0", prefix: netip.MustParsePrefix("192.168.7.7/24")}}, true
			default:
				return loopbackLocals()
			}
		}
		rx := startRelaySetup(t, cfg)
		e := relay.end(t)
		close(gone)
		setupNetworkChanged()
		waitFor(t, "the lost address to be dropped", func() bool { return len(rxNow(t).Addresses) == 0 })
		st := rxNow(t)
		if st.State != setupWaiting || st.Relay != setupRelayOn || st.Payload != rx.Payload || st.Port != 0 || !e.open() {
			t.Fatalf("after losing the address: %+v", st)
		}
		nothingListens(t, setupAddr(rx))
		// The code still names the address; the phone gets nowhere with it
		// and takes the relay.
		if tx := setupSendWith(send, rx.Payload, testSetupKey, "", ""); tx.State != setupAwaiting || tx.Path != setupViaRelay {
			t.Errorf("send: %+v", tx)
		}
		SetupReceiveCancel()
		waitClosed(t, e)
	})
}

// A phone whose first try was not heard asks again by the other road, with
// the same request: the television knows it, says "held" again, and tells
// both connections how it ended.
func TestSetupRelaySameRequestTwice(t *testing.T) {
	setupOnLoopback(t)
	relay := newFakeRelay()
	cfg, _ := relayConfigs(relay)
	rx := startRelaySetup(t, cfg)
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	request, x := testRequest(t, rx.Payload, testSetupKey)
	reply := make([]byte, setupReplyLen)

	lan := dialSetup(t, setupAddr(rx))
	lan.Write(request)
	if _, err := io.ReadFull(lan, reply); err != nil {
		t.Fatal(err)
	}
	if kind, ok := x.check(reply, setupSaysHeld); !ok {
		t.Fatalf("over the LAN: %#x", kind)
	}
	held := rxNow(t)

	keys, _ := setupRelayKeysOf(code.key, code.secret())
	through, err := relay.dial(context.Background(), keys, code.relay)
	if err != nil {
		t.Fatal(err)
	}
	defer through.Close()
	_ = through.SetDeadline(time.Now().Add(5 * time.Second))
	through.Write(request)
	if _, err := io.ReadFull(through, reply); err != nil {
		t.Fatal(err)
	}
	if kind, ok := x.check(reply, setupSaysHeld); !ok {
		t.Fatalf("through the relay: %#x", kind)
	}
	// One bundle, the one that came first, by the road it came.
	if now := rxNow(t); !equalRx(now, held) || now.Via != setupViaLAN || now.Code != x.code {
		t.Errorf("the second arrival changed the session: %+v, was %+v", now, held)
	}

	// Another phone's request by that road is another bundle, and too late.
	other, y := testRequest(t, rx.Payload, "tskey-auth-another")
	late, err := relay.dial(context.Background(), keys, code.relay)
	if err != nil {
		t.Fatal(err)
	}
	defer late.Close()
	if _, answered, state := setupExchangeOver(context.Background(), late, other, y, 3*time.Second); !answered || state != setupTaken {
		t.Errorf("another phone through the relay: %s, answered %v", state, answered)
	}

	SetupReceiveConfirm()
	if SetupReceiveTakeKey() != testSetupKey {
		t.Fatal("no key")
	}
	for name, c := range map[string]net.Conn{"the LAN": lan, "the relay": through} {
		if _, err := io.ReadFull(c, reply); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if kind, ok := x.check(reply, setupSaysConfirmed); !ok {
			t.Errorf("%s: the verdict is %#x", name, kind)
		}
	}
	waitClosed(t, relay.end(t))
}

// The user's own tailcat server is theirs: a session neither uses it nor
// waits for it to be free, and leaves it as it found it.
func TestSetupRelayBesideTheUsersServer(t *testing.T) {
	setupOnLoopback(t)
	users := &tcServer{tcLog: tcLog{name: "server"}, wake: make(chan struct{}, 1), state: "serving", active: map[net.Conn]struct{}{}}
	tsMu.Lock()
	tsSrv = users
	tsMu.Unlock()
	t.Cleanup(func() {
		tsMu.Lock()
		tsSrv = nil
		tsMu.Unlock()
	})
	before := TailcatServerStatusJSON()
	if !strings.Contains(before, `"serving"`) {
		t.Fatalf("the user's server: %s", before)
	}
	if err := TailcatServerStart("/nonexistent", "80", false, "", true); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("a second server of the user's: %v", err)
	}

	relay := newFakeRelay()
	cfg, send := relayConfigs(relay)
	rx := startRelaySetup(t, cfg)
	if rx.Relay != setupRelayOn {
		t.Fatalf("with the user's server running: %+v", rx)
	}
	closeLAN(t)
	if tx := setupSendWith(send, rx.Payload, testSetupKey, "", ""); tx.State != setupAwaiting || tx.Path != setupViaRelay {
		t.Fatalf("send: %+v", tx)
	}
	SetupReceiveConfirm()
	if SetupReceiveTakeKey() != testSetupKey {
		t.Fatal("no key")
	}
	waitTx(t, setupConfirmed)
	waitClosed(t, relay.end(t))

	tsMu.Lock()
	still := tsSrv
	tsMu.Unlock()
	if still != users || TailcatServerStatusJSON() != before {
		t.Errorf("the user's server after the session: %s, was %s", TailcatServerStatusJSON(), before)
	}
	select {
	case <-users.wake:
		t.Error("the session woke the user's server")
	default:
	}
}

// What the two ends derive for the tunnel: the same from one code, something
// else from any other, and an address tailcat reads.
func TestSetupRelayKeys(t *testing.T) {
	text, _ := testCode(t, 1, testRelayRegion)
	code, err := parseSetupCode(text)
	if err != nil {
		t.Fatal(err)
	}
	tv, err := setupRelayKeysOf(code.key, code.secret())
	if err != nil {
		t.Fatal(err)
	}
	phone, _ := setupRelayKeysOf(code.key, code.secret())
	if !tv.node.Equal(phone.node) || !tv.psk.Equal(phone.psk) || tv.node.IsZero() || tv.psk.IsZero() {
		t.Fatal("the two ends derive different keys, or none")
	}
	otherSecret := append([]byte(nil), code.secret()...)
	otherSecret[5] ^= 1
	otherKey := append([]byte(nil), code.key...)
	otherKey[5] ^= 1
	for name, k := range map[string]setupRelayKeys{
		"another secret": first2(setupRelayKeysOf(code.key, otherSecret)),
		"another key":    first2(setupRelayKeysOf(otherKey, code.secret())),
	} {
		if k.node.Equal(tv.node) || k.psk.Equal(tv.psk) {
			t.Errorf("%s gives the same tunnel", name)
		}
	}
	// The tunnel's keys are not the sealing's: neither the secret nor the
	// television's key shows in them.
	raw, _ := tv.node.MarshalText()
	for what, part := range map[string][]byte{"the secret": code.secret(), "the television's key": code.key} {
		if strings.Contains(string(raw), hex.EncodeToString(part)) || strings.Contains(hex.EncodeToString(tv.psk[:]), hex.EncodeToString(part)) {
			t.Errorf("%s is in the tunnel's keys as it stands", what)
		}
	}

	// What a phone hands tailcat is an address like any server's, for the
	// region the code names.
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: tv.node.Public()},
		ServerDiscoPublic: tailcat.DiscoPublicForNode(tv.node),
		PresharedKey:      tv.psk,
		RegionID:          testRelayRegion,
	}
	back, err := tailcat.ParseAddr(ci.Addr())
	if err != nil {
		t.Fatal(err)
	}
	if !back.ServerPublic.Equal(ci.ServerPublic) || !back.ServerDiscoPublic.Equal(ci.ServerDiscoPublic) || !back.PresharedKey.Equal(tv.psk) || back.RegionID != testRelayRegion {
		t.Errorf("the address reads back as %+v", back)
	}
	if msg := TailcatCheckAddress(string(ci.Addr())); msg != "" {
		t.Errorf("tailcat refuses the address: %s", msg)
	}
}

func first2(k setupRelayKeys, _ error) setupRelayKeys { return k }

// Nothing a session with a relay logs can be used: not the code, the secret
// or the key, and not the tunnel's keys that follow from them.
func TestSetupRelayLogs(t *testing.T) {
	setupOnLoopback(t)
	ClearLogs()
	relay := newFakeRelay()
	cfg, send := relayConfigs(relay)
	rx := startRelaySetup(t, cfg)
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	closeLAN(t)
	tx := setupSendWith(send, rx.Payload, testSetupKey, testSetupServer, "Pixel 9")
	if tx.State != setupAwaiting || tx.Path != setupViaRelay {
		t.Fatalf("send: %+v", tx)
	}
	SetupReceiveConfirm()
	SetupReceiveTakeKey()
	waitTx(t, setupConfirmed)
	waitClosed(t, relay.end(t))

	keys, _ := setupRelayKeysOf(code.key, code.secret())
	nodePriv, _ := keys.node.MarshalText()
	logs := strings.ToLower(GetLogs())
	if !strings.Contains(logs, "via=relay") {
		t.Fatalf("the log does not say which road the key took:\n%s", logs)
	}
	for what, s := range map[string]string{
		"the key":                 testSetupKey,
		"the code":                rx.Payload[len(setupPrefix):],
		"the secret":              hex.EncodeToString(code.secret()),
		"the television's key":    hex.EncodeToString(code.key),
		"the server":              testSetupServer,
		"the check code":          tx.Code,
		"the tunnel's key":        strings.TrimPrefix(string(nodePriv), "privkey:"),
		"the tunnel's public key": strings.TrimPrefix(keys.node.Public().String(), "nodekey:"),
		"the pre-shared key":      hex.EncodeToString(keys.psk[:]),
	} {
		if strings.Contains(logs, strings.ToLower(s)) {
			t.Errorf("the log carries %s:\n%s", what, logs)
		}
	}
	for _, status := range []string{SetupReceiveStatus(), SetupSendStatus()} {
		if strings.Contains(status, testSetupKey) {
			t.Errorf("a status carries the key: %s", status)
		}
	}
}

// TestSetupRelayLive runs a session over the real thing — tailcat, the relay
// map at tailcat.dev and Tailscale's relays — with both ends in this process,
// so it runs only when asked, as TestTailcatLive does:
//
//	TC_BIN=1 go test -ldflags=-checklinkname=0 -run TestSetupRelayLive -v .
//
// (It runs no tailcat binary; TC_BIN is only the word that says "go out".)
// The television has no LAN address here, so the relay is the only road; the
// user's own tailcat server runs beside the session's and is still there
// afterwards.
func TestSetupRelayLive(t *testing.T) {
	if os.Getenv("TC_BIN") == "" {
		t.Skip("TC_BIN not set")
	}
	t.Cleanup(resetSetup)

	// The user's server, as the Tailcat screen starts it.
	keyFile := t.TempDir() + "/server.json"
	if _, err := TailcatServerCreateKey(keyFile); err != nil {
		t.Fatalf("the user's server key: %v", err)
	}
	if err := TailcatServerStart(keyFile, "28000", false, "", true); err != nil {
		t.Fatal(err)
	}
	defer TailcatServerStop()
	serving := func() bool { return strings.Contains(TailcatServerStatusJSON(), `"serving"`) }
	for i := 0; i < 150 && !serving(); i++ {
		time.Sleep(200 * time.Millisecond)
	}
	if !serving() {
		t.Fatalf("the user's server is not serving: %s\n%s", TailcatServerStatusJSON(), TailcatServerLog())
	}

	cfg := defaultSetupConfig()
	cfg.locals = noLocals
	resetSetup()
	started := time.Now()
	first := startSetupRx(cfg)
	if first.State != setupPreparing {
		t.Fatalf("start: %+v", first)
	}
	for rxNow(t).State == setupPreparing && time.Since(started) < 15*time.Second {
		time.Sleep(50 * time.Millisecond)
	}
	rx := rxNow(t)
	if rx.State != setupWaiting || rx.Relay != setupRelayOn || len(rx.Addresses) != 0 {
		t.Fatalf("the session after %v: %+v", time.Since(started), rx)
	}
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("the code was up after %v: %d characters, relay region %d", time.Since(started).Round(time.Millisecond), len(rx.Payload), code.relay)

	sent := time.Now()
	tx := setupSendWith(defaultSetupSendConfig(), rx.Payload, testSetupKey, testSetupServer, "live test")
	if tx.State != setupAwaiting || tx.Path != setupViaRelay || len(tx.Code) != 6 {
		t.Fatalf("send after %v: %+v", time.Since(sent), tx)
	}
	t.Logf("the television held the bundle %v after the send began", time.Since(sent).Round(time.Millisecond))
	st := rxNow(t)
	if st.State != setupReceived || st.Via != setupViaRelay || st.From != "" || st.Code != tx.Code || st.LoginServer != testSetupServer {
		t.Fatalf("the television: %+v", st)
	}

	decided := time.Now()
	SetupReceiveConfirm()
	if key := SetupReceiveTakeKey(); key != testSetupKey {
		t.Fatalf("the app was handed %d bytes", len(key))
	}
	for txNow(t).State == setupAwaiting && time.Since(decided) < 10*time.Second {
		time.Sleep(20 * time.Millisecond)
	}
	if got := txNow(t); got.State != setupConfirmed {
		t.Fatalf("the phone %v after the yes: %+v", time.Since(decided), got)
	}
	t.Logf("the phone heard the yes after %v", time.Since(decided).Round(time.Millisecond))

	// A second session at once: the region is remembered, the code is quick.
	again := time.Now()
	startSetupRx(cfg)
	for rxNow(t).State == setupPreparing && time.Since(again) < 15*time.Second {
		time.Sleep(20 * time.Millisecond)
	}
	if st := rxNow(t); st.State != setupWaiting || st.Relay != setupRelayOn {
		t.Fatalf("a second session: %+v", st)
	}
	t.Logf("a second code was up after %v", time.Since(again).Round(time.Millisecond))
	// A phone with a code nobody listens behind any more.
	SetupReceiveCancel()
	time.Sleep(setupRelayDrain + time.Second)
	gone := time.Now()
	late := setupSendWith(defaultSetupSendConfig(), rx.Payload, testSetupKey, "", "")
	t.Logf("a send to a session that is over: %q by %q after %v", late.State, late.Path, time.Since(gone).Round(time.Millisecond))
	if late.State != setupUnreachable && late.State != setupClosed {
		t.Errorf("a send to a session that is over: %+v", late)
	}

	if !serving() {
		t.Errorf("the user's server after the sessions: %s", TailcatServerStatusJSON())
	}
}
