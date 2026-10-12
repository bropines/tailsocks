package appctr

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"encoding/base32"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Both ends of every test below are this machine, talking over 127.0.0.1.

func loopbackLocals() ([]setupLocal, bool) {
	return []setupLocal{{name: "lo", prefix: netip.MustParsePrefix("127.0.0.1/8")}}, true
}

// setupOnLoopback lets sessions use 127.0.0.1 for the test and leaves none
// behind.
func setupOnLoopback(t *testing.T) {
	t.Helper()
	setupLoopbackOK.Store(true)
	t.Cleanup(func() {
		resetSetup()
		setupLoopbackOK.Store(false)
	})
}

func testSetupConfig() setupConfig {
	return setupConfig{
		lifetime:      30 * time.Second,
		confirmWindow: 30 * time.Second,
		takeWindow:    30 * time.Second,
		maxFailures:   setupMaxFailures,
		readTimeout:   3 * time.Second,
		writeTimeout:  3 * time.Second,
		addrCheck:     time.Hour,
		tick:          5 * time.Millisecond,
		locals:        loopbackLocals,
	}
}

func testSendConfig() setupSendConfig {
	return setupSendConfig{
		dialTimeout:   3 * time.Second,
		answerTimeout: 3 * time.Second,
		verdictWait:   30 * time.Second,
		locals:        loopbackLocals,
	}
}

// resetSetup ends both sides' sessions, a confirmed key included.
func resetSetup() {
	setupMu.Lock()
	if setupRecv != nil {
		setupRecv.endLocked(setupFailed, setupWasStopped, setupSaysCancelled)
		setupRecv = nil
	}
	setupMu.Unlock()
	SetupSendCancel()
}

// startTestSetup opens a new session, whatever the test did before, and
// fails the test unless it waits.
func startTestSetup(t *testing.T, cfg setupConfig) setupRxStatus {
	t.Helper()
	resetSetup()
	st := startSetupRx(cfg)
	if st.State != setupWaiting {
		t.Fatalf("session did not start: %+v", st)
	}
	return st
}

func sendTestSetup(payload, key, server string) setupTxStatus {
	return setupSendWith(testSendConfig(), payload, key, server, "Pixel 9")
}

// rxNow and txNow go through the exported calls, as a screen would.
func rxNow(t *testing.T) setupRxStatus {
	t.Helper()
	return decodeRx(t, SetupReceiveStatus())
}

func decodeRx(t *testing.T, s string) setupRxStatus {
	t.Helper()
	var st setupRxStatus
	dec := json.NewDecoder(strings.NewReader(s))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&st); err != nil {
		t.Fatalf("status %q: %v", s, err)
	}
	return st
}

func txNow(t *testing.T) setupTxStatus {
	t.Helper()
	var st setupTxStatus
	if err := json.Unmarshal([]byte(SetupSendStatus()), &st); err != nil {
		t.Fatal(err)
	}
	return st
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s (television %s, phone %s)", what, SetupReceiveStatus(), SetupSendStatus())
		}
		time.Sleep(2 * time.Millisecond)
	}
}

func waitRx(t *testing.T, state, reason string) {
	t.Helper()
	waitFor(t, "the television to be "+state+" "+reason, func() bool {
		st := rxNow(t)
		return st.State == state && st.Reason == reason
	})
}

func waitTx(t *testing.T, state string) {
	t.Helper()
	waitFor(t, "the phone to be "+state, func() bool { return txNow(t).State == state })
}

func setupAddr(st setupRxStatus) string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(st.Port))
}

// nothingListens fails the test if the session's port still takes connections.
func nothingListens(t *testing.T, addr string) {
	t.Helper()
	if listens(addr) {
		t.Errorf("%s still listens", addr)
	}
}

// listens reports whether something takes connections at addr. A connection
// to itself does not count: with nothing listening on a port of the range
// the kernel draws source ports from, a dial to it on this machine is now and
// then given that very port and meets itself.
func listens(addr string) bool {
	c, err := net.DialTimeout("tcp4", addr, time.Second)
	if err != nil {
		return false
	}
	defer c.Close()
	return c.LocalAddr().String() != c.RemoteAddr().String()
}

// waitAdmitted waits until the session is reading n connections. A dial
// returns when the kernel has the connection, which is before the session
// does; one still queued when the listener closes is reset, not read.
func waitAdmitted(t *testing.T, n int) {
	t.Helper()
	waitFor(t, strconv.Itoa(n)+" connections to be read", func() bool {
		setupMu.Lock()
		defer setupMu.Unlock()
		return setupRecv != nil && len(setupRecv.conns) == n
	})
}

