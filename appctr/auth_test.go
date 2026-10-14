package appctr

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// The keyless registration: which control server a login ends up on.
//
// The daemon builds its control client at Start, from the ControlURL pref as it
// stands then, and starts by itself as it comes up — so a profile that has no
// session waits for a login on the server it has stored, not on the one in the
// settings. These tests hold the routine to the one in the settings, and to
// leaving a session alone.

func TestControlServerOf(t *testing.T) {
	for _, c := range []struct{ in, want string }{
		{"", defaultControlURL},
		{"  ", defaultControlURL},
		{"https://controlplane.tailscale.com", defaultControlURL},
		{"https://controlplane.tailscale.com/", defaultControlURL},
		// Upstream's other name for the default (ipn.IsLoginServerSynonym).
		{"https://login.tailscale.com", defaultControlURL},
		{"https://hs.example.com", "https://hs.example.com"},
		{" https://hs.example.com/ ", "https://hs.example.com"},
		{"http://10.0.2.2:28080", "http://10.0.2.2:28080"},
	} {
		if got := controlServerOf(c.in); got != c.want {
			t.Errorf("controlServerOf(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestNextRegisterStep(t *testing.T) {
	for _, c := range []struct {
		name string
		look registerLook
		want registerStep
	}{
		// A session is left alone whatever server the settings name: starting
		// it over or logging in again would cost a working node key.
		{"running, same server", registerLook{state: "Running", serverFits: true}, registerLeave},
		{"running, another server in the settings", registerLook{state: "Running", serverFits: false}, registerLeave},
		{"starting, another server in the settings", registerLook{state: "Starting", serverFits: false}, registerLeave},
		{"stopped", registerLook{state: "Stopped", serverFits: true}, registerResume},
		{"stopped, another server in the settings", registerLook{state: "Stopped", serverFits: false}, registerResume},

		// Waiting for a login on the right server: as it always was.
		{"needs login", registerLook{state: "NeedsLogin", serverFits: true}, registerLogin},
		{"needs login, link out", registerLook{state: "NeedsLogin", serverFits: true, authURL: true}, registerLeave},

		// Waiting for a login on another server: neither the state nor a link
		// that is out is trusted before the backend was started for the right one.
		{"needs login on another server", registerLook{state: "NeedsLogin", serverFits: false}, registerStart},
		{"needs login on another server, its link out", registerLook{state: "NeedsLogin", serverFits: false, authURL: true}, registerStart},
		// Once per attempt: after the Start the client is the one asked for.
		{"needs login, started", registerLook{state: "NeedsLogin", serverFits: false, started: true}, registerLogin},
		{"needs login, started, link out", registerLook{state: "NeedsLogin", serverFits: false, started: true, authURL: true}, registerLeave},

		{"no state", registerLook{state: "NoState", serverFits: true}, registerStart},
		{"no state, started", registerLook{state: "NoState", serverFits: true, started: true}, registerWait},
		{"no state, started, link out", registerLook{state: "NoState", serverFits: true, started: true, authURL: true}, registerLeave},

		{"machine not approved yet", registerLook{state: "NeedsMachineAuth", serverFits: true}, registerWait},
		{"a state it does not know", registerLook{state: "", serverFits: true}, registerWait},
	} {
		if got := nextRegisterStep(c.look); got != c.want {
			t.Errorf("%s: step %d, want %d", c.name, got, c.want)
		}
	}
}

// fakeDaemon answers the LocalAPI requests the registration makes, the way
// tailscaled does where it matters here: the control client is built at Start
// from the ControlURL pref of that moment, a login runs on that client, and a
// Start drops the login link.
type fakeDaemon struct {
	mu sync.Mutex
	fakeState
}

// fakeState is what the fake daemon holds, and what a test reads back.
type fakeState struct {
	state       string
	prefURL     string // the ControlURL pref
	clientURL   string // the server the control client was built for
	authURL     string
	haveNodeKey bool
	wantRunning bool
	authKey     string // the key the last Start carried
	// registeredOn is the server an auth key was registered with.
	registeredOn string
	calls        []string
}

const (
	fakeHeadscale = "http://hs.test:8080"
	fakeOther     = "https://other.example"
)

func (d *fakeDaemon) linkOf(server string) string { return server + "/register/abc" }

// login is the control client asked to log in: with a key it registers, without
// one the server answers with its link.
func (d *fakeDaemon) login() {
	if d.authKey != "" {
		d.registeredOn, d.haveNodeKey, d.state, d.authURL = d.clientURL, true, "Running", ""
		return
	}
	d.authURL = d.linkOf(d.clientURL)
}

func (d *fakeDaemon) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	body, _ := io.ReadAll(r.Body)
	switch r.Method + " " + r.URL.Path {
	case "GET /localapi/v0/status":
		json.NewEncoder(w).Encode(map[string]any{"BackendState": d.state, "AuthURL": d.authURL, "HaveNodeKey": d.haveNodeKey})
	case "GET /localapi/v0/prefs":
		json.NewEncoder(w).Encode(map[string]any{"ControlURL": d.prefURL, "WantRunning": d.wantRunning})
	case "PATCH /localapi/v0/prefs":
		var p struct {
			ControlURL     string
			ControlURLSet  bool
			WantRunning    bool
			WantRunningSet bool
		}
		json.Unmarshal(body, &p)
		if p.ControlURLSet {
			d.prefURL = p.ControlURL
			d.calls = append(d.calls, "prefs ControlURL="+p.ControlURL)
		}
		if p.WantRunningSet {
			d.calls = append(d.calls, "prefs WantRunning")
			// The daemon logs in when WantRunning turns on, on the client it has.
			if p.WantRunning && !d.wantRunning && d.state == "NeedsLogin" {
				d.login()
			}
			d.wantRunning = p.WantRunning
		}
		w.Write([]byte("{}"))
	case "POST /localapi/v0/start":
		var o struct{ AuthKey string }
		json.Unmarshal(body, &o)
		d.clientURL, d.authKey, d.authURL = controlServerOf(d.prefURL), o.AuthKey, ""
		if d.haveNodeKey {
			d.state = "Starting"
		} else {
			d.state = "NeedsLogin"
		}
		d.calls = append(d.calls, "start")
		w.Write([]byte("{}"))
	case "POST /localapi/v0/login-interactive":
		d.calls = append(d.calls, "login on "+d.clientURL)
		// A link that is out is handed back as it is.
		if d.authURL == "" {
			d.login()
		}
		w.Write([]byte("{}"))
	case "POST /localapi/v0/logout":
		// The profile is deleted; its successor has no login server of its own.
		d.calls = append(d.calls, "logout")
		d.haveNodeKey, d.prefURL, d.clientURL, d.authURL, d.wantRunning, d.state = false, "", defaultControlURL, "", false, "NeedsLogin"
		w.Write([]byte("{}"))
	default:
		http.NotFound(w, r)
	}
}

func (d *fakeDaemon) snapshot() fakeState {
	d.mu.Lock()
	defer d.mu.Unlock()
	c := d.fakeState
	c.calls = slices.Clone(d.calls)
	return c
}

func newFakeDaemon(s fakeState) *fakeDaemon { return &fakeDaemon{fakeState: s} }

// serveFakeDaemon puts d behind a Unix socket and points the bridge at it, as
// an attached daemon; the cleanup takes the bridge off it again.
func serveFakeDaemon(t *testing.T, d *fakeDaemon, opt *StartOptions) {
	t.Helper()
	dir := t.TempDir()
	sock := filepath.Join(dir, "d.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: d}
	go srv.Serve(ln)

	oldPoll := registerPollInterval
	registerPollInterval = 5 * time.Millisecond
	stateMu.Lock()
	externalSocketPath = sock
	PC = newPathControl(filepath.Join(dir, "libtailscale.so"), sock, filepath.Join(dir, "state"))
	lastOptions = opt
	stateMu.Unlock()
	resetBusState()

	t.Cleanup(func() {
		stateMu.Lock()
		externalSocketPath = ""
		PC = pathControl{}
		lastOptions = nil
		stateMu.Unlock()
		registerPollInterval = oldPoll
		resetBusState()
		closeLocalClient()
		srv.Close()
	})
}

// indexOf is the position of the first call with this prefix, -1 without one.
func indexOf(calls []string, prefix string) int {
	return slices.IndexFunc(calls, func(c string) bool { return strings.HasPrefix(c, prefix) })
}

// assertStartedFor holds the order the control client depends on: the server
// in the prefs before Start, WantRunning after it.
func assertStartedFor(t *testing.T, got fakeState, server string) {
	t.Helper()
	url, start, want := indexOf(got.calls, "prefs ControlURL="+server), indexOf(got.calls, "start"), indexOf(got.calls, "prefs WantRunning")
	if url < 0 || start < 0 || url > start {
		t.Errorf("the server was not in the prefs before Start: %q", got.calls)
	}
	if want >= 0 && want < start {
		t.Errorf("WantRunning went before Start: %q", got.calls)
	}
	if got.clientURL != controlServerOf(server) {
		t.Errorf("the control client is for %q, want %q", got.clientURL, controlServerOf(server))
	}
}

// A new profile with a login server and no key — the daemon came up waiting for
// a login on the default server, and the link has to be the custom server's.
func TestKeylessLoginOnANewProfileUsesTheLoginServer(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", clientURL: defaultControlURL})
	opt := &StartOptions{LoginServer: fakeHeadscale}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	assertStartedFor(t, got, fakeHeadscale)
	if got.authURL != d.linkOf(fakeHeadscale) {
		t.Errorf("login link %q, want the login server's; calls %q", got.authURL, got.calls)
	}
	if i := indexOf(got.calls, "login on "); i >= 0 && got.calls[i] != "login on "+fakeHeadscale {
		t.Errorf("the login ran on the wrong client: %q", got.calls)
	}
}

// The default server on a new profile is what the daemon started on: no Start,
// one login request, as before.
func TestKeylessLoginOnTheDefaultServerStartsNothingOver(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", clientURL: defaultControlURL})
	opt := &StartOptions{}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	if want := []string{"login on " + defaultControlURL}; !slices.Equal(got.calls, want) {
		t.Errorf("calls %q, want %q", got.calls, want)
	}
}

// A link that is already out came from the server the daemon started on. It is
// not shown as the login server's, and it does not keep the prefs from changing.
func TestALinkOfAnotherServerIsReplaced(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", clientURL: defaultControlURL})
	d.authURL = d.linkOf(defaultControlURL)
	opt := &StartOptions{LoginServer: fakeHeadscale}
	serveFakeDaemon(t, d, opt)
	// The bridge has seen the old link, which makes PatchPrefsJSON a no-op.
	busStateMu.Lock()
	busState.AuthURL = d.authURL
	busStateMu.Unlock()

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	assertStartedFor(t, got, fakeHeadscale)
	if got.authURL != d.linkOf(fakeHeadscale) {
		t.Errorf("login link %q, want the login server's; calls %q", got.authURL, got.calls)
	}
	if indexOf(got.calls, "prefs WantRunning") < 0 {
		t.Errorf("WantRunning was not asked for after the Start: %q", got.calls)
	}
	if shown := GetLoginURL(); shown != d.linkOf(fakeHeadscale) {
		t.Errorf("the bridge shows %q, want the login server's link", shown)
	}
}

// The profile holds a login server the user has since cleared.
func TestKeylessLoginAfterTheLoginServerWasCleared(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", prefURL: fakeHeadscale, clientURL: fakeHeadscale})
	opt := &StartOptions{}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	assertStartedFor(t, got, defaultControlURL)
	if got.authURL != d.linkOf(defaultControlURL) {
		t.Errorf("login link %q, want the default server's; calls %q", got.authURL, got.calls)
	}
}

// A signed-out profile whose login server was changed in the settings.
func TestKeylessLoginAfterTheLoginServerWasChanged(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", prefURL: fakeOther, clientURL: fakeOther})
	opt := &StartOptions{LoginServer: fakeHeadscale}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	assertStartedFor(t, got, fakeHeadscale)
	if got.authURL != d.linkOf(fakeHeadscale) {
		t.Errorf("login link %q, want the new server's; calls %q", got.authURL, got.calls)
	}
}

// A profile with a session is never started over for the settings' server and
// never logged in again.
func TestASessionIsNotMovedToAnotherServer(t *testing.T) {
	for _, state := range []string{"Running", "Starting"} {
		d := newFakeDaemon(fakeState{state: state, prefURL: fakeOther, clientURL: fakeOther, haveNodeKey: true, wantRunning: true})
		opt := &StartOptions{LoginServer: fakeHeadscale}
		serveFakeDaemon(t, d, opt)

		registerMachineWithAuthKey(context.Background(), opt)

		if got := d.snapshot(); len(got.calls) != 0 || got.clientURL != fakeOther {
			t.Errorf("%s: calls %q on a client for %q, want none and %q", state, got.calls, got.clientURL, fakeOther)
		}
	}
}

func TestAStoppedSessionIsOnlyAskedToRun(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "Stopped", prefURL: fakeOther, clientURL: fakeOther, haveNodeKey: true})
	opt := &StartOptions{LoginServer: fakeHeadscale}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	if want := []string{"prefs WantRunning"}; !slices.Equal(got.calls, want) {
		t.Errorf("calls %q, want %q", got.calls, want)
	}
}

// A backend still in NoState gets its one Start and is then left to connect.
func TestABackendInNoStateIsStartedOnceAndNotLoggedIn(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NoState", prefURL: fakeHeadscale, clientURL: fakeHeadscale, haveNodeKey: true, wantRunning: true})
	opt := &StartOptions{LoginServer: fakeHeadscale}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	if n := len(got.calls) - len(slices.DeleteFunc(slices.Clone(got.calls), func(c string) bool { return c == "start" })); n != 1 {
		t.Errorf("%d Starts, want one: %q", n, got.calls)
	}
	if indexOf(got.calls, "login on ") >= 0 {
		t.Errorf("a session was logged in again: %q", got.calls)
	}
}

// "Add account" with a login server and no key: the reset logs the profile out,
// which leaves the daemon on the default server.
func TestAResetLogsInOnTheLoginServer(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", clientURL: defaultControlURL})
	opt := &StartOptions{LoginServer: fakeHeadscale, DoReset: true}
	serveFakeDaemon(t, d, opt)

	registerMachineWithAuthKey(context.Background(), opt)

	got := d.snapshot()
	assertStartedFor(t, got, fakeHeadscale)
	if logout, start := indexOf(got.calls, "logout"), indexOf(got.calls, "start"); logout < 0 || logout > start {
		t.Errorf("the logout did not come first: %q", got.calls)
	}
	if got.calls[len(got.calls)-1] != "login on "+fakeHeadscale {
		t.Errorf("the reset did not end in a login on the login server: %q", got.calls)
	}
	if got.authURL != d.linkOf(fakeHeadscale) {
		t.Errorf("login link %q, want the login server's", got.authURL)
	}
	if opt.DoReset {
		t.Error("the reset was not consumed")
	}
}

// With a key the order was right already; it has to stay so, with the server
// in the prefs even while an old login link is out.
func TestAnAuthKeyRegistersWithTheLoginServer(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", clientURL: defaultControlURL})
	d.authURL = d.linkOf(defaultControlURL)
	opt := &StartOptions{LoginServer: fakeHeadscale, AuthKey: "hskey-auth-test"}
	serveFakeDaemon(t, d, opt)
	busStateMu.Lock()
	busState.AuthURL = d.authURL
	busStateMu.Unlock()

	if answer := Login(opt.AuthKey); answer != "OK" {
		t.Fatalf("Login: %s", answer)
	}

	got := d.snapshot()
	assertStartedFor(t, got, fakeHeadscale)
	if got.registeredOn != fakeHeadscale {
		t.Errorf("the key was registered with %q, want the login server; calls %q", got.registeredOn, got.calls)
	}
}

// An auth key on a profile that still holds a login server the user cleared.
func TestAnAuthKeyRegistersWithTheDefaultServerOnceTheLoginServerIsCleared(t *testing.T) {
	d := newFakeDaemon(fakeState{state: "NeedsLogin", prefURL: fakeHeadscale, clientURL: fakeHeadscale})
	opt := &StartOptions{AuthKey: "tskey-auth-test"}
	serveFakeDaemon(t, d, opt)

	if answer := Login(opt.AuthKey); answer != "OK" {
		t.Fatalf("Login: %s", answer)
	}

	got := d.snapshot()
	assertStartedFor(t, got, defaultControlURL)
	if got.registeredOn != defaultControlURL {
		t.Errorf("the key was registered with %q, want the default server; calls %q", got.registeredOn, got.calls)
	}
}
