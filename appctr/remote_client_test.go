package appctr

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/net/socks5"
)

// rtPhoneSide is the managing phone: the client half pointed at a SOCKS5
// server that stands for the phone's own daemon. Whatever the client dials
// through it lands on the managed device's listener, from a loopback port the
// fake daemon attributes to the phone — the whole path a real request takes,
// with the tailnet in the middle left out.
type rtPhoneSide struct {
	env    *remoteClientEnv
	dialed atomic.Value // the last address the client asked the proxy for
	socks  string
}

func newRtPhoneSide(t *testing.T, r *remoteRig, as *rtNode) *rtPhoneSide {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &rtPhoneSide{socks: ln.Addr().String()}
	p.dialed.Store("")
	srv := &socks5.Server{
		Logf: func(string, ...any) {},
		Dialer: func(_ context.Context, _, addr string) (net.Conn, error) {
			p.dialed.Store(addr)
			return r.dialAs(as)
		},
	}
	go srv.Serve(ln)
	t.Cleanup(func() { ln.Close() })
	// The phone's network map is the same fake tailnet, seen from the phone:
	// the managed device is one of its peers.
	phoneDaemon := newRemoteFakeDaemon()
	phoneDaemon.self = as
	p.env = &remoteClientEnv{
		api:   phoneDaemon,
		socks: func() (string, string, string) { return p.socks, "", "" },
		port:  RemotePort,
	}
	t.Cleanup(p.env.closeIdle)
	return p
}

func TestRemoteClient(t *testing.T) {
	r := newRemoteRig(t)
	phone := newRtPhoneSide(t, r, rtPhone)

	// Probe: is this peer a TailSocks that can be managed, and what is it?
	probe := phone.env.probe(rtSelf.id)
	if !probe.OK || probe.API != 1 || probe.DeviceClass != "tv" || probe.Name != rtSelf.name || probe.Paired || probe.Pairing || probe.Class != "owner" || !probe.NeedsCode {
		t.Fatalf("probe: %+v", probe)
	}
	// It went to the managed node's own address, on the fixed port.
	if got := phone.dialed.Load(); got != rtSelf.ip+":28115" {
		t.Errorf("dialed %v", got)
	}

	// Pair with the code from the other screen.
	code := r.code()
	if p := phone.env.probe(rtSelf.id); !p.Pairing {
		t.Errorf("probe with a code on show: %+v", p)
	}
	bad := phone.env.pair(rtSelf.id, wrongCode(code))
	if bad.OK || bad.Error != "wrong" || bad.AttemptsLeft == nil || *bad.AttemptsLeft != 4 || bad.Status != 403 || bad.Token != "" {
		t.Errorf("a wrong code: %+v", bad)
	}
	if never := phone.env.pair(rtSelf.id, "12345"); never.OK || never.Error != "bad_request" {
		t.Errorf("a malformed code: %+v", never)
	}
	if p := r.m.state().Pairing; p == nil || p.AttemptsLeft != 4 {
		t.Errorf("a malformed code was sent: %+v", p)
	}
	paired := phone.env.pair(rtSelf.id, code)
	if !paired.OK || len(paired.Token) != 43 || paired.Class != "owner" || paired.Left != -1 || paired.Name != rtSelf.name {
		t.Fatalf("pair: %+v", paired)
	}
	if p := phone.env.probe(rtSelf.id); !p.Paired || p.Pairing {
		t.Errorf("probe after pairing: %+v", p)
	}

	// The generic helpers.
	st := phone.env.do(rtSelf.id, "GET", "/v1/status", paired.Token, "", time.Second*5)
	var status remoteStatus
	if st.Status != 200 || st.Error != "" || json.Unmarshal([]byte(st.Body), &status) != nil || status.NodeID != rtSelf.id || status.You.Class != "owner" {
		t.Errorf("status: %+v", st)
	}
	set := phone.env.do(rtSelf.id, "POST", "/v1/settings", paired.Token, `{"key":"accept_routes","value":"true"}`, time.Second*5)
	if set.Status != 200 {
		t.Errorf("set: %+v", set)
	}
	if got := strings.Join(r.app.writes(), ","); got != "set accept_routes=true" {
		t.Errorf("what reached the app: %s", got)
	}
	if denied := phone.env.do(rtSelf.id, "GET", "/v1/status", "not the token", "", time.Second*5); denied.Status != 401 || !strings.Contains(denied.Body, "unauthorized") {
		t.Errorf("a wrong token: %+v", denied)
	}
	if un := phone.env.do(rtSelf.id, "POST", "/v1/unpair", paired.Token, "", time.Second*5); un.Status != 200 {
		t.Errorf("unpair: %+v", un)
	}
	if gone := phone.env.do(rtSelf.id, "GET", "/v1/status", paired.Token, "", time.Second*5); gone.Status != 401 {
		t.Errorf("after unpairing: %+v", gone)
	}

	// A guest's phone learns what it would be.
	friend := newRtPhoneSide(t, r, rtFriend)
	if p := friend.env.probe(rtSelf.id); !p.OK || p.Class != "guest" || !p.NeedsCode {
		t.Errorf("probe from another user's phone: %+v", p)
	}
	g := friend.env.pair(rtSelf.id, r.code())
	if !g.OK || g.Class != "guest" || g.Left != 86400 {
		t.Errorf("guest pairing: %+v", g)
	}
}