func dialSetup(t *testing.T, addr string) *net.TCPConn {
	t.Helper()
	c, err := net.DialTimeout("tcp4", addr, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	return c.(*net.TCPConn)
}

// testRequest seals a bundle for a code, as a phone would.
func testRequest(t *testing.T, payload, key string) ([]byte, *setupExchange) {
	t.Helper()
	c, err := parseSetupCode(payload)
	if err != nil {
		t.Fatal(err)
	}
	plain, err := encodeSetupBundle(setupBundle{key: []byte(key)})
	if err != nil {
		t.Fatal(err)
	}
	request, x, err := sealSetupRequest(c, plain)
	if err != nil {
		t.Fatal(err)
	}
	return request, x
}

// heldKey is the key the session keeps, read under its lock.
func heldKey() []byte {
	setupMu.Lock()
	defer setupMu.Unlock()
	if setupRecv == nil || setupRecv.held == nil {
		return nil
	}
	return bytes.Clone(setupRecv.held.key)
}

// The whole road: code, bundle, the same check code on both sides, the
// person's yes, and the key handed to the app, once.
func TestSetupExchange(t *testing.T) {
	setupOnLoopback(t)
	ClearLogs()

	rx := startTestSetup(t, testSetupConfig())
	if !slices.Equal(rx.Addresses, []string{"127.0.0.1"}) || rx.Port < setupMinPort || rx.ExpiresIn != 30 || len(rx.ID) != 5 {
		t.Fatalf("waiting status: %+v", rx)
	}
	var parsed setupParsed
	if err := json.Unmarshal([]byte(SetupParse(rx.Payload)), &parsed); err != nil {
		t.Fatal(err)
	}
	if !parsed.OK || parsed.ID != rx.ID || parsed.Port != rx.Port || !slices.Equal(parsed.Addresses, rx.Addresses) {
		t.Fatalf("SetupParse(%q) = %+v, the television says %+v", rx.Payload, parsed, rx)
	}
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}

	// A scanner may hand the text over in any case, with a line end.
	tx := sendTestSetup(" "+strings.ToLower(rx.Payload)+"\n", "  "+testSetupKey+"\n", testSetupServer)
	if tx.State != setupAwaiting || len(tx.Code) != 6 || tx.Address != setupAddr(rx) {
		t.Fatalf("send: %+v", tx)
	}
	if got := txNow(t); got != tx {
		t.Errorf("SetupSendStatus %+v, SetupSend said %+v", got, tx)
	}

	got := rxNow(t)
	want := setupRxStatus{
		State: setupReceived, ID: rx.ID, Addresses: []string{}, ExpiresIn: 30,
		From: "127.0.0.1", SenderName: "Pixel 9", Code: tx.Code, KeyPrefix: "tskey-auth", LoginServer: testSetupServer,
	}
	if !slices.Equal(got.Addresses, want.Addresses) {
		t.Errorf("addresses %q after the bundle arrived", got.Addresses)
	}
	got.Addresses = nil
	want.Addresses = nil
	if !equalRx(got, want) {
		t.Errorf("received status:\n got %+v\nwant %+v", got, want)
	}
	// One bundle, so nothing listens while it waits for the person.
	nothingListens(t, setupAddr(rx))

	// Nothing is taken before the person has said yes.
	if key := SetupReceiveTakeKey(); key != "" {
		t.Fatalf("a key was handed out unconfirmed: %d bytes", len(key))
	}
	confirmed := decodeRx(t, SetupReceiveConfirm())
	if confirmed.State != setupConfirmed || confirmed.Error != "" || confirmed.Code != tx.Code || confirmed.LoginServer != testSetupServer || confirmed.ExpiresIn != 30 {
		t.Fatalf("confirm: %+v", confirmed)
	}
	if again := decodeRx(t, SetupReceiveConfirm()); again.State != setupConfirmed || again.Error != "nothing_to_confirm" {
		t.Errorf("second confirm: %+v", again)
	}
	// The phone hears of it when the app has the key, not before.
	if st := txNow(t); st.State != setupAwaiting {
		t.Errorf("the phone before the key is taken: %+v", st)
	}

	if key := SetupReceiveTakeKey(); key != testSetupKey {
		t.Fatalf("the app was handed %q", key)
	}
	waitTx(t, setupConfirmed)
	if st := rxNow(t); st.State != setupReleased || st.Code != tx.Code || st.From != "127.0.0.1" || st.LoginServer != testSetupServer || st.ExpiresIn != 0 {
		t.Errorf("after the hand-over: %+v", st)
	}
	if again := SetupReceiveTakeKey(); again != "" {
		t.Errorf("the key was handed out twice: %d bytes", len(again))
	}
	if k := heldKey(); len(k) != 0 {
		t.Errorf("the session still holds %d bytes of key", len(k))
	}

	// Nothing that could be used again is in a status or in the log.
	secret := code.raw[setupKeyLen : setupKeyLen+setupSecretLen]
	secrets := map[string]string{
		"the key":             testSetupKey,
		"the code":            rx.Payload,
		"the code's digits":   rx.Payload[len(setupPrefix):],
		"the secret (hex)":    hex.EncodeToString(secret),
		"the secret (base32)": base32.StdEncoding.EncodeToString(secret),
		"the public key":      hex.EncodeToString(code.key),
		"the server":          testSetupServer,
		"the check code":      tx.Code,
	}
	logs := GetLogs()
	if !strings.Contains(logs, "Setup:") {
		t.Fatalf("no setup lines in the log:\n%s", logs)
	}
	for what, s := range secrets {
		if strings.Contains(strings.ToLower(logs), strings.ToLower(s)) {
			t.Errorf("the log carries %s:\n%s", what, logs)
		}
	}
	for _, status := range []string{SetupReceiveStatus(), SetupSendStatus(), setupJSON(confirmed), setupJSON(got)} {
		if strings.Contains(status, testSetupKey) || strings.Contains(status, "kTESTtest1CNTRL") {
			t.Errorf("a status carries the key: %s", status)
		}
	}
}

func equalRx(a, b setupRxStatus) bool {
	return setupJSON(a) == setupJSON(b)
}

func TestSetupReject(t *testing.T) {
	setupOnLoopback(t)
	rx := startTestSetup(t, testSetupConfig())
	if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
		t.Fatalf("send: %+v", tx)
	}
	if k := heldKey(); string(k) != testSetupKey {
		t.Fatalf("held key %q", k)
	}
	if st := rxNow(t); st.LoginServer != "" || st.SenderName != "Pixel 9" {
		t.Errorf("received: %+v", st)
	}
	if st := decodeRx(t, SetupReceiveReject()); st.State != setupRejected || st.Error != "" {
		t.Fatalf("reject: %+v", st)
	}
	waitTx(t, setupRejected)
	if k := heldKey(); len(k) != 0 {
		t.Errorf("a rejected key is still held: %d bytes", len(k))
	}
	if key := SetupReceiveTakeKey(); key != "" {
		t.Errorf("a rejected key was handed out: %d bytes", len(key))
	}
	for _, call := range []func() string{SetupReceiveConfirm, SetupReceiveReject} {
		if st := decodeRx(t, call()); st.State != setupRejected || st.Error == "" {
			t.Errorf("after the reject: %+v", st)
		}
	}
	nothingListens(t, setupAddr(rx))
}

// A code that differs from the television's in its secret, its key or its
// addresses gives other keys: the bundle does not open, the phone gets no
// answer, and the right code works afterwards.
func TestSetupWrongCode(t *testing.T) {
	setupOnLoopback(t)
	rx := startTestSetup(t, testSetupConfig())
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	pub := code.raw[:setupKeyLen]
	secret := code.raw[setupKeyLen : setupKeyLen+setupSecretLen]

	otherSecret := bytes.Clone(secret)
	otherSecret[0] ^= 1
	other, err := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	wrong := map[string]string{}
	wrong["another secret"], _ = encodeSetupCode(pub, otherSecret, code.port, code.addrs)
	wrong["another key"], _ = encodeSetupCode(other.PublicKey().Bytes(), secret, code.port, code.addrs)
	wrong["another address list"], _ = encodeSetupCode(pub, secret, code.port, []netip.Addr{code.addrs[0], netip.MustParseAddr("127.0.0.2")})

	refused := 0
	for name, payload := range wrong {
		tx := sendTestSetup(payload, testSetupKey, "")
		if tx.State != setupNoAnswer || tx.Code != "" {
			t.Errorf("%s: %+v", name, tx)
		}
		refused++
		waitFor(t, name+" to be counted", func() bool { return rxNow(t).FailedAttempts == refused })
		if st := rxNow(t); st.State != setupWaiting || st.From != "" {
			t.Errorf("%s: the television is %+v", name, st)
		}
	}
	if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
		t.Errorf("the right code after the wrong ones: %+v", tx)
	}
}

