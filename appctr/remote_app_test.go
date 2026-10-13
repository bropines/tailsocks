package appctr

// The bridge's dealings with the app's RemoteDelegate: what a paired device is
// told must be what happened, and a caller that has shown no token must not be
// able to make the bridge call into the app at all.

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// hang makes the fake app stop answering until the returned function is called.
func (a *remoteFakeApp) hang() (release func()) {
	block := make(chan struct{})
	a.mu.Lock()
	a.block = block
	a.mu.Unlock()
	return func() {
		a.mu.Lock()
		a.block = nil
		a.mu.Unlock()
		close(block)
	}
}

func (a *remoteFakeApp) value(key string) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.settings[key]
}

// idle waits until no call into the app is in flight.
func (r *remoteRig) idle() {
	r.t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for len(r.m.delegateSlot) != 0 {
		if time.Now().After(deadline) {
			r.t.Fatal("a call into the app never returned")
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// The app refuses, in its own words, and the refusal is what is logged.
func TestRemoteAppRefuses(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	set := map[string]string{"key": "accept_dns", "value": "false"}

	r.app.mu.Lock()
	r.app.fail = errors.New("TUN needs the VPN permission, grant it on the device\nsecond line")
	r.app.mu.Unlock()
	a := r.call(rtPhone, "POST", "/v1/settings", token, set)
	r.want(a, 409, "refused")
	if a.str("message") != "TUN needs the VPN permission, grant it on the devicesecond line" {
		t.Errorf("message: %q", a.str("message"))
	}
	r.want(r.call(rtPhone, "POST", "/v1/actions", token, map[string]string{"action": "reconnect"}), 409, "refused")
	r.want(r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": rtExitA.id}), 409, "refused")
	if ev := r.m.actions()[0]; ev.Op != "exit_node" || ev.OK || ev.Detail != rtExitA.name {
		t.Errorf("a refused change is logged as %+v", ev)
	}
	if r.app.value("accept_dns") != "true" {
		t.Error("a refused change was applied")
	}
	// A setting the app cannot read is marked, the rest still come.
	a = r.call(rtPhone, "GET", "/v1/settings", token, nil)
	if a.status != 200 || !strings.Contains(a.raw, `"error":"unavailable"`) || !strings.Contains(a.raw, `"value":"tv"`) {
		t.Errorf("settings while the app fails: %d %s", a.status, a.raw)
	}
	r.app.mu.Lock()
	r.app.fail = nil
	r.app.mu.Unlock()

	// A value the app returns is checked like one a device sends.
	r.app.mu.Lock()
	r.app.settings["accept_routes"] = "tskey-auth-leaked"
	r.app.settings["device_class"] = "<script>"
	r.app.mu.Unlock()
	r.m.setDelegate(r.app)
	r.m.loadFacts()
	a = r.call(rtPhone, "GET", "/v1/settings", token, nil)
	if a.status != 200 || strings.Contains(a.raw, "tskey") || strings.Contains(a.raw, "script") {
		t.Errorf("a value of the wrong kind was passed on: %d %s", a.status, a.raw)
	}
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.str("deviceClass") != "" {
		t.Errorf("hello with a made-up device class: %s", a.raw)
	}

	// No app registered at all: nothing can be changed, and that is said.
	r.m.setDelegate(nil)
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, set), 503, "unavailable")
	r.want(r.call(rtPhone, "GET", "/v1/settings", token, nil), 503, "unavailable")
	r.want(r.call(rtPhone, "POST", "/v1/actions", token, map[string]string{"action": "reconnect"}), 503, "unavailable")
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.status != 200 || a.str("deviceClass") != "" {
		t.Errorf("hello without the app: %d %s", a.status, a.raw)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("status without the app: %d %s", a.status, a.raw)
	}
	if st := r.m.state(); st.Delegate {
		t.Error("state says the app is registered")
	}
	if ev := r.m.actions()[0]; ev.OK || !strings.Contains(ev.Detail, "not asked of the app") {
		t.Errorf("a change that was never made is logged as %+v", ev)
	}
}

// An answer matches what happened. A call into the app cannot be taken back,
// so a request is never told "failed" about a change that may still be made:
// it is told "pending", or — when the call was never started — that nothing
// was changed, which is then true.
func TestRemoteAppAnswerIsWhatHappened(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	routes := map[string]string{"key": "accept_routes", "value": "true"}
	dns := map[string]string{"key": "accept_dns", "value": "false"}

	// The app is slow but answers while the request waits: the request waits,
	// and says what the app said.
	r.m.delegateWait, r.m.delegateAnswer = 200*time.Millisecond, 3*time.Second
	release := r.app.hang()
	go func() {
		time.Sleep(150 * time.Millisecond)
		release()
	}()
	if a := r.call(rtPhone, "POST", "/v1/settings", token, routes); a.status != 200 {
		t.Fatalf("a slow app: %d %s", a.status, a.raw)
	}
	if ev := r.m.actions()[0]; !ev.OK || ev.Detail != "accept_routes=true" || r.app.value("accept_routes") != "true" {
		t.Fatalf("logged %+v, the app has %q", ev, r.app.value("accept_routes"))
	}
	r.idle()
	if a := r.call(rtPhone, "POST", "/v1/settings", token, map[string]string{"key": "accept_routes", "value": "false"}); a.status != 200 {
		t.Fatalf("back to false: %d %s", a.status, a.raw)
	}

	// The app hangs past the wait. The call has been made: nobody knows.
	r.m.delegateWait, r.m.delegateAnswer = 100*time.Millisecond, 150*time.Millisecond
	release = r.app.hang()
	began := time.Now()
	a := r.call(rtPhone, "POST", "/v1/settings", token, routes)
	r.want(a, 504, "pending")
	if !strings.Contains(a.str("message"), "may still be applied") {
		t.Errorf("message: %q", a.str("message"))
	}
	if ev := r.m.actions()[0]; ev.OK || !strings.Contains(ev.Detail, "accept_routes=true") || !strings.Contains(ev.Detail, "no answer") {
		t.Errorf("a pending change is logged as %+v", ev)
	}
	// What comes next is not started behind it — and is told so, truthfully:
	// the app never hears of these.
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, dns), 503, "unavailable")
	r.want(r.call(rtPhone, "POST", "/v1/actions", token, map[string]string{"action": "reconnect"}), 503, "unavailable")
	r.want(r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": rtExitA.id}), 503, "unavailable")
	r.want(r.call(rtPhone, "GET", "/v1/settings", token, nil), 503, "unavailable")
	if took := time.Since(began); took > 5*time.Second {
		t.Errorf("five requests against a hung app took %v", took)
	}
	writes := strings.Join(r.app.writes(), ",")
	if writes != "set accept_routes=true,set accept_routes=false,set accept_routes=true" {
		t.Errorf("calls the app has seen: %s", writes)
	}
	// Status does not wait for the app: it answers, without the app's report.
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] != nil {
		t.Errorf("status while the app hangs: %d %s", a.status, a.raw)
	}

	// The app gets round to it. The change is made — as the answer allowed
	// for — and the log says so; what was refused as busy never is.
	release()
	r.idle()
	deadline := time.Now().Add(3 * time.Second)
	for !strings.Contains(r.m.actions()[0].Detail, "applied late") && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if ev := r.m.actions()[0]; !ev.OK || ev.Op != "setting" || ev.Detail != "accept_routes=true (applied late)" {
		t.Errorf("the late outcome is logged as %+v", ev)
	}
	if r.app.value("accept_routes") != "true" || r.app.value("accept_dns") != "true" || r.app.value(RemoteKeyExitNode) != "" {
		t.Errorf("the app has routes=%q dns=%q exit=%q", r.app.value("accept_routes"), r.app.value("accept_dns"), r.app.value(RemoteKeyExitNode))
	}
	if got := strings.Join(r.app.writes(), ","); got != writes {
		t.Errorf("calls made after the fact: %s", got)
	}

	// Line by line, the log is true: a line that says a change was not made
	// is about a change the app does not have.
	for _, ev := range r.m.actions() {
		if ev.Op != "setting" && ev.Op != "action" && ev.Op != "exit_node" {
			continue
		}
		switch {
		case ev.OK:
		case strings.Contains(ev.Detail, "no answer from the app yet"):
			// Allowed to turn out either way; the late line says which.
		case strings.Contains(ev.Detail, "not asked of the app"):
			if strings.HasPrefix(ev.Detail, "accept_dns=false") && r.app.value("accept_dns") != "true" {
				t.Errorf("%+v, yet the app applied it", ev)
			}
		default:
			t.Errorf("a change logged as failed: %+v", ev)
		}
	}

	// A late failure is told as one too.
	r.m.delegateAnswer = 50 * time.Millisecond
	release = r.app.hang()
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, dns), 504, "pending")
	r.app.mu.Lock()
	r.app.fail = errors.New("no")
	r.app.mu.Unlock()
	release()
	r.idle()
	deadline = time.Now().Add(3 * time.Second)
	for !strings.Contains(r.m.actions()[0].Detail, "failed late") && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if ev := r.m.actions()[0]; ev.OK || ev.Detail != "accept_dns=false (failed late)" || r.app.value("accept_dns") != "true" {
		t.Errorf("the late failure is logged as %+v; the app has dns=%q", ev, r.app.value("accept_dns"))
	}
}

