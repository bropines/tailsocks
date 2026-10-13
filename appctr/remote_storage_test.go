package appctr

// The device's storage by name, "send me this file", shared folders, the
// diagnostics and the scrubbed log.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRemoteStoragePathOK(t *testing.T) {
	for _, ok := range []string{"/storage/emulated/0", "/storage/emulated/0/Download/Фильмы 2026", "/storage/1234-ABCD/a b/c.d", "/mnt/media_rw/x", "/a..b/c"} {
		if !remoteStoragePathOK(ok) {
			t.Errorf("%q refused", ok)
		}
	}
	for _, bad := range []string{
		"", "storage/emulated/0", "./x", "/storage/../data", "/storage/emulated/0/..", "/storage//emulated", "/storage/emulated/0/",
		"/storage/./emulated", "/a\x00b", "/a\nb", `/a\b`, "/bad\xffutf8", "/" + strings.Repeat("n", remoteMaxPath),
	} {
		if remoteStoragePathOK(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
	for _, ok := range []string{"film.mkv", "отчёт.pdf", "a b", "x.partial"} {
		if !remoteEntryNameOK(ok) {
			t.Errorf("entry %q refused", ok)
		}
	}
	for _, bad := range []string{"", ".", "..", ".hidden", "a/b", "a\x00", "a\nb", strings.Repeat("n", 256)} {
		if remoteEntryNameOK(bad) {
			t.Errorf("entry %q accepted", bad)
		}
	}
}

// rtFull is a managed device with full control on and an owner paired.
func rtFull(t *testing.T) (*remoteRig, string) {
	t.Helper()
	r := newRemoteRig(t)
	r.m.setAccess(true, false)
	return r, r.pair(rtPhone)
}

func TestRemoteStorageList(t *testing.T) {
	r, owner := rtFull(t)
	list := func(path string) rtReply {
		t.Helper()
		return r.call(rtPhone, "POST", "/v1/storage/list", owner, map[string]string{"path": path})
	}

	// The roots: what the app names, as far as it can be a root at all.
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestStorageList] = `{"roots":[
		  {"kind":"primary","name":"Internal storage","path":"/storage/emulated/0","free":51234567890,"total":118111600640},
		  {"kind":"removable","name":"USB\u0000 drive","path":"/storage/1234-ABCD","free":-5,"total":31914983424},
		  {"kind":"cloud","name":"Something else","path":"/mnt/other"},
		  {"name":"relative","path":"storage/x"},
		  {"name":"up","path":"/storage/../data"}],
		 "secret":"tskey-auth-abc"}`
	})
	a := list("")
	roots, _ := a.obj["roots"].([]any)
	if a.status != 200 || len(roots) != 3 || a.obj["path"] != "" {
		t.Fatalf("roots: %d %s", a.status, a.raw)
	}
	if usb := roots[1].(map[string]any); usb["name"] != "USB drive" || usb["free"] != float64(0) || usb["path"] != "/storage/1234-ABCD" || usb["kind"] != "removable" {
		t.Errorf("a root: %v", usb)
	}
	// A kind the bridge does not know is just a volume.
	if roots[0].(map[string]any)["kind"] != "primary" || roots[2].(map[string]any)["kind"] != "volume" {
		t.Errorf("kinds: %v", roots)
	}
	if strings.Contains(a.raw, "tskey") || strings.Contains(a.raw, "entries") {
		t.Errorf("roots: %s", a.raw)
	}
	if calls := r.app.called(); calls[len(calls)-1] != `get storage_list {"path":"","offset":0}` {
		t.Errorf("what the app was asked: %q", calls[len(calls)-1])
	}

	// A folder: names, sizes and dates, in the app's own order; nothing
	// hidden, nothing that is not one name.
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestStorageList] = `{"path":"/storage/emulated/0/Download","parent":"/storage/emulated/0","limited":true,"entries":[
		  {"name":"Album","dir":true},
		  {"name":"Taildrop","dir":true,"size":4096,"time":1791727380},
		  {"name":".thumbnails","dir":true},
		  {"name":"../../data","dir":true},
		  {"name":"a/b","dir":false},
		  {"name":"file2.txt","dir":false,"size":734003200,"time":1791700000},
		  {"name":"file10.txt","dir":false,"size":-1,"time":-1}]}`
	})
	a = list("/storage/emulated/0/Download")
	if a.status != 200 || a.obj["path"] != "/storage/emulated/0/Download" || a.obj["parent"] != "/storage/emulated/0" || a.obj["limited"] != true ||
		a.obj["truncated"] != false || a.obj["offset"] != float64(0) {
		t.Fatalf("listing: %d %s", a.status, a.raw)
	}
	var names []string
	for _, raw := range a.obj["entries"].([]any) {
		e := raw.(map[string]any)
		names = append(names, fmt.Sprintf("%v:%v:%v", e["name"], e["dir"], e["size"]))
	}
	// Not sorted again here: "file2" stays before "file10", as the device's own browser has it.
	if got := strings.Join(names, ","); got != "Album:true:0,Taildrop:true:0,file2.txt:false:7.340032e+08,file10.txt:false:0" {
		t.Errorf("entries: %s", got)
	}
	if a.obj["roots"] != nil {
		t.Errorf("a folder with roots: %s", a.raw)
	}
	// The next part of a long folder: the app is told where to start, and the answer says where it starts.
	a = r.call(rtPhone, "POST", "/v1/storage/list", owner, map[string]any{"path": "/storage/emulated/0/Download", "offset": 500})
	if a.status != 200 || a.obj["offset"] != float64(500) {
		t.Errorf("a later part: %d %s", a.status, a.raw)
	}
	if calls := r.app.called(); calls[len(calls)-1] != `get storage_list {"path":"/storage/emulated/0/Download","offset":500}` {
		t.Errorf("what the app was asked: %q", calls[len(calls)-1])
	}
	for _, bad := range []map[string]any{
		{"path": "/storage/emulated/0", "offset": -1}, {"path": "/storage/emulated/0", "offset": remoteMaxOffset + 1},
		{"path": "", "offset": 1}, {"path": "/storage/emulated/0", "offset": "2"},
	} {
		r.want(r.call(rtPhone, "POST", "/v1/storage/list", owner, bad), 400, "bad_request")
	}

	// A request for something that is not a path does not reach the app.
	asked := len(r.app.called())
	for _, bad := range []string{"Download", "/storage/../data", "/storage//x", "/storage/x/", "/a\x00b", "./x"} {
		r.want(list(bad), 400, "bad_request")
	}
	r.want(r.call(rtPhone, "POST", "/v1/storage/list", owner, map[string]string{"path": "/storage", "depth": "9"}), 400, "bad_request")
	if len(r.app.called()) != asked {
		t.Errorf("the app was asked for a path that is none: %q", r.app.called()[asked:])
	}

	// The app's refusals: two words that are the API's own, and its sentences.
	for why, want := range map[string][2]string{
		"not_found":                {"404", "not_found"},
		"forbidden":                {"403", "forbidden"},
		"the drive is not mounted": {"409", "refused"},
	} {
		r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestStorageList] = why })
		a := list("/storage/emulated/0/x")
		if fmt.Sprint(a.status) != want[0] || a.str("error") != want[1] {
			t.Errorf("the app said %q: %d %s", why, a.status, a.raw)
		}
	}
	r.app.set(func(a *remoteFakeApp) { delete(a.refusals, remoteRequestStorageList) })

	// An answer that is no listing, a folder the app names badly.
	for _, bad := range []string{"not json", `{"path":"relative","entries":[]}`, `{"entries":[]}`} {
		r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestStorageList] = bad })
		if a := list("/storage/emulated/0"); a.status != 502 {
			t.Errorf("the app answered %q: %d %s", bad, a.status, a.raw)
		}
	}

	// A long folder is cut, and says so.
	var many []string
	for i := 0; i < remoteMaxEntries+100; i++ {
		many = append(many, fmt.Sprintf(`{"name":"f%04d","size":1}`, i))
	}
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestStorageList] = `{"path":"/storage/emulated/0/DCIM","entries":[` + strings.Join(many, ",") + `]}`
	})
	a = list("/storage/emulated/0/DCIM")
	if a.status != 200 || len(a.obj["entries"].([]any)) != remoteMaxEntries || a.obj["truncated"] != true || a.obj["parent"] != nil {
		t.Errorf("a long folder: %d, %d entries, truncated %v", a.status, len(a.obj["entries"].([]any)), a.obj["truncated"])
	}

	// A read: an app that hangs is unavailable, not "pending".
	r.m.delegateWait, r.m.delegateAnswer = 100*time.Millisecond, 100*time.Millisecond
	release := r.app.hang()
	r.want(list("/storage/emulated/0"), 503, "unavailable")
	release()
	r.idle()

	// Looking around is in the log once, not once per folder.
	n := 0
	for _, ev := range r.m.actions() {
		if ev.Op == "browse" {
			n++
			if ev.Name != rtPhone.name || !ev.OK {
				t.Errorf("logged: %+v", ev)
			}
		}
	}
	if n != 1 {
		t.Errorf("%d lines for looking around", n)
	}
	r.clock.advance(2 * time.Hour)
	list("/storage/emulated/0/DCIM")
	if ev := r.m.actions()[0]; ev.Op != "browse" {
		t.Errorf("looking again two hours later is not logged: %+v", ev)
	}
}