// A recorded request is worth nothing: the session it was made for holds one
// bundle whatever is sent, and any other session has another key and secret.
func TestSetupReplay(t *testing.T) {
	setupOnLoopback(t)
	rx := startTestSetup(t, testSetupConfig())
	request, x := testRequest(t, rx.Payload, testSetupKey)

	// Both connections exist before the request goes down the first, so the
	// second is one that was on its way when the session stopped listening.
	first := dialSetup(t, setupAddr(rx))
	second := dialSetup(t, setupAddr(rx))
	waitAdmitted(t, 2)
	reply := make([]byte, setupReplyLen)
	first.Write(request)
	if _, err := io.ReadFull(first, reply); err != nil {
		t.Fatal(err)
	}
	if kind, ok := x.check(reply, setupSaysHeld); !ok {
		t.Fatalf("the first answer is %#x", kind)
	}
	held := rxNow(t)

	// The recording gets no answer of its own: nothing to hand the phone in
	// place of the one it was given.
	second.Write(request)
	if out, _ := io.ReadAll(second); len(out) != 0 {
		t.Fatalf("the replay was answered with %d bytes", len(out))
	}
	if now := rxNow(t); now.State != setupReceived || now.Code != x.code || now.Code != held.Code || now.FailedAttempts != 0 {
		t.Errorf("the replay changed the session: %+v", now)
	}
	if k := heldKey(); string(k) != testSetupKey {
		t.Errorf("held key after the replay: %q", k)
	}
	nothingListens(t, setupAddr(rx))

	// An answer cannot be played back as a verdict either.
	if _, ok := x.check(x.reply(setupSaysHeld), setupSaysConfirmed, setupSaysRejected, setupSaysExpired, setupSaysCancelled); ok {
		t.Error("an acknowledgement passed for a verdict")
	}

	SetupReceiveReject()
	if _, err := io.ReadFull(first, reply); err != nil {
		t.Fatal(err)
	}
	if kind, ok := x.check(reply, setupSaysRejected); !ok {
		t.Fatalf("the verdict is %#x", kind)
	}
	nothingListens(t, setupAddr(rx))

	// The next session, as it happens on any port: the recording does not open.
	next := startTestSetup(t, testSetupConfig())
	c := dialSetup(t, setupAddr(next))
	c.Write(request)
	if n, err := io.ReadFull(c, reply); err == nil || n != 0 {
		t.Fatalf("a later session answered a recording: %d bytes", n)
	}
	waitFor(t, "the recording to be counted", func() bool { return rxNow(t).FailedAttempts == 1 })
	if st := rxNow(t); st.State != setupWaiting {
		t.Errorf("the later session is %+v", st)
	}
}

func TestSetupExpiry(t *testing.T) {
	setupOnLoopback(t)

	t.Run("nobody sends", func(t *testing.T) {
		cfg := testSetupConfig()
		cfg.lifetime = 60 * time.Millisecond
		rx := startTestSetup(t, cfg)
		waitRx(t, setupExpired, "")
		nothingListens(t, setupAddr(rx))
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupClosed || tx.Code != "" {
			t.Errorf("sending to an expired code: %+v", tx)
		}
		if st := rxNow(t); st.Payload != "" || st.ExpiresIn != 0 {
			t.Errorf("an expired session still shows %+v", st)
		}
	})

	t.Run("nobody confirms", func(t *testing.T) {
		cfg := testSetupConfig()
		cfg.confirmWindow = 80 * time.Millisecond
		rx := startTestSetup(t, cfg)
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
			t.Fatalf("send: %+v", tx)
		}
		waitRx(t, setupExpired, "")
		waitTx(t, setupExpired)
		if k := heldKey(); len(k) != 0 {
			t.Errorf("an expired key is still held: %d bytes", len(k))
		}
		if st := decodeRx(t, SetupReceiveConfirm()); st.State != setupExpired || st.Error != "nothing_to_confirm" {
			t.Errorf("confirming too late: %+v", st)
		}
	})

	t.Run("the app never takes the key", func(t *testing.T) {
		cfg := testSetupConfig()
		cfg.takeWindow = 80 * time.Millisecond
		rx := startTestSetup(t, cfg)
		sendTestSetup(rx.Payload, testSetupKey, "")
		if st := decodeRx(t, SetupReceiveConfirm()); st.State != setupConfirmed {
			t.Fatalf("confirm: %+v", st)
		}
		waitRx(t, setupFailed, setupNotTaken)
		// The phone was not told yes for a key nobody took.
		waitTx(t, setupCancelled)
		if key := SetupReceiveTakeKey(); key != "" {
			t.Errorf("a key that ran out of time was handed out: %d bytes", len(key))
		}
		if k := heldKey(); len(k) != 0 {
			t.Errorf("still held: %d bytes", len(k))
		}
	})

	// A bundle has its own time, counted from its arrival: it outlives the
	// code it came for.
	t.Run("the code's time ends with a bundle held", func(t *testing.T) {
		cfg := testSetupConfig()
		cfg.lifetime = time.Second
		started := time.Now()
		rx := startTestSetup(t, cfg)
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
			t.Fatalf("send: %+v", tx)
		}
		if st := rxNow(t); st.State != setupReceived || st.ExpiresIn < 25 {
			t.Errorf("with the bundle held: %+v", st)
		}
		time.Sleep(time.Until(started.Add(cfg.lifetime + 100*time.Millisecond)))
		if st := rxNow(t); st.State != setupReceived || st.ExpiresIn < 25 {
			t.Errorf("past the code's lifetime: %+v", st)
		}
	})
}

// What a sender is told once a session has a bundle, and once it is over.
func TestSetupHoldAnswers(t *testing.T) {
	setupOnLoopback(t)
	rx := startTestSetup(t, testSetupConfig())
	setupMu.Lock()
	s := setupRecv
	setupMu.Unlock()
	_, first := testRequest(t, rx.Payload, testSetupKey)
	_, second := testRequest(t, rx.Payload, testSetupKey)
	from := netip.MustParseAddr("127.0.0.1")
	bundle := func() setupBundle { return setupBundle{key: []byte(testSetupKey)} }

	h, answer := s.hold(nil, from, bundle(), first)
	if h == nil || answer != setupSaysHeld {
		t.Fatalf("the first bundle: answer %#x", answer)
	}
	nothingListens(t, setupAddr(rx))
	if h, answer := s.hold(nil, from, bundle(), second); h != nil || answer != setupSaysTaken {
		t.Errorf("another bundle while one is held: answer %#x", answer)
	}
	if h, answer := s.hold(nil, from, bundle(), first); h != nil || answer != 0 {
		t.Errorf("the held request again: answer %#x", answer)
	}
	if st := rxNow(t); st.State != setupReceived || st.Code != first.code {
		t.Errorf("the session: %+v", st)
	}

	SetupReceiveReject()
	if v := <-h.verdict; v != setupSaysRejected {
		t.Errorf("verdict %#x", v)
	}
	if h, answer := s.hold(nil, from, bundle(), second); h != nil || answer != setupSaysClosed {
		t.Errorf("a bundle after the session ended: answer %#x", answer)
	}
	if h, answer := s.hold(nil, from, bundle(), first); h != nil || answer != 0 {
		t.Errorf("the held request again, after the end: answer %#x", answer)
	}
}