// /v1/hello and /v1/pair are answered to callers that have shown no token.
// Neither may reach into the app — not to ask, and not to wait for it.
func TestRemoteNoTokenNoCallIntoTheApp(t *testing.T) {
	r := newRemoteRig(t)
	// Asked once, when the app registered.
	if got := strings.Join(r.app.called(), ","); got != "get device_class,get app_version" {
		t.Fatalf("calls at registration: %s", got)
	}
	// Any request that waited for the app would wait this long.
	r.m.delegateWait, r.m.delegateAnswer = 3*time.Second, 3*time.Second
	release := r.app.hang()
	began := time.Now()
	for _, n := range []*rtNode{rtFriend, rtPhone, rtTagged, rtFriend, rtFriend} {
		a := r.call(n, "GET", "/v1/hello", "", nil)
		if a.status != 200 || a.str("deviceClass") != "tv" {
			t.Errorf("hello: %d %s", a.status, a.raw)
		}
	}
	code := r.code()
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)}), 403, "wrong")
	if a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("pair while the app hangs: %d %s", a.status, a.raw)
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("seven requests that need nothing from the app took %v with the app hung", took)
	}
	if got := len(r.app.called()); got != 2 {
		t.Errorf("the app was called for a request without a token: %q", r.app.called())
	}
	release()

	// An app that registered and has not been asked yet: hello has no device
	// class to give, and does not go and get one.
	r.m.setDelegate(r.app)
	if a := r.call(rtFriend, "GET", "/v1/hello", "", nil); a.status != 200 || a.str("deviceClass") != "" {
		t.Errorf("hello before the app was asked: %d %s", a.status, a.raw)
	}
	if got := len(r.app.called()); got != 2 {
		t.Errorf("hello asked the app: %q", r.app.called())
	}
	// A request with a token may ask, and then hello has it too.
	token := r.pair(rtPhone)
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.str("deviceClass") != "tv" || a.str("appVersion") != "4.11.0" {
		t.Errorf("status: %d %s", a.status, a.raw)
	}
	if a := r.call(rtFriend, "GET", "/v1/hello", "", nil); a.str("deviceClass") != "tv" {
		t.Errorf("hello after the app was asked: %s", a.raw)
	}
	// The status asked for the two facts again, and for the app's report.
	if got := strings.Join(r.app.called(), ","); got != "get device_class,get app_version,get app_version,get device_class,get report" {
		t.Errorf("calls: %s", got)
	}
}