// Where the client refuses to send anything at all.
func TestRemoteClientRefusals(t *testing.T) {
	r := newRemoteRig(t)
	phone := newRtPhoneSide(t, r, rtPhone)
	token := r.pair(rtPhone)
	sent := func() string { return phone.dialed.Load().(string) }

	// Not one of this phone's peers: the token goes nowhere.
	for _, id := range []string{"nNOSUCH", "", "100.64.0.1", rtSelf.ip + ":28115", "../" + rtSelf.id, rtPhone.id} {
		if a := phone.env.do(id, "GET", "/v1/status", token, "", time.Second); a.Status != 0 || a.Error != "no_peer" {
			t.Errorf("peer %q: %+v", id, a)
		}
		if p := phone.env.probe(id); p.OK || p.Error != "no_peer" {
			t.Errorf("probe of %q: %+v", id, p)
		}
	}
	// Not a path of the API: it cannot be turned into a request for anything
	// else on that host or another.
	for _, path := range []string{
		"", "/", "v1/status", "/v2/status", "/v1", "/localapi/v0/status", "/v1/../localapi/v0/prefs", "/v1/status\r\nX-Injected: 1",
		"/v1/status HTTP/1.1", "//evil.example/v1/status", "/v1/@evil.example/", "/v1/status#x", `/v1/\..\x`, "/v1/" + strings.Repeat("a", 600),
		"http://evil.example/v1/status",
	} {
		if remotePathOK(path) {
			t.Errorf("path %q accepted", path)
		}
		if a := phone.env.do(rtSelf.id, "GET", path, token, "", time.Second); a.Status != 0 || a.Error != "bad_request" {
			t.Errorf("path %q: %+v", path, a)
		}
	}
	for _, path := range []string{"/v1/status", "/v1/log?lines=100", "/v1/files/delete", "/v1/exit-nodes"} {
		if !remotePathOK(path) {
			t.Errorf("path %q refused", path)
		}
	}
	if got := sent(); got != "" {
		t.Fatalf("something was dialed: %s", got)
	}

	// This phone's TailSocks is not connected: no proxy, and no other way out.
	off := &remoteClientEnv{api: phone.env.api, socks: func() (string, string, string) { return "", "", "" }, port: RemotePort}
	if a := off.do(rtSelf.id, "GET", "/v1/status", token, "", time.Second); a.Status != 0 || a.Error != "not_connected" {
		t.Errorf("without a proxy: %+v", a)
	}
	down := newRemoteFakeDaemon()
	down.down = true
	noDaemon := &remoteClientEnv{api: down, socks: phone.env.socks, port: RemotePort}
	if a := noDaemon.do(rtSelf.id, "GET", "/v1/status", token, "", time.Second); a.Status != 0 || a.Error != "not_connected" {
		t.Errorf("without a daemon: %+v", a)
	}
	if got := sent(); got != "" {
		t.Fatalf("something was dialed: %s", got)
	}

	// The proxy is there but nothing answers behind it.
	dead, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	deadAddr := dead.Addr().String()
	dead.Close()
	gone := &remoteClientEnv{api: phone.env.api, socks: func() (string, string, string) { return deadAddr, "", "" }, port: RemotePort}
	a := gone.do(rtSelf.id, "GET", "/v1/status", token, "", time.Second)
	if a.Status != 0 || a.Error != "unreachable" || a.Message == "" || strings.Contains(a.Message, token) {
		t.Errorf("a dead proxy: %+v", a)
	}
	if p := gone.probe(rtSelf.id); p.OK || p.Error != "unreachable" {
		t.Errorf("probe through a dead proxy: %+v", p)
	}
}