func TestSetupTooManyAttempts(t *testing.T) {
	setupOnLoopback(t)
	cfg := testSetupConfig()
	cfg.maxFailures = 3
	rx := startTestSetup(t, cfg)

	// Connections that say nothing: a phone trying the code's other addresses.
	for range 2 * cfg.maxFailures {
		c := dialSetup(t, setupAddr(rx))
		c.Close()
	}
	time.Sleep(50 * time.Millisecond)
	if st := rxNow(t); st.State != setupWaiting || st.FailedAttempts != 0 {
		t.Fatalf("silent connections were counted: %+v", st)
	}

	for i := 1; i <= cfg.maxFailures; i++ {
		c := dialSetup(t, setupAddr(rx))
		c.Write([]byte("GET / HTTP/1.1\r\nHost: 192.168.1.50\r\nAccept: */*\r\n\r\n"))
		if out, _ := io.ReadAll(c); len(out) != 0 {
			t.Fatalf("garbage was answered: %q", out)
		}
		if i < cfg.maxFailures {
			waitFor(t, "the attempt to be counted", func() bool { return rxNow(t).FailedAttempts == i })
			if st := rxNow(t); st.State != setupWaiting {
				t.Fatalf("after %d: %+v", i, st)
			}
		}
	}
	waitRx(t, setupFailed, setupTooMany)
	nothingListens(t, setupAddr(rx))
	if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupClosed {
		t.Errorf("sending to a dead session: %+v", tx)
	}
}

// Whatever arrives that is not a request sealed for this session gets no
// answer and counts once.
func TestSetupMalformedRequests(t *testing.T) {
	setupOnLoopback(t)
	cfg := testSetupConfig()
	cfg.maxFailures = 1000
	cfg.readTimeout = 300 * time.Millisecond
	rx := startTestSetup(t, cfg)
	valid, _ := testRequest(t, rx.Payload, testSetupKey)
	random := func(n int) []byte {
		b := make([]byte, n)
		rand.Read(b)
		return b
	}
	header := func(magic string, enc []byte, size int) []byte {
		h := append([]byte(magic), enc...)
		return binary.BigEndian.AppendUint16(h, uint16(size))
	}
	edit := func(at int, f func(byte) byte) []byte {
		b := bytes.Clone(valid)
		b[at] = f(b[at])
		return b
	}
	flip := func(b byte) byte { return b ^ 0x40 }
	enc := valid[len(setupMagic) : len(setupMagic)+setupKeyLen]
	oneBlock := setupPadBlock + setupSealOverhead

	for _, tc := range []struct {
		name string
		data []byte
		// stall leaves the connection open, so only the deadline ends it.
		stall bool
	}{
		{name: "one byte", data: []byte("T")},
		{name: "one byte and silence", data: []byte("T"), stall: true},
		{name: "half a header", data: valid[:setupHeaderLen/2]},
		{name: "a web request", data: []byte("POST /p1 HTTP/1.1\r\nHost: tv\r\nContent-Length: 4\r\n\r\nabcd")},
		{name: "another version's magic", data: append([]byte("TSP2"), valid[4:]...)},
		{name: "lower-case magic", data: append([]byte("tsp1"), valid[4:]...)},
		{name: "length zero", data: header(setupMagic, enc, 0)},
		{name: "length of a tag alone", data: header(setupMagic, enc, setupSealOverhead)},
		{name: "length not a whole block", data: append(header(setupMagic, enc, oneBlock+1), random(oneBlock+1)...)},
		{name: "length one block over the cap", data: append(header(setupMagic, enc, setupMaxPlain+setupPadBlock+setupSealOverhead), random(2048)...)},
		{name: "the longest length there is", data: append(header(setupMagic, enc, 0xffff), random(0xffff)...)},
		{name: "a body cut short", data: valid[:len(valid)-10]},
		{name: "a body cut short and silence", data: valid[:len(valid)-10], stall: true},
		{name: "a header and no body", data: valid[:setupHeaderLen]},
		{name: "random bytes of the right shape", data: append(header(setupMagic, random(setupKeyLen), oneBlock), random(oneBlock)...)},
		{name: "a key of small order", data: append(header(setupMagic, make([]byte, setupKeyLen), oneBlock), valid[setupHeaderLen:]...)},
		{name: "a bit of the phone's key flipped", data: edit(len(setupMagic)+3, flip)},
		{name: "a bit of the bundle flipped", data: edit(setupHeaderLen+20, flip)},
		{name: "a bit of the tag flipped", data: edit(len(valid)-1, flip)},
		{name: "another length of the same class", data: append(header(setupMagic, enc, 2*oneBlock-setupSealOverhead), append(bytes.Clone(valid[setupHeaderLen:]), random(setupPadBlock)...)...)},
	} {
		before := rxNow(t).FailedAttempts
		c := dialSetup(t, setupAddr(rx))
		// The television may hang up before all of it is written.
		c.Write(tc.data)
		if !tc.stall {
			c.CloseWrite()
		}
		started := time.Now()
		out, _ := io.ReadAll(c)
		if len(out) != 0 {
			t.Errorf("%s: answered with %d bytes", tc.name, len(out))
		}
		if took := time.Since(started); took > 2*time.Second {
			t.Errorf("%s: the connection was kept for %v", tc.name, took)
		}
		waitFor(t, tc.name+" to be counted", func() bool { return rxNow(t).FailedAttempts == before+1 })
		if st := rxNow(t); st.State != setupWaiting {
			t.Fatalf("%s: the session is %+v", tc.name, st)
		}
	}

	// And after all that, the request that is one.
	c := dialSetup(t, setupAddr(rx))
	c.Write(valid)
	reply := make([]byte, setupReplyLen)
	if _, err := io.ReadFull(c, reply); err != nil || reply[0] != setupSaysHeld {
		t.Fatalf("the valid request: %v, %#x", err, reply[0])
	}
}

