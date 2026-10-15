package appctr

// The device's network access to files: a ceiling over the level and the
// class, for an owner and for a guest given full rights alike, held in the
// grant, and closing at once.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

// rtFilesRoutes is every route the device's network access to files closes,
// with a body that is well formed, and the call to the app — or "log", the
// log being read — that shows a request got past the checks.
var rtFilesRoutes = []struct {
	method, path, op string
	body             any
	reached          string
}{
	{"POST", "/v1/shares/remove", remoteOpSharesRemove, map[string]string{"name": "Movies"}, "request share_remove"},
	{"POST", "/v1/shares/add", remoteOpSharesAdd, map[string]string{"name": "Movies", "path": "/storage/emulated/0/Movies"}, "request share_add"},
	{"POST", "/v1/storage/list", remoteOpStorageList, map[string]string{"path": ""}, "get storage_list"},
	{"POST", "/v1/storage/send", remoteOpStorageSend, map[string]string{"path": "/storage/emulated/0/a.txt"}, "get storage_file"},
	{"GET", "/v1/diag/report", remoteOpDiagReport, nil, "get diag_report"},
	{"GET", "/v1/diag/log", remoteOpDiagLog, nil, "log"},
}

// rtFilesCalls are the app's calls, and the log's reading, that hand out
// something of the device's files.
var rtFilesCalls = []string{"get storage_list", "get storage_file", "request share_add", "request share_remove", "get diag_report"}

// newFilesRig is a device whose app answers every request about its files,
// and that counts the log's readings and the files it sends.
func newFilesRig(t *testing.T) (*remoteRig, *atomic.Int32, *atomic.Int32) {
	t.Helper()
	r := newElevateRig(t)
	var logged, sent atomic.Int32
	r.m.logText = func() string { logged.Add(1); return "a line of the log" }
	r.m.sendFile = func(string, string) string { sent.Add(1); return "OK" }
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestShareAdd] = ""
		a.answers[remoteRequestShareRemove] = ""
		a.answers[remoteRequestStorageList] = `{"roots":[{"kind":"primary","name":"Internal storage","path":"/storage/emulated/0"}]}`
		// A file the bridge's own rule then refuses: it is not under the
		// storage of the machine the test runs on. That the app was asked is
		// what is counted.
		a.answers[remoteRequestStorageFile] = `{"path":"/storage/emulated/0/a.txt","name":"a.txt","size":1}`
		a.answers[remoteRequestDiagReport] = "TailSocks 5.0.1"
	})
	return r, &logged, &sent
}

// reachedSince reports whether the app was asked for what marks or, for
// "log", the log was read, since the app had been asked before times and the
// log read logs times.
func (r *remoteRig) reachedSince(before int, logged *atomic.Int32, logs int32, marks string) bool {
	if marks == "log" {
		return logged.Load() != logs
	}
	for _, c := range r.app.called()[before:] {
		if strings.HasPrefix(c, marks) {
			return true
		}
	}
	return false
}