func TestRemoteStorageSend(t *testing.T) {
	r, owner := rtFull(t)
	tablet := r.pair(rtTablet)
	// The storage of this test: a directory with a file, a folder, and a link
	// that leads out of it to something that must never be sent.
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	private, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	saved := remoteStorageRoots
	remoteStorageRoots = []string{root + "/"}
	t.Cleanup(func() { remoteStorageRoots = saved })
	file := filepath.Join(root, "Download", "film.mkv")
	state := filepath.Join(private, "tailscaled.state")
	link := filepath.Join(root, "Download", "notes.txt")
	linkedDir := filepath.Join(root, "inside")
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		t.Fatal(err)
	}
	for path, content := range map[string]string{file: "the film", state: "the node key"} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink(state, link); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(private, linkedDir); err != nil {
		t.Fatal(err)
	}

	type sent struct{ peer, path string }
	sends := make(chan sent, 8)
	result := "OK"
	hold := make(chan struct{})
	close(hold)
	r.m.sendFile = func(peer, path string) string {
		<-hold
		sends <- sent{peer, path}
		return result
	}
	answer := func(path string) {
		r.app.set(func(a *remoteFakeApp) {
			b, _ := json.Marshal(map[string]any{"path": path, "name": "ignored", "size": 1})
			a.answers[remoteRequestStorageFile] = string(b)
		})
	}
	send := func(n *rtNode, token, path string) rtReply {
		t.Helper()
		return r.call(n, "POST", "/v1/storage/send", token, map[string]string{"path": path})
	}
	fetch := func(n *rtNode, token string) map[string]any {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		for {
			st := r.call(n, "GET", "/v1/status", token, nil)
			f, _ := st.obj["fetch"].(map[string]any)
			if f != nil && f["state"] != "sending" || time.Now().After(deadline) {
				return f
			}
			time.Sleep(5 * time.Millisecond)
		}
	}

	// The file goes to the node that asked, by the daemon's own transfer.
	answer(file)
	a := send(rtPhone, owner, file)
	if a.status != 200 || a.str("name") != "film.mkv" || a.num("size") != 8 || a.str("id") == "" {
		t.Fatalf("send: %d %s", a.status, a.raw)
	}
	select {
	case s := <-sends:
		if s.peer != rtPhone.id || s.path != file {
			t.Errorf("sent %+v", s)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("nothing was sent")
	}
	if f := fetch(rtPhone, owner); f == nil || f["state"] != "sent" || f["name"] != "film.mkv" || f["id"] != a.str("id") || f["error"] != nil || f["age"] != float64(0) {
		t.Errorf("fetch: %v", f)
	}
	// It is told for a while — with how long ago it ended — and then no more.
	r.clock.advance(90 * time.Second)
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.obj["fetch"] == nil || st.obj["fetch"].(map[string]any)["age"] != float64(90) {
		t.Errorf("fetch a minute and a half later: %s", st.raw)
	}
	r.clock.advance(remoteFetchKeep)
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.obj["fetch"] != nil {
		t.Errorf("an old transfer is still told: %s", st.raw)
	}
	if st := r.call(rtTablet, "GET", "/v1/status", tablet, nil); st.obj["fetch"] != nil {
		t.Errorf("another device is shown the transfer: %s", st.raw)
	}
	deadline := time.Now().Add(3 * time.Second)
	for r.m.actions()[0].Op != "file_sent" && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if ev := r.m.actions()[0]; ev.Op != "file_sent" || ev.Detail != "film.mkv" || !ev.OK || ev.Name != rtPhone.name {
		t.Errorf("logged: %+v", ev)
	}

	// Whatever the app answers, nothing outside the storage is opened: its
	// own state, a link that leads there, a file reached through a linked
	// folder, a path that is no path.
	for _, path := range []string{state, link, filepath.Join(linkedDir, "tailscaled.state"), "/data/user/0/app/files/states/1/tailscaled.state", "/proc/self/environ"} {
		answer(path)
		a := send(rtPhone, owner, file)
		if a.status != 403 || a.str("error") != "forbidden" {
			t.Errorf("the app named %s: %d %s", path, a.status, a.raw)
		}
	}
	for _, bad := range []string{"not json", `{"path":"relative"}`, `{}`} {
		r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestStorageFile] = bad })
		if a := send(rtPhone, owner, file); a.status != 502 {
			t.Errorf("the app answered %q: %d %s", bad, a.status, a.raw)
		}
	}
	// Not a file, or not there.
	answer(filepath.Dir(file))
	r.want(send(rtPhone, owner, file), 409, "refused")
	answer(filepath.Join(root, "Download", "gone.mkv"))
	r.want(send(rtPhone, owner, file), 404, "not_found")
	select {
	case s := <-sends:
		t.Fatalf("something was sent that should not have been: %+v", s)
	case <-time.After(50 * time.Millisecond):
	}
	if !r.hasOp("denied") {
		t.Errorf("the attempts left no trace: %q", r.ops())
	}

	// The app's own refusals, and a request that is no path.
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestStorageFile] = "forbidden" })
	r.want(send(rtPhone, owner, file), 403, "forbidden")
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestStorageFile] = "not_found" })
	r.want(send(rtPhone, owner, file), 404, "not_found")
	r.app.set(func(a *remoteFakeApp) { delete(a.refusals, remoteRequestStorageFile) })
	asked := len(r.app.called())
	for _, bad := range []string{"", "film.mkv", "/storage/../x", root + "/"} {
		r.want(send(rtPhone, owner, bad), 400, "bad_request")
	}
	if len(r.app.called()) != asked {
		t.Error("the app was asked for a path that is none")
	}

	// The transfer fails: the daemon may not send to that phone.
	answer(file)
	result = "Error: HTTP 403: file sharing not enabled by Tailscale admin"
	if a := send(rtPhone, owner, file); a.status != 200 {
		t.Fatalf("send: %d %s", a.status, a.raw)
	}
	<-sends
	if f := fetch(rtPhone, owner); f == nil || f["state"] != "failed" || f["error"] != "HTTP 403: file sharing not enabled by Tailscale admin" {
		t.Errorf("fetch after a failure: %v", f)
	}
	deadline = time.Now().Add(3 * time.Second)
	for r.m.actions()[0].OK && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if ev := r.m.actions()[0]; ev.Op != "file_sent" || ev.OK {
		t.Errorf("logged: %+v", ev)
	}

	// One at a time for a device; another device is not held up by it.
	result = "OK"
	hold = make(chan struct{})
	if a := send(rtPhone, owner, file); a.status != 200 {
		t.Fatalf("send: %d %s", a.status, a.raw)
	}
	b := send(rtPhone, owner, file)
	r.want(b, 409, "refused")
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.obj["fetch"].(map[string]any)["state"] != "sending" {
		t.Errorf("fetch while it runs: %s", st.raw)
	}
	if a := send(rtTablet, tablet, file); a.status != 200 {
		t.Errorf("another device meanwhile: %d %s", a.status, a.raw)
	}
	close(hold)
	<-sends
	<-sends
	if f := fetch(rtPhone, owner); f["state"] != "sent" {
		t.Errorf("fetch: %v", f)
	}
}