// A bundle that is sealed properly but cannot be used: its sender read the
// code, so they are told, and the session goes on waiting.
func TestSetupUnusableBundles(t *testing.T) {
	setupOnLoopback(t)
	rx := startTestSetup(t, testSetupConfig())
	code, err := parseSetupCode(rx.Payload)
	if err != nil {
		t.Fatal(err)
	}
	good, err := encodeSetupBundle(setupBundle{key: []byte(testSetupKey), server: testSetupServer, name: "Pixel"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseSetupBundle(good); err != nil {
		t.Fatal(err)
	}
	edit := func(f func(b []byte)) []byte {
		b := bytes.Clone(good)
		f(b)
		return b
	}
	raw := func(key, server, name string) []byte {
		b := []byte{setupBundleVersion}
		b = binary.BigEndian.AppendUint16(b, uint16(len(key)))
		b = append(b, key...)
		b = append(b, byte(len(server)))
		b = append(b, server...)
		b = append(b, byte(len(name)))
		b = append(b, name...)
		return append(b, make([]byte, (setupPadBlock-len(b)%setupPadBlock)%setupPadBlock)...)
	}
	for name, plain := range map[string][]byte{
		"another version":           edit(func(b []byte) { b[0] = 2 }),
		"version zero":              edit(func(b []byte) { b[0] = 0 }),
		"padding that is not zeros": edit(func(b []byte) { b[len(b)-1] = 1 }),
		"a key longer than the rest": edit(func(b []byte) {
			binary.BigEndian.PutUint16(b[1:], uint16(len(b)))
		}),
		"no key":                   raw("", "", ""),
		"a key with a space":       raw("tskey-auth with space", "", ""),
		"a key with a line end":    raw("tskey-auth-x\n", "", ""),
		"a key in another script":  raw("ключ", "", ""),
		"a key beyond the limit":   raw(strings.Repeat("k", setupMaxKey+1), "", ""),
		"a server that is no URL":  raw("k", "hs.example.com", ""),
		"a server of another kind": raw("k", "ftp://hs.example.com", ""),
		"a server with a password": raw("k", "https://admin:pw@hs.example.com", ""),
		"a server with a query":    raw("k", "https://hs.example.com/?x=1", ""),
		"a look-alike server":      raw("k", "https://hеadscale.example.com", ""),
		"a name that is not text":  raw("k", "", "\xff\xfe"),
		"a name beyond the limit":  raw("k", "", strings.Repeat("n", setupMaxNameBytes+1)),
		"all zeros":                make([]byte, setupPadBlock),
	} {
		if _, err := parseSetupBundle(plain); err == nil {
			t.Errorf("%s: parsed", name)
			continue
		}
		request, x, err := sealSetupRequest(code, plain)
		if err != nil {
			t.Fatal(err)
		}
		c := dialSetup(t, setupAddr(rx))
		c.Write(request)
		reply := make([]byte, setupReplyLen)
		if _, err := io.ReadFull(c, reply); err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if kind, ok := x.check(reply, setupSaysUnsupported); !ok {
			t.Errorf("%s: answered %#x", name, kind)
		}
		if st := rxNow(t); st.State != setupWaiting || st.FailedAttempts != 0 {
			t.Fatalf("%s: the session is %+v", name, st)
		}
	}
	if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
		t.Errorf("a usable bundle afterwards: %+v", tx)
	}
}

func TestSetupCancel(t *testing.T) {
	setupOnLoopback(t)

	t.Run("while waiting", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		SetupReceiveCancel()
		if st := rxNow(t); !equalRx(st, setupRxStatus{State: setupIdle, Addresses: []string{}}) {
			t.Errorf("after cancel: %+v", st)
		}
		nothingListens(t, setupAddr(rx))
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupClosed {
			t.Errorf("sending to a cancelled code: %+v", tx)
		}
		SetupReceiveCancel() // with nothing to cancel
		for _, call := range []func() string{SetupReceiveConfirm, SetupReceiveReject} {
			if st := decodeRx(t, call()); st.State != setupIdle || st.Error == "" {
				t.Errorf("with no session: %+v", st)
			}
		}
	})

	t.Run("while a bundle waits", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
			t.Fatalf("send: %+v", tx)
		}
		SetupReceiveCancel()
		waitTx(t, setupCancelled)
		if st := rxNow(t); st.State != setupIdle {
			t.Errorf("after cancel: %+v", st)
		}
		if key := SetupReceiveTakeKey(); key != "" {
			t.Error("a cancelled key was handed out")
		}
	})

	// The screen may go away between the yes and the hand-over.
	t.Run("after the confirmation", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		sendTestSetup(rx.Payload, testSetupKey, "")
		SetupReceiveConfirm()
		SetupReceiveCancel()
		if st := rxNow(t); st.State != setupConfirmed {
			t.Fatalf("a cancel after the confirmation: %+v", st)
		}
		if key := SetupReceiveTakeKey(); key != testSetupKey {
			t.Errorf("the app was handed %q", key)
		}
		waitTx(t, setupConfirmed)
		// And once the key is taken, a cancel clears what is left to show.
		SetupReceiveCancel()
		if st := rxNow(t); st.State != setupIdle {
			t.Errorf("a cancel after the hand-over: %+v", st)
		}
	})

	t.Run("a yes taken back", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		sendTestSetup(rx.Payload, testSetupKey, "")
		SetupReceiveConfirm()
		if st := decodeRx(t, SetupReceiveReject()); st.State != setupRejected || st.Error != "" {
			t.Fatalf("reject after confirm: %+v", st)
		}
		waitTx(t, setupRejected)
		if key := SetupReceiveTakeKey(); key != "" {
			t.Error("a key that was taken back was handed out")
		}
		if k := heldKey(); len(k) != 0 {
			t.Errorf("still held: %d bytes", len(k))
		}
	})

	// A screen that is built twice asks twice.
	t.Run("starting again is not starting over", func(t *testing.T) {
		first := startTestSetup(t, testSetupConfig())
		if again := startSetupRx(testSetupConfig()); !equalRx(again, first) {
			t.Fatalf("a second start gave %+v, the session is %+v", again, first)
		}
		tx := sendTestSetup(first.Payload, testSetupKey, "")
		if tx.State != setupAwaiting {
			t.Fatalf("send: %+v", tx)
		}
		if again := startSetupRx(testSetupConfig()); again.State != setupReceived || again.Code != tx.Code {
			t.Fatalf("a start while a bundle waits: %+v", again)
		}
		SetupReceiveConfirm()
		if again := startSetupRx(testSetupConfig()); again.State != setupConfirmed || again.Code != tx.Code {
			t.Fatalf("a start while a key waits: %+v", again)
		}

		// A new code is a cancel and a start — a reject first, with a key
		// that was already confirmed.
		SetupReceiveReject()
		waitTx(t, setupRejected)
		SetupReceiveCancel()
		fresh := startSetupRx(testSetupConfig())
		if fresh.State != setupWaiting || fresh.Payload == first.Payload || fresh.ID == first.ID || fresh.From != "" || fresh.Code != "" {
			t.Errorf("the new session %+v after %+v", fresh, first)
		}
		if tx := sendTestSetup(first.Payload, testSetupKey, ""); tx.State == setupAwaiting || tx.State == setupTaken {
			t.Errorf("the old code still works: %+v", tx)
		}
		if st := rxNow(t); st.State != setupWaiting || st.FailedAttempts > 1 {
			t.Errorf("the new session after the old code was tried: %+v", st)
		}
	})

	t.Run("the phone stops waiting", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
			t.Fatalf("send: %+v", tx)
		}
		SetupSendCancel()
		if st := txNow(t); st != (setupTxStatus{State: setupIdle}) {
			t.Errorf("after the phone's cancel: %+v", st)
		}
		// The television decides about what it holds.
		if st := rxNow(t); st.State != setupReceived {
			t.Errorf("the television after the phone's cancel: %+v", st)
		}
		if st := decodeRx(t, SetupReceiveConfirm()); st.State != setupConfirmed {
			t.Errorf("confirm: %+v", st)
		}
	})
}