// Every caller, at either level, with the device's files open or closed, on
// every route that touches them: closed is closed for everyone, and the app is
// not asked; open leaves the class and the level to decide, as before.
func TestRemoteFilesCeiling(t *testing.T) {
	type caller struct {
		name      string
		elevated  bool
		grantFull bool
	}
	callers := []caller{
		{name: "owner"},
		{name: "guest"},
		{name: "guest with full rights", elevated: true},
		{name: "guest with full rights and full control", elevated: true, grantFull: true},
	}
	policy := remoteDefaultPolicy()
	for _, c := range callers {
		for _, full := range []bool{false, true} {
			for _, files := range []bool{false, true} {
				t.Run(fmt.Sprintf("%s/full=%v/files=%v", c.name, full, files), func(t *testing.T) {
					r, logged, _ := newFilesRig(t)
					r.m.setAccess(full, false)
					r.m.setFiles(files)
					node, token := rtPhone, ""
					if c.name == "owner" {
						token = r.pair(rtPhone)
					} else {
						node, token = rtFriend, r.pair(rtFriend)
					}
					if c.elevated {
						// Given with the switches as they are: the grant says
						// whether the files come with it.
						a := r.m.elevate(rtFriend.id, 3600, c.grantFull)
						if !a.OK || a.NoFiles == files {
							t.Fatalf("elevate: %+v", a)
						}
					}
					owner := c.name == "owner" || c.elevated
					tier := full || c.grantFull

					st := r.call(node, "GET", "/v1/status", token, nil)
					can := rtStrings(st.obj["you"].(map[string]any)["can"])
					switch {
					case !owner && st.obj["files"] != nil:
						t.Errorf("a guest is told the files are %v", st.obj["files"])
					case owner && st.str("files") != remoteFilesWord(files):
						t.Errorf("status.files = %q", st.str("files"))
					}
					app, _ := st.obj["app"].(map[string]any)
					if _, listed := app["folders"]; listed != (owner && files) {
						t.Errorf("the shared folders are listed: %v (%s)", listed, st.raw)
					}

					for _, rt := range rtFilesRoutes {
						var status int
						var word string
						switch {
						case !owner:
							status, word = http.StatusForbidden, "forbidden"
						case !files:
							status, word = http.StatusForbidden, "files_closed"
						case policy.Full[rt.op] && !tier:
							status, word = http.StatusForbidden, "needs_full"
						}
						open := status == 0
						if rtHas(can, rt.op) != open {
							t.Errorf("%s offered: %v, want %v", rt.op, rtHas(can, rt.op), open)
						}
						before, logs := len(r.app.called()), logged.Load()
						a := r.call(node, rt.method, rt.path, token, rt.body)
						if open {
							if a.status == http.StatusForbidden && a.str("error") != "forbidden" || a.status == http.StatusUnauthorized {
								t.Errorf("%s: %d %s", rt.op, a.status, a.raw)
							}
						} else {
							r.want(a, status, word)
						}
						if got := r.reachedSince(before, logged, logs, rt.reached); got != open {
							t.Errorf("%s reached the app: %v, want %v", rt.op, got, open)
						}
					}

					// Taildrive and its proxy are the shared folders': neither
					// way while the files are closed, whoever asks.
					set := r.call(node, "GET", "/v1/settings", token, nil)
					for _, raw := range set.obj["settings"].([]any) {
						s := raw.(map[string]any)
						key := s["key"].(string)
						if key != "taildrive_enabled" && key != "taildrive_proxy_enabled" {
							continue
						}
						switch {
						case !files:
							if s["writable"] != false || s["needs"] != "files" || s["only"] != nil {
								t.Errorf("%s with the files closed: %v", key, s)
							}
						case !owner:
							if s["writable"] != false {
								t.Errorf("%s for a guest: %v", key, s)
							}
						case !tier:
							if s["writable"] != true || s["needs"] != "full" || s["only"] != "false" {
								t.Errorf("%s at the basic level: %v", key, s)
							}
						default:
							if s["writable"] != true || s["needs"] != nil {
								t.Errorf("%s with full control: %v", key, s)
							}
						}
					}
					before := len(r.app.writes())
					a := r.call(node, "POST", "/v1/settings", token, map[string]string{"key": "taildrive_enabled", "value": "false"})
					switch {
					case !owner:
						r.want(a, http.StatusForbidden, "forbidden")
					case !files:
						r.want(a, http.StatusForbidden, "files_closed")
					case a.status != http.StatusOK:
						t.Errorf("sharing switched off with the files open: %d %s", a.status, a.raw)
					}
					if wrote := len(r.app.writes()) != before; wrote != (owner && files) {
						t.Errorf("the app was written to: %v", wrote)
					}

					// Refused for the files, and logged so, by the reason's name.
					if owner && !files {
						ev := r.lastEvent("denied")
						if ev == nil || !strings.HasPrefix(ev.Detail, "op_needs_files: ") {
							t.Errorf("the refusal in the log: %+v", ev)
						}
					}
				})
			}
		}
	}
}