func TestRemoteShares(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestShareAdd], a.answers[remoteRequestShareRemove] = "", ""
	})
	add := map[string]string{"name": "Movies", "path": "/storage/emulated/0/Movies"}
	remove := map[string]string{"name": "Downloads"}

	// At the basic level a share can be taken away and not added.
	r.want(r.call(rtPhone, "POST", "/v1/shares/add", owner, add), 403, "needs_full")
	if a := r.call(rtPhone, "POST", "/v1/shares/remove", owner, remove); a.status != 200 {
		t.Fatalf("remove: %d %s", a.status, a.raw)
	}
	if got := strings.Join(r.app.writes(), ","); got != `request share_remove {"name":"Downloads"}` {
		t.Errorf("what reached the app: %s", got)
	}
	if ev := r.m.actions()[0]; ev.Op != "share_removed" || ev.Detail != "Downloads" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestShareRemove] = "not_found" })
	r.want(r.call(rtPhone, "POST", "/v1/shares/remove", owner, remove), 404, "not_found")
	r.app.set(func(a *remoteFakeApp) { delete(a.refusals, remoteRequestShareRemove) })
	// A guest, neither.
	r.want(r.call(rtFriend, "POST", "/v1/shares/add", guest, add), 403, "forbidden")
	r.want(r.call(rtFriend, "POST", "/v1/shares/remove", guest, remove), 403, "forbidden")

	r.m.setAccess(true, false)
	if a := r.call(rtPhone, "POST", "/v1/shares/add", owner, add); a.status != 200 {
		t.Fatalf("add: %d %s", a.status, a.raw)
	}
	writes := r.app.writes()
	if got := writes[len(writes)-1]; got != `request share_add {"name":"Movies","path":"/storage/emulated/0/Movies"}` {
		t.Errorf("what reached the app: %s", got)
	}
	if ev := r.m.actions()[0]; ev.Op != "share_added" || ev.Detail != "Movies" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	r.want(r.call(rtFriend, "POST", "/v1/shares/add", guest, add), 403, "forbidden")

	// A name Taildrive would not take, a path that is none: the app is not asked.
	asked := len(r.app.writes())
	for _, name := range []string{"", "my share", "a/b", "Фильмы", "x.y", strings.Repeat("n", 41), "../x"} {
		r.want(r.call(rtPhone, "POST", "/v1/shares/add", owner, map[string]string{"name": name, "path": "/storage/emulated/0/Movies"}), 400, "bad_request")
		r.want(r.call(rtPhone, "POST", "/v1/shares/remove", owner, map[string]string{"name": name}), 400, "bad_request")
	}
	for _, path := range []string{"", "Movies", "/storage/../data", "/storage/x/"} {
		r.want(r.call(rtPhone, "POST", "/v1/shares/add", owner, map[string]string{"name": "Movies", "path": path}), 400, "bad_request")
	}
	r.want(r.call(rtPhone, "POST", "/v1/shares/add", owner, map[string]string{"name": "Movies", "path": "/storage/x", "readonly": "false"}), 400, "bad_request")
	if len(r.app.writes()) != asked {
		t.Errorf("the app was asked: %q", r.app.writes()[asked:])
	}
	// The app's own reasons.
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestShareAdd] = "needs storage access" })
	a := r.call(rtPhone, "POST", "/v1/shares/add", owner, add)
	r.want(a, 409, "refused")
	if a.str("message") != "needs storage access" || r.m.actions()[0].OK {
		t.Errorf("a refusal: %s, logged %+v", a.raw, r.m.actions()[0])
	}
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestShareAdd] = "forbidden" })
	r.want(r.call(rtPhone, "POST", "/v1/shares/add", owner, add), 403, "forbidden")
}