// Several phones read the same code. One bundle is held; the others are told
// so, or find nothing listening.
func TestSetupTwoSenders(t *testing.T) {
	setupOnLoopback(t)

	t.Run("requests on their way together", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		const senders = 4
		type sender struct {
			conn    *net.TCPConn
			request []byte
			x       *setupExchange
			kind    byte
		}
		all := make([]*sender, senders)
		for i := range all {
			s := &sender{conn: dialSetup(t, setupAddr(rx))}
			s.request, s.x = testRequest(t, rx.Payload, testSetupKey+strconv.Itoa(i))
			all[i] = s
		}
		waitAdmitted(t, senders)
		var wg sync.WaitGroup
		for _, s := range all {
			wg.Add(1)
			go func() {
				defer wg.Done()
				s.conn.Write(s.request)
				reply := make([]byte, setupReplyLen)
				if _, err := io.ReadFull(s.conn, reply); err != nil {
					t.Error(err)
					return
				}
				kind, ok := s.x.check(reply, setupSaysHeld, setupSaysTaken)
				if !ok {
					t.Errorf("answer %#x", kind)
				}
				s.kind = kind
			}()
		}
		wg.Wait()
		winner := -1
		for i, s := range all {
			if s.kind == setupSaysHeld {
				if winner >= 0 {
					t.Fatalf("senders %d and %d were both told their bundle is held", winner, i)
				}
				winner = i
			}
		}
		if winner < 0 {
			t.Fatal("nobody's bundle is held")
		}
		st := rxNow(t)
		if st.State != setupReceived || st.Code != all[winner].x.code {
			t.Errorf("the television shows %+v, the winner's code is %s", st, all[winner].x.code)
		}
		for i, s := range all {
			if i != winner && s.x.code == st.Code {
				t.Errorf("sender %d shares the winner's check code", i)
			}
		}
		SetupReceiveConfirm()
		if key := SetupReceiveTakeKey(); key != testSetupKey+strconv.Itoa(winner) {
			t.Errorf("the app was handed %q, the winner is sender %d", key, winner)
		}
	})

	t.Run("whole sends at once", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		code, err := parseSetupCode(rx.Payload)
		if err != nil {
			t.Fatal(err)
		}
		const senders = 6
		results := make([]setupTxStatus, senders)
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		var wg sync.WaitGroup
		start := make(chan struct{})
		for i := range results {
			wg.Add(1)
			go func() {
				defer wg.Done()
				plain, _ := encodeSetupBundle(setupBundle{key: []byte(testSetupKey + strconv.Itoa(i))})
				<-start
				_, _, results[i] = setupDeliver(ctx, testSendConfig(), code, plain)
			}()
		}
		close(start)
		wg.Wait()
		winner := -1
		for i, r := range results {
			switch r.State {
			case setupAwaiting:
				if winner >= 0 {
					t.Fatalf("senders %d and %d both await a decision", winner, i)
				}
				winner = i
			case setupTaken, setupClosed:
				if r.Code != "" {
					t.Errorf("sender %d lost and has a code: %+v", i, r)
				}
			case setupNoAnswer:
				// Connected, and the listener closed before it was read.
			default:
				t.Errorf("sender %d: %+v", i, r)
			}
		}
		if winner < 0 {
			t.Fatalf("nobody won: %+v", results)
		}
		if st := rxNow(t); st.Code != results[winner].Code {
			t.Errorf("the television shows %s, the winner %s", st.Code, results[winner].Code)
		}
	})
}

// Nothing of a session listens once the bridge has let go of its daemon. A
// key that was confirmed is past listening, and is left for the app to take.
func TestSetupDaemonStop(t *testing.T) {
	setupOnLoopback(t)

	t.Run("while waiting", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		releaseGoResources()
		waitRx(t, setupFailed, setupDaemonStop)
		nothingListens(t, setupAddr(rx))
	})

	t.Run("while a bundle waits", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		sendTestSetup(rx.Payload, testSetupKey, "")
		releaseGoResources()
		waitRx(t, setupFailed, setupDaemonStop)
		waitTx(t, setupCancelled)
		if k := heldKey(); len(k) != 0 {
			t.Errorf("still held: %d bytes", len(k))
		}
	})

	t.Run("a confirmed key", func(t *testing.T) {
		rx := startTestSetup(t, testSetupConfig())
		sendTestSetup(rx.Payload, testSetupKey, "")
		SetupReceiveConfirm()
		releaseGoResources()
		if st := rxNow(t); st.State != setupConfirmed {
			t.Fatalf("after the stop: %+v", st)
		}
		if key := SetupReceiveTakeKey(); key != testSetupKey {
			t.Errorf("after the stop the app was handed %q", key)
		}
	})
}

