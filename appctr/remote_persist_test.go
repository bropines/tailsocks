package appctr

// What survives a restart of the app process, and what a damaged file costs.

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRemoteStoreRoundTrip(t *testing.T) {
	r := newRemoteRig(t)
	phone := r.pair(rtPhone)
	friend := r.pair(rtFriend)
	r.clock.advance(time.Hour)
	if a := r.call(rtPhone, "POST", "/v1/settings", phone, map[string]string{"key": "accept_routes", "value": "true"}); a.status != 200 {
		t.Fatalf("a change to log: %d %s", a.status, a.raw)
	}
	before, log := r.m.paired(), r.m.actions()

	// Only the app may read the file, and it holds no token.
	fi, err := os.Stat(r.store)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Errorf("file mode %o, want 600", fi.Mode().Perm())
	}
	if di, _ := os.Stat(filepath.Dir(r.store)); di.Mode().Perm() != 0o700 {
		t.Errorf("directory mode %o, want 700", di.Mode().Perm())
	}
	raw, err := os.ReadFile(r.store)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{phone, friend} {
		if strings.Contains(string(raw), secret) {
			t.Error("the file holds a token")
		}
	}
	if !strings.Contains(string(raw), remoteHashToken(phone)) {
		t.Error("the file does not hold the token's hash")
	}
	if left, _ := filepath.Glob(filepath.Join(filepath.Dir(r.store), "*.tmp")); len(left) != 0 {
		t.Errorf("temporary files left behind: %q", left)
	}

	// The app process starts again: same list, same log, same tokens.
	r.restart()
	after := r.m.paired()
	if len(after) != 2 || fmt.Sprint(after) != fmt.Sprint(before) {
		t.Errorf("paired list\n before %+v\n after  %+v", before, after)
	}
	if got := r.m.actions(); fmt.Sprint(got) != fmt.Sprint(log) {
		t.Errorf("action log\n before %+v\n after  %+v", log, got)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", phone, nil); a.status != 200 {
		t.Errorf("the owner's token after a restart: %d %s", a.status, a.raw)
	}
	if a := r.call(rtFriend, "GET", "/v1/status", friend, nil); a.status != 200 {
		t.Errorf("the guest's token after a restart: %d %s", a.status, a.raw)
	}
	// A pending code does not survive a restart; the pairings do.
	if r.m.state().Pairing != nil {
		t.Error("a code came back from disk")
	}
}

// A damaged file lets nobody in: it is set aside and the list starts empty.
func TestRemoteStoreCorrupt(t *testing.T) {
	good := func(t *testing.T) (*remoteRig, string, []byte) {
		r := newRemoteRig(t)
		token := r.pair(rtPhone)
		r.m.configure(false, "")
		raw, err := os.ReadFile(r.store)
		if err != nil {
			t.Fatal(err)
		}
		return r, token, raw
	}
	for name, damage := range map[string]func(raw []byte) []byte{
		"not JSON":  func([]byte) []byte { return []byte("\x00\x01 not json") },
		"empty":     func([]byte) []byte { return nil },
		"cut short": func(raw []byte) []byte { return raw[:len(raw)/2] },
		"an array":  func([]byte) []byte { return []byte(`[]`) },
		"a future version": func(raw []byte) []byte {
			return []byte(strings.Replace(string(raw), `"version": 1`, `"version": 2`, 1))
		},
		"no version at all": func(raw []byte) []byte { return []byte(strings.Replace(string(raw), `"version": 1,`, ``, 1)) },
		"pairings a string": func([]byte) []byte { return []byte(`{"version":1,"pairings":"all of them"}`) },
	} {
		t.Run(name, func(t *testing.T) {
			r, token, raw := good(t)
			if err := os.WriteFile(r.store, damage(raw), 0o600); err != nil {
				t.Fatal(err)
			}
			r.restart()
			if list := r.m.paired(); len(list) != 0 {
				t.Fatalf("pairings read from a damaged file: %+v", list)
			}
			r.want(r.call(rtPhone, "GET", "/v1/status", token, nil), 401, "unauthorized")
			if _, err := os.Stat(r.store + ".corrupt"); err != nil {
				t.Errorf("the damaged file was not kept aside: %v", err)
			}
			if !r.hasOp("reset") {
				t.Errorf("the reset is not in the log: %q", r.ops())
			}
			// And the device is usable again from there.
			fresh := r.pair(rtPhone)
			r.restart()
			if a := r.call(rtPhone, "GET", "/v1/status", fresh, nil); a.status != 200 {
				t.Errorf("a pairing made after the reset: %d %s", a.status, a.raw)
			}
		})
	}
}

// A store that cannot be written grants nothing.
func TestRemoteStoreUnwritable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root writes anywhere")
	}
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	dir := filepath.Dir(r.store)
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(dir, 0o700) })

	code := r.code()
	a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code})
	r.want(a, 500, "unavailable")
	if a.str("token") != "" {
		t.Error("a token was handed out for a pairing that was not saved")
	}
	if list := r.m.paired(); len(list) != 1 || list[0].NodeID != rtPhone.id {
		t.Errorf("paired: %+v", list)
	}
	// A device that was pairing again keeps what it had.
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}), 500, "unavailable")
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("the pairing that was on disk: %d %s", a.status, a.raw)
	}
	// The code was not spent on the failure.
	os.Chmod(dir, 0o700)
	if a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("the same code once the disk works: %d %s", a.status, a.raw)
	}
}

func TestRemoteStorePath(t *testing.T) {
	for _, tc := range []struct {
		socket, state, want string
	}{
		{"/data/user/0/app/files/tailscaled.sock", "/data/user/0/app/files/states/1760000000000", "/data/user/0/app/files/remote/1760000000000.json"},
		{"/data/user/0/app/files/tailscaled.sock", "/data/user/0/app/files/states/default", "/data/user/0/app/files/remote/default.json"},
		{"/data/user/0/app/files/tailscaled.sock", "/data/user/0/app/files/states/default/", "/data/user/0/app/files/remote/default.json"},
		// A state directory that is not an account id names no file of its own.
		{"/f/tailscaled.sock", "", "/f/remote/default.json"},
		{"/f/tailscaled.sock", "/f/states/..", "/f/remote/default.json"},
		{"/f/tailscaled.sock", "/f/states/a b", "/f/remote/default.json"},
		{"/f/tailscaled.sock", "/f/states/" + strings.Repeat("x", 65), "/f/remote/default.json"},
		{"", "/f/states/1", ""},
	} {
		if got := remoteStorePath(&StartOptions{SocketPath: tc.socket, StatePath: tc.state}); got != tc.want {
			t.Errorf("socket %q state %q: %q, want %q", tc.socket, tc.state, got, tc.want)
		}
	}
	if remoteStorePath(nil) != "" {
		t.Error("a path without options")
	}
}

// Each profile has its own list, and switching drops the code on show.
func TestRemoteProfiles(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	r.code()
	other := filepath.Join(filepath.Dir(r.store), "other.json")
	r.m.configure(true, other)
	if st := r.m.state(); st.Pairing != nil || st.Paired != 0 || !st.Enabled {
		t.Errorf("state on the other profile: %+v", st)
	}
	r.want(r.call(rtPhone, "GET", "/v1/status", token, nil), 401, "unauthorized")
	r.m.configure(true, r.store)
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("back on the first profile: %d %s", a.status, a.raw)
	}
}