// What the scrubber takes out, and what it leaves.
func TestRemoteScrub(t *testing.T) {
	for _, tc := range []struct {
		line   string
		secret string
		keeps  string
	}{
		{"Running Tailscale CLI args=[up --auth-key=tskey-auth-kABC123CNTRL-abcdefghijklmnop]", "kABC123CNTRL", "Running Tailscale CLI"},
		{"up --auth-key=0123456789abcdef0123456789abcdef --hostname tv", "0123456789abcdef", "--hostname tv"},
		{"up --authkey hunter2hunter2 --hostname tv", "hunter2", "--hostname tv"},
		{"To authenticate, visit: https://login.tailscale.com/a/1a2b3c4d5e6f", "1a2b3c4d5e6f", "login.tailscale.com"},
		{"control: doLogin(regen=false) url=https://controlplane.example/a/9z8y7x?next=/admin", "9z8y7x", "controlplane.example"},
		{"dialing socks5://user:hunter2@10.0.0.1:1080", "hunter2", "10.0.0.1:1080"},
		{"Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.abc", "eyJhbGci", "Authorization"},
		{`prefs: {"ProxyPassword":"hunter2","Hostname":"tv"}`, "hunter2", `"Hostname":"tv"`},
		{"password=hunter2&user=x", "hunter2", "user=x"},
		{"control: logged in as ann@example.org (ok)", "ann@example.org", "logged in as"},
		{"state: /data/user/0/io.github.bropines.tailscaled/files/states/1/tailscaled.state missing", "states/1", "missing"},
		{"magicsock: nodekey:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789 active", "abcdef0123456789", "active"},
		{"wg: privkey:c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0 set", "c2VjcmV0", "set"},
		{"cookie=session-123abc; path=/", "session-123abc", "path="},
		{"blob 4f1a9c3e7b2d5a8f6c0e1d2b3a4f5e6c7d8b9a0f end", "4f1a9c3e7b2d5a8f", "end"},
		{"admin api_key: tskey-api-kXYZ-secretsecret", "secretsecret", "admin"},
	} {
		got, n := remoteScrub(tc.line)
		if strings.Contains(got, tc.secret) || n == 0 {
			t.Errorf("%q\n -> %q: the secret is still there (%d changes)", tc.line, got, n)
		}
		if !strings.Contains(got, tc.keeps) {
			t.Errorf("%q\n -> %q: lost %q", tc.line, got, tc.keeps)
		}
	}
	// What says something and gives nothing away stays as it is.
	for _, line := range []string{
		"12:00:01 INFO CORE Starting the daemon",
		"magicsock: derp-4 connected; connGen=1",
		"peer living-room-tv (100.64.0.7) active via 192.168.1.20:41641",
		"netcheck: udp=true v6=false mapvarydest=false portmap=UPnP",
		"health(warnable=no-derp-connection): error: not connected to home DERP region 4",
		"Taildrop: received holiday.mp4 (812647424 bytes)",
	} {
		if got, n := remoteScrub(line); got != line || n != 0 {
			t.Errorf("%q was changed to %q", line, got)
		}
	}
}