func TestSetupNetworkChange(t *testing.T) {
	setupOnLoopback(t)
	elsewhere := []setupLocal{{name: "wlan0", prefix: netip.MustParsePrefix("192.168.7.7/24")}}

	t.Run("the address is gone", func(t *testing.T) {
		var gone atomic.Bool
		cfg := testSetupConfig()
		cfg.locals = func() ([]setupLocal, bool) {
			if gone.Load() {
				return elsewhere, true
			}
			return loopbackLocals()
		}
		rx := startTestSetup(t, cfg)
		setupNetworkChanged()
		time.Sleep(30 * time.Millisecond)
		if st := rxNow(t); st.State != setupWaiting {
			t.Fatalf("a change that left the address alone: %+v", st)
		}
		gone.Store(true)
		setupNetworkChanged()
		waitRx(t, setupFailed, setupNetChanged)
		nothingListens(t, setupAddr(rx))
	})

	t.Run("noticed without being told", func(t *testing.T) {
		var gone atomic.Bool
		cfg := testSetupConfig()
		cfg.addrCheck = 20 * time.Millisecond
		cfg.locals = func() ([]setupLocal, bool) {
			if gone.Load() {
				return elsewhere, true
			}
			return loopbackLocals()
		}
		rx := startTestSetup(t, cfg)
		gone.Store(true)
		waitRx(t, setupFailed, setupNetChanged)
		nothingListens(t, setupAddr(rx))
	})

	t.Run("the system will not say", func(t *testing.T) {
		var blind atomic.Bool
		cfg := testSetupConfig()
		cfg.addrCheck = 10 * time.Millisecond
		cfg.locals = func() ([]setupLocal, bool) {
			if blind.Load() {
				return nil, false
			}
			return loopbackLocals()
		}
		rx := startTestSetup(t, cfg)
		blind.Store(true)
		time.Sleep(60 * time.Millisecond)
		if st := rxNow(t); st.State != setupWaiting {
			t.Fatalf("ended on no information: %+v", st)
		}
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting {
			t.Errorf("send: %+v", tx)
		}
	})

	t.Run("one of two addresses goes", func(t *testing.T) {
		both := []setupLocal{
			{name: "eth0", prefix: netip.MustParsePrefix("127.0.0.1/8")},
			{name: "wlan0", prefix: netip.MustParsePrefix("127.0.0.2/8")},
		}
		var one atomic.Bool
		cfg := testSetupConfig()
		cfg.locals = func() ([]setupLocal, bool) {
			if one.Load() {
				return both[:1], true
			}
			return both, true
		}
		resetSetup()
		rx := startSetupRx(cfg)
		if rx.State != setupWaiting || !slices.Equal(rx.Addresses, []string{"127.0.0.1", "127.0.0.2"}) {
			t.Skipf("this machine does not listen on 127.0.0.2: %+v", rx)
		}
		lost := net.JoinHostPort("127.0.0.2", strconv.Itoa(rx.Port))
		c, err := net.DialTimeout("tcp4", lost, time.Second)
		if err != nil {
			t.Fatalf("the second address: %v", err)
		}
		c.Close()
		one.Store(true)
		setupNetworkChanged()
		waitFor(t, "the lost address to be dropped", func() bool { return len(rxNow(t).Addresses) == 1 })
		st := rxNow(t)
		if st.State != setupWaiting || st.Addresses[0] != "127.0.0.1" || st.Payload != rx.Payload {
			t.Errorf("after losing one address: %+v", st)
		}
		nothingListens(t, lost)
		// The code still lists both; the phone uses the one that answers.
		if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx.State != setupAwaiting || tx.Address != setupAddr(rx) {
			t.Errorf("send: %+v", tx)
		}
	})

	t.Run("no local network at all", func(t *testing.T) {
		cfg := testSetupConfig()
		cfg.locals = func() ([]setupLocal, bool) {
			return []setupLocal{{name: "tailscale0", prefix: netip.MustParsePrefix("100.101.102.103/32")}, {name: "rmnet0", prefix: netip.MustParsePrefix("8.8.4.4/30")}}, true
		}
		resetSetup()
		if st := startSetupRx(cfg); st.State != setupFailed || st.Reason != setupNoLAN || st.Payload != "" {
			t.Errorf("started without a LAN address: %+v", st)
		}
		if st := rxNow(t); st.State != setupFailed || st.Reason != setupNoLAN {
			t.Errorf("status: %+v", st)
		}
	})
}

// What the phone can tell about a television it did not reach.
func TestSetupSendOutcomes(t *testing.T) {
	setupOnLoopback(t)
	kem := hpke.DHKEM(ecdh.X25519())
	priv, err := kem.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).AddrPort().Port()
	payload, raw := encodeSetupCode(priv.PublicKey().Bytes(), make([]byte, setupSecretLen), port, []netip.Addr{netip.MustParseAddr("127.0.0.1")})

	// A television that says what the test tells it to: one answer to the
	// request, and after it, if there is one, a verdict.
	var script atomic.Pointer[func(x *setupExchange) [][]byte]
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				header, sealed, _, err := readSetupRequest(c)
				if err != nil {
					return
				}
				_, x, err := openSetupRequest(priv, setupInfo(raw), header, sealed)
				if err != nil {
					return
				}
				for _, answer := range (*script.Load())(x) {
					c.Write(answer)
				}
			}()
		}
	}()
	says := func(kinds ...byte) *func(x *setupExchange) [][]byte {
		f := func(x *setupExchange) (out [][]byte) {
			for _, k := range kinds {
				out = append(out, x.reply(k))
			}
			return out
		}
		return &f
	}
	for _, tc := range []struct {
		name      string
		answers   *func(x *setupExchange) [][]byte
		want      string
		wantCode  bool
		wantFinal string
	}{
		{name: "taken", answers: says(setupSaysTaken), want: setupTaken},
		{name: "unsupported", answers: says(setupSaysUnsupported), want: setupUnsupported},
		{name: "closed", answers: says(setupSaysClosed), want: setupClosed},
		// No answer, and no check code to compare with anything: that is shown
		// only for a bundle the television said it holds.
		{name: "nothing", answers: says(), want: setupNoAnswer},
		// Out of place, or not the television's at all: no answer.
		{name: "a verdict for an answer", answers: says(setupSaysConfirmed), want: setupNoAnswer},
		{name: "a kind there is none of", answers: says(0x7f), want: setupNoAnswer},
		{name: "a tag of all ones", answers: func() *func(x *setupExchange) [][]byte {
			f := func(*setupExchange) [][]byte { return [][]byte{bytes.Repeat([]byte{setupSaysHeld}, setupReplyLen)} }
			return &f
		}(), want: setupNoAnswer},
		{name: "a tag cut short", answers: func() *func(x *setupExchange) [][]byte {
			f := func(x *setupExchange) [][]byte { return [][]byte{x.reply(setupSaysHeld)[:setupReplyLen-1]} }
			return &f
		}(), want: setupNoAnswer},
		{name: "held, then yes", answers: says(setupSaysHeld, setupSaysConfirmed), want: setupAwaiting, wantCode: true, wantFinal: setupConfirmed},
		{name: "held, then no", answers: says(setupSaysHeld, setupSaysRejected), want: setupAwaiting, wantCode: true, wantFinal: setupRejected},
		{name: "held, then too late", answers: says(setupSaysHeld, setupSaysExpired), want: setupAwaiting, wantCode: true, wantFinal: setupExpired},
		{name: "held, then cancelled", answers: says(setupSaysHeld, setupSaysCancelled), want: setupAwaiting, wantCode: true, wantFinal: setupCancelled},
		{name: "held, then silence", answers: says(setupSaysHeld), want: setupAwaiting, wantCode: true, wantFinal: setupLost},
		// The acknowledgement again is not a verdict.
		{name: "held, then held", answers: says(setupSaysHeld, setupSaysHeld), want: setupAwaiting, wantCode: true, wantFinal: setupLost},
		{name: "held, then taken", answers: says(setupSaysHeld, setupSaysTaken), want: setupAwaiting, wantCode: true, wantFinal: setupLost},
	} {
		SetupSendCancel()
		script.Store(tc.answers)
		tx := sendTestSetup(payload, testSetupKey, "")
		// "awaiting" may already have become what followed it.
		if tx.State != tc.want || (tx.Code != "") != tc.wantCode {
			t.Errorf("%s: %+v, want %s", tc.name, tx, tc.want)
			continue
		}
		if tc.wantFinal != "" {
			waitTx(t, tc.wantFinal)
			if st := txNow(t); st.Code != tx.Code || st.Address != tx.Address {
				t.Errorf("%s: %+v after %+v", tc.name, st, tx)
			}
		}
	}
	SetupSendCancel()
	ln.Close()
	if tx := sendTestSetup(payload, testSetupKey, ""); tx.State != setupClosed {
		t.Errorf("nothing listening: %+v", tx)
	}

	// A dial that cannot finish, with what the phone knows of its networks.
	for name, tc := range map[string]struct {
		locals []setupLocal
		known  bool
		want   string
	}{
		"on the television's network": {[]setupLocal{{"wlan0", netip.MustParsePrefix("127.0.0.9/8")}}, true, setupUnreachable},
		"on another network":          {[]setupLocal{{"wlan0", netip.MustParsePrefix("192.168.1.5/24")}}, true, setupWrongNetwork},
		"on no local network":         {nil, true, setupWrongNetwork},
		"masks unknown":               {[]setupLocal{{"wlan0", netip.MustParsePrefix("192.168.1.5/32")}}, true, setupUnreachable},
		"nothing known":               {nil, false, setupUnreachable},
	} {
		cfg := testSendConfig()
		cfg.dialTimeout = time.Nanosecond
		cfg.locals = func() ([]setupLocal, bool) { return tc.locals, tc.known }
		if tx := setupSendWith(cfg, payload, testSetupKey, "", ""); tx.State != tc.want || tx.Code != "" {
			t.Errorf("%s: %+v, want %s", name, tx, tc.want)
		}
	}

	// Refused before anything is sent.
	for name, tc := range map[string]struct {
		payload, key, server, want string
	}{
		"not a code":             {"https://login.tailscale.com/a/1234", testSetupKey, "", setupBadPayload},
		"a damaged code":         {payload[:len(payload)-1], testSetupKey, "", setupBadPayload},
		"no key":                 {payload, "  ", "", setupBadKey},
		"a key with a space":     {payload, "tskey auth", "", setupBadKey},
		"a key beyond the limit": {payload, strings.Repeat("k", setupMaxKey+1), "", setupBadKey},
		"a server without http":  {payload, testSetupKey, "hs.example.com", setupBadServer},
		"a server with a login":  {payload, testSetupKey, "https://me:pw@hs.example.com", setupBadServer},
	} {
		if tx := sendTestSetup(tc.payload, tc.key, tc.server); tx != (setupTxStatus{State: tc.want}) {
			t.Errorf("%s: %+v, want %s", name, tx, tc.want)
		}
	}

	// One send at a time, and the one in flight is left alone.
	rx := startTestSetup(t, testSetupConfig())
	first := sendTestSetup(rx.Payload, testSetupKey, "")
	if first.State != setupAwaiting {
		t.Fatalf("send: %+v", first)
	}
	if tx := sendTestSetup(rx.Payload, testSetupKey, ""); tx != (setupTxStatus{State: setupBusy}) {
		t.Errorf("a second send: %+v", tx)
	}
	if st := txNow(t); st != first {
		t.Errorf("the first send after the second: %+v", st)
	}
}