// Nothing reaches the device's files while the switch is off: every route of
// the API, as the callers with the most rights there are, with full control on.
func TestRemoteFilesClosedEverywhere(t *testing.T) {
	r, logged, sent := newFilesRig(t)
	r.m.setAccess(true, false)
	r.m.setFiles(false)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	if a := r.m.elevate(rtFriend.id, 0, true); !a.OK || !a.NoFiles {
		t.Fatalf("elevate: %+v", a)
	}
	bodies := map[string]any{"/v1/pair": map[string]string{"code": "000000"}}
	for _, rt := range rtFilesRoutes {
		bodies[rt.path] = rt.body
	}
	for _, as := range []struct {
		node  *rtNode
		token string
	}{{rtPhone, owner}, {rtFriend, guest}} {
		for _, rt := range remoteRoutes {
			if rt.path == "/v1/unpair" {
				continue
			}
			body := bodies[rt.path]
			if body == nil && rt.method == "POST" {
				body = map[string]string{}
			}
			r.call(as.node, rt.method, rt.path, as.token, body)
		}
		for _, key := range []string{"taildrive_enabled", "taildrive_proxy_enabled"} {
			for _, v := range []string{"true", "false"} {
				r.want(r.call(as.node, "POST", "/v1/settings", as.token, map[string]string{"key": key, "value": v}), http.StatusForbidden, "files_closed")
			}
		}
		if app, _ := r.call(as.node, "GET", "/v1/status", as.token, nil).obj["app"].(map[string]any); app["folders"] != nil {
			t.Errorf("the shared folders were listed to %s", as.node.name)
		}
	}
	for _, c := range r.app.called() {
		for _, files := range rtFilesCalls {
			if strings.HasPrefix(c, files) {
				t.Errorf("the app was asked %q", c)
			}
		}
		if strings.HasPrefix(c, "set taildrive") {
			t.Errorf("the app was told %q", c)
		}
	}
	if logged.Load() != 0 || sent.Load() != 0 {
		t.Errorf("the log was read %d times, %d files were sent", logged.Load(), sent.Load())
	}
}

// Full rights keep what the person who gave them was told: given while the
// files were closed, never the files; given while they were open, the files
// as long as the switch stays on.
func TestRemoteFilesHeldInTheGrant(t *testing.T) {
	r, _, _ := newFilesRig(t)
	r.m.setAccess(true, false)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	list := func(n *rtNode, token string) rtReply {
		return r.call(n, "POST", "/v1/storage/list", token, map[string]string{"path": ""})
	}
	filesOf := func(n *rtNode, token string) string {
		return r.call(n, "GET", "/v1/status", token, nil).str("files")
	}

	r.m.setFiles(false)
	if a := r.m.elevate(rtFriend.id, 3600, true); !a.OK || !a.NoFiles {
		t.Fatalf("given with the files closed: %+v", a)
	}
	if el, _ := r.you(rtFriend, guest)["elevated"].(map[string]any); el["noFiles"] != true {
		t.Errorf("the guest is not told its rights leave the files out: %v", el)
	}
	if p := rtPaired(t, r, rtFriend.id); p.Elevated == nil || !p.Elevated.NoFiles {
		t.Errorf("the device's list does not say so: %+v", p.Elevated)
	}
	// The switch turned on afterwards opens them to the owner, not to the guest.
	r.m.setFiles(true)
	if a := list(rtPhone, owner); a.status != http.StatusOK || filesOf(rtPhone, owner) != "open" {
		t.Errorf("the owner, files on: %d %s", a.status, a.raw)
	}
	r.want(list(rtFriend, guest), http.StatusForbidden, "files_closed")
	if w := filesOf(rtFriend, guest); w != "closed" {
		t.Errorf("the guest is told the files are %q", w)
	}
	// And kept so across a restart of the app.
	r.restart()
	r.want(list(rtFriend, guest), http.StatusForbidden, "files_closed")

	// Given again with the switch on: the files come with them, and go with
	// the switch, and come back with it.
	if a := r.m.elevate(rtFriend.id, 3600, true); !a.OK || a.NoFiles {
		t.Fatalf("given with the files open: %+v", a)
	}
	if a := list(rtFriend, guest); a.status != http.StatusOK {
		t.Errorf("the guest, files on: %d %s", a.status, a.raw)
	}
	r.m.setFiles(false)
	r.want(list(rtFriend, guest), http.StatusForbidden, "files_closed")
	r.want(list(rtPhone, owner), http.StatusForbidden, "files_closed")
	r.m.setFiles(true)
	if a := list(rtFriend, guest); a.status != http.StatusOK {
		t.Errorf("the guest, files on again: %d %s", a.status, a.raw)
	}

	// A store written before the switch existed holds no word on it: such
	// rights follow the switch.
	var e remoteElevation
	if err := json.Unmarshal([]byte(`{"granted":1,"seconds":3600,"full":true}`), &e); err != nil || e.NoFiles {
		t.Errorf("an older grant: %+v %v", e, err)
	}
}