// A peer that answers on the port without being a TailSocks, or is not the
// node it was asked as, is not offered for management.
func TestRemoteProbeOfSomethingElse(t *testing.T) {
	r := newRemoteRig(t)
	for name, answer := range map[string]func(w http.ResponseWriter){
		"a web server":     func(w http.ResponseWriter) { w.Write([]byte("<html>router admin</html>")) },
		"another JSON API": func(w http.ResponseWriter) { w.Write([]byte(`{"app":"something-else","nodeId":"` + rtSelf.id + `"}`)) },
		"not found":        func(w http.ResponseWriter) { http.NotFound(w, nil) },
		"another node's id": func(w http.ResponseWriter) {
			w.Write([]byte(`{"app":"tailsocks","api":1,"nodeId":"nIMPOSTOR","deviceClass":"tv"}`))
		},
		"a redirect": func(w http.ResponseWriter) {
			w.Header().Set("Location", "http://127.0.0.1:1/v1/hello")
			w.WriteHeader(302)
		},
		"a made-up class": nil,
		"an enormous answer": func(w http.ResponseWriter) {
			w.Write([]byte(`{"app":"tailsocks","pad":"` + strings.Repeat("x", 2*remoteMaxAnswer)))
		},
	} {
		t.Run(name, func(t *testing.T) {
			other := http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
				if q.Header.Get("Authorization") != "" {
					t.Error("a probe carried a token")
				}
				if answer == nil {
					w.Write([]byte(`{"app":"tailsocks","api":1,"nodeId":"` + rtSelf.id + `","deviceClass":"mainframe","class":"root","name":"x"}`))
					return
				}
				answer(w)
			})}
			ln, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			go other.Serve(ln)
			defer other.Close()
			// Send the phone's connections to that server instead.
			elsewhere := *r
			elsewhere.addr = ln.Addr().String()
			phone := newRtPhoneSide(t, &elsewhere, rtPhone)
			p := phone.env.probe(rtSelf.id)
			if answer == nil {
				// What it says about itself is taken only as far as it is known.
				if !p.OK || p.DeviceClass != "" || p.Class != "" {
					t.Errorf("probe: %+v", p)
				}
				return
			}
			if p.OK || p.Error != "not_offered" {
				t.Errorf("probe: %+v", p)
			}
		})
	}
}

// The exported functions answer in JSON whatever happens; with no daemon in
// this process that is "not connected".
func TestRemoteClientExported(t *testing.T) {
	for name, out := range map[string]string{
		"probe": RemoteProbe("nPEER"),
		"pair":  RemotePair("nPEER", "123456"),
		"get":   RemoteGet("nPEER", "token", "/v1/status"),
		"post":  RemotePost("nPEER", "token", "/v1/unpair", ""),
	} {
		var v map[string]any
		if err := json.Unmarshal([]byte(out), &v); err != nil || v["error"] != "not_connected" {
			t.Errorf("%s: %s", name, out)
		}
	}
	for name, out := range map[string]string{
		"state":   RemoteStateJSON(),
		"paired":  RemotePairedJSON(),
		"actions": RemoteActionsJSON(),
		"code":    RemoteStartPairing(),
	} {
		if !json.Valid([]byte(out)) {
			t.Errorf("%s: %s", name, out)
		}
	}
	if RemotePairedJSON() != "[]" || RemoteActionsJSON() != "[]" {
		t.Errorf("lists before any run: %s %s", RemotePairedJSON(), RemoteActionsJSON())
	}
	RemoteCancelPairing()
	if RemoteRevoke("nPEER") || RemoteRevokeAll() != 0 {
		t.Error("revoked something before any run")
	}
}