func TestRemoteDiag(t *testing.T) {
	r, owner := rtFull(t)
	const secret = "tskey-auth-kSECRET0CNTRL-0123456789abcdefghij"
	var lines []string
	for i := 0; i < remoteLogLines+150; i++ {
		lines = append(lines, fmt.Sprintf("12:00:%02d INFO CORE line %d", i%60, i))
	}
	lines[40] = "early: --auth-key=" + secret // outside the tail
	lines[400] = "Running Tailscale CLI args=[up --auth-key=" + secret + "]"
	lines[401] = "To authenticate, visit: https://login.tailscale.com/a/1a2b3c4d5e6f"
	lines[402] = "long " + strings.Repeat("word ", 400)
	lines[403] = "bad\xffutf8 line\r"
	lines[404] = ""
	r.m.logText = func() string { return strings.Join(lines, "\n") + "\n" }

	a := r.call(rtPhone, "GET", "/v1/diag/log", owner, nil)
	got := rtStrings(a.obj["lines"])
	if a.status != 200 || len(got) != remoteLogLines-1 {
		t.Fatalf("log: %d, %d lines", a.status, len(got))
	}
	if strings.Contains(a.raw, "kSECRET0CNTRL") || strings.Contains(a.raw, "1a2b3c4d5e6f") || strings.Contains(a.raw, "0123456789abcdefghij") {
		t.Errorf("the log gives a secret away: %.300s", a.raw)
	}
	// The tail, oldest first, each line cut to a length a screen can show.
	if got[0] != lines[150] || got[len(got)-1] != lines[len(lines)-1] || a.num("scrubbed") < 2 {
		t.Errorf("first %q, last %q, scrubbed %v", got[0], got[len(got)-1], a.num("scrubbed"))
	}
	for _, line := range got {
		if len([]rune(line)) > remoteLogLineMax+1 || strings.ContainsAny(line, "\r\n") {
			t.Errorf("a line of %d", len([]rune(line)))
		}
		if strings.HasPrefix(line, "bad") && line != "badutf8 line" {
			t.Errorf("a line that was not text: %q", line)
		}
	}
	// No log at all is an empty list.
	r.m.logText = func() string { return "" }
	if a := r.call(rtPhone, "GET", "/v1/diag/log", owner, nil); a.status != 200 || a.raw != `{"lines":[],"scrubbed":0}`+"\n" {
		t.Errorf("an empty log: %d %q", a.status, a.raw)
	}

	// The report is the app's; it is scrubbed all the same.
	r.app.set(func(a *remoteFakeApp) {
		a.answers[remoteRequestDiagReport] = "--- TAILSOCKS DIAGNOSTICS ---\nMode: proxy\nSOCKS5: 127.0.0.1:48115\nActive profile: auth key present\nleak: token=abcdef123\n"
	})
	a = r.call(rtPhone, "GET", "/v1/diag/report", owner, nil)
	if a.status != 200 || !strings.Contains(a.str("text"), "SOCKS5: 127.0.0.1:48115") || !strings.Contains(a.str("text"), "auth key present") || strings.Contains(a.raw, "abcdef123") {
		t.Errorf("report: %d %s", a.status, a.raw)
	}
	r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestDiagReport] = strings.Repeat("x ", remoteMaxDiagReport) })
	if a := r.call(rtPhone, "GET", "/v1/diag/report", owner, nil); a.status != 200 || len(a.str("text")) > remoteMaxDiagReport {
		t.Errorf("a long report: %d, %d bytes", a.status, len(a.str("text")))
	}
	r.app.set(func(a *remoteFakeApp) { a.refusals[remoteRequestDiagReport] = "no" })
	r.want(r.call(rtPhone, "GET", "/v1/diag/report", owner, nil), 409, "refused")

	// Each look is in the device's log, once while nothing else happens.
	var diag []string
	for _, ev := range r.m.actions() {
		if ev.Op == "diag" {
			diag = append(diag, ev.Detail)
			if ev.Name != rtPhone.name || !ev.OK {
				t.Errorf("logged: %+v", ev)
			}
		}
	}
	if got := strings.Join(diag, ","); got != "report,log" {
		t.Errorf("diag lines, newest first: %s", got)
	}
}