// Switched off at the device, the files close at once: the next request of a
// page that is open on the other side is refused as closed, and its status
// says so.
func TestRemoteFilesCloseAtOnce(t *testing.T) {
	r, _, _ := newFilesRig(t)
	r.m.setAccess(true, false)
	owner := r.pair(rtPhone)
	list := func() rtReply {
		return r.call(rtPhone, "POST", "/v1/storage/list", owner, map[string]string{"path": ""})
	}
	if a := list(); a.status != http.StatusOK {
		t.Fatalf("open: %d %s", a.status, a.raw)
	}
	rev := r.m.state().Rev
	r.m.setFiles(false)
	if r.m.state().Rev == rev {
		t.Error("the revision did not move: the device's page would not read the change")
	}
	r.want(list(), http.StatusForbidden, "files_closed")
	st := r.call(rtPhone, "GET", "/v1/status", owner, nil)
	if st.str("files") != "closed" || rtHas(rtStrings(st.obj["you"].(map[string]any)["can"]), remoteOpStorageList) {
		t.Errorf("the status after: %s", st.raw)
	}
	// The level is untouched: full control is still on.
	if st.str("level") != "full" {
		t.Errorf("the level after: %q", st.str("level"))
	}
}

// The device's page is told what the switch keeps closed, from the same lists.
func TestRemoteFilesAccessDocument(t *testing.T) {
	r := newRemoteRig(t)
	policy := remoteDefaultPolicy()
	var filesOps []string
	for _, op := range remoteOps {
		if policy.Files[op] {
			filesOps = append(filesOps, op)
		}
	}
	for _, full := range []bool{false, true} {
		r.m.setAccess(full, false)
		r.m.setFiles(false)
		v := r.m.access()
		if v.Files || strings.Join(v.WithFiles.Ops, ",") != strings.Join(filesOps, ",") {
			t.Errorf("full=%v, files off: %v keeps %v", full, v.Files, v.WithFiles.Ops)
		}
		var kept []string
		for _, s := range v.WithFiles.Settings {
			kept = append(kept, s.Key)
		}
		if strings.Join(kept, ",") != "taildrive_enabled,taildrive_proxy_enabled" {
			t.Errorf("full=%v: the settings kept closed: %v", full, kept)
		}
		for _, op := range append(append(append([]string{}, v.Owner.Ops...), v.Guest.Ops...), v.Full.Ops...) {
			if policy.Files[op] {
				t.Errorf("full=%v: %s is said to be open, or to open with full control", full, op)
			}
		}
		for _, s := range append(append([]remoteAccessSetting{}, v.Owner.Settings...), v.Full.Settings...) {
			if strings.HasPrefix(s.Key, "taildrive") {
				t.Errorf("full=%v: %s is said to be written", full, s.Key)
			}
		}
		if !rtHas(v.Never, "files_on") {
			t.Errorf("switching the files on from afar is not among what nobody may: %v", v.Never)
		}

		r.m.setFiles(true)
		v = r.m.access()
		if !v.Files || len(v.WithFiles.Ops) != 0 || len(v.WithFiles.Settings) != 0 {
			t.Errorf("full=%v, files on: %+v", full, v.WithFiles)
		}
		if full != rtHas(v.Owner.Ops, remoteOpStorageList) || !rtHas(v.Owner.Ops, remoteOpFolders) {
			t.Errorf("full=%v, files on: an owner's %v", full, v.Owner.Ops)
		}
	}
}

// The switch reaches the bridge with the options and at once by itself; the
// zero value of the options keeps the files closed.
func TestRemoteFilesFromOptions(t *testing.T) {
	saved := remote
	remote = newRemoteManager(remoteDefaultPolicy(), newRemoteFakeClock(), newRemoteFakeDaemon())
	t.Cleanup(func() {
		remote.configure(false, "")
		remote = saved
	})
	if (StartOptions{}).RemoteFiles {
		t.Fatal("the files are open by default")
	}
	files := func() bool {
		remote.mu.Lock()
		defer remote.mu.Unlock()
		return remote.files
	}
	remoteSwitch(&StartOptions{RemoteFiles: true})
	if !files() {
		t.Error("the options did not open them")
	}
	RemoteAllowFiles(false)
	if files() {
		t.Error("the switch did not close them")
	}
	remoteApply(&StartOptions{RemoteFiles: true})
	if !files() {
		t.Error("a start did not open them")
	}
	remoteApply(&StartOptions{})
	if files() {
		t.Error("a start without them left them open")
	}
}

// rtPaired is one pairing as RemotePairedJSON lists it.
func rtPaired(t *testing.T, r *remoteRig, nodeID string) remotePairedView {
	t.Helper()
	for _, p := range r.m.paired() {
		if p.NodeID == nodeID {
			return p
		}
	}
	t.Fatalf("%s is not paired", nodeID)
	return remotePairedView{}
}