func TestSetupParse(t *testing.T) {
	setupOnLoopback(t)
	valid := startTestSetup(t, testSetupConfig()).Payload
	for in, want := range map[string]string{
		valid:                              "",
		"  " + strings.ToLower(valid):      "",
		"":                                 "not_a_setup_code",
		"tailsocks://tailcat":              "not_a_setup_code",
		testSetupKey:                       "not_a_setup_code",
		strings.Repeat("TSP1:", 500):       "not_a_setup_code",
		"TSP2:" + valid[5:]:                "unsupported_version",
		"tsp10:whatever":                   "unsupported_version",
		"TSP1:":                            "malformed",
		valid[:len(valid)-1]:               "malformed",
		valid + "0":                        "malformed",
		valid[:20] + "-" + valid[21:]:      "malformed",
		"TSP1:" + strings.Repeat("Z", 84):  "malformed",
		"TSP1:" + strings.Repeat("0", 84):  "malformed",
		"TSP1:+" + strings.Repeat("1", 83): "malformed",
	} {
		var got setupParsed
		if err := json.Unmarshal([]byte(SetupParse(in)), &got); err != nil {
			t.Fatal(err)
		}
		if got.Error != want || got.OK != (want == "") || got.Addresses == nil {
			t.Errorf("SetupParse(%.40q) = %+v, want error %q", in, got, want)
		}
		if !got.OK && (got.ID != "" || got.Port != 0 || len(got.Addresses) != 0) {
			t.Errorf("SetupParse(%.40q) refused it and still says %+v", in, got)
		}
	}
}

// On whatever network this machine has: a session listens on LAN addresses
// only, or says there is none.
func TestSetupReceiveStartHere(t *testing.T) {
	t.Cleanup(SetupReceiveCancel)
	st := decodeRx(t, SetupReceiveStart())
	switch st.State {
	case setupWaiting:
		code, err := parseSetupCode(st.Payload)
		if err != nil {
			t.Fatalf("the code does not parse: %v", err)
		}
		if len(st.Addresses) == 0 || len(st.Addresses) > setupMaxAddrs || len(code.addrs) != len(st.Addresses) || st.ExpiresIn != int(setupLifetime/time.Second) {
			t.Errorf("status: %+v", st)
		}
		for _, a := range code.addrs {
			if a.IsLoopback() || !(a.IsPrivate() || a.IsLinkLocalUnicast()) {
				t.Errorf("listening on %v", a)
			}
			if !listens(net.JoinHostPort(a.String(), strconv.Itoa(st.Port))) {
				t.Errorf("nothing listens on %v", a)
			}
		}
		// Bound to those addresses and to no other: read off the listeners,
		// since whether some other program has that port on loopback is not
		// this session's doing.
		setupMu.Lock()
		var bound []string
		for _, ln := range setupRecv.lns {
			bound = append(bound, ln.Addr().String())
		}
		setupMu.Unlock()
		var want []string
		for _, a := range code.addrs {
			want = append(want, net.JoinHostPort(a.String(), strconv.Itoa(st.Port)))
		}
		if !slices.Equal(bound, want) {
			t.Errorf("listening on %q, the code says %q", bound, want)
		}
	case setupFailed:
		if st.Reason != setupNoLAN {
			t.Errorf("status: %+v", st)
		}
	default:
		t.Errorf("status: %+v", st)
	}
	SetupReceiveCancel()
	if st := rxNow(t); st.State != setupIdle {
		t.Errorf("after cancel: %+v", st)
	}
}
