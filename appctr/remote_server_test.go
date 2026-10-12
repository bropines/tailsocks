package appctr

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// rtNever is what must never be reachable remotely, by any name the app or
// the daemon gives it. The allow-lists are compared against it, and every
// entry is sent to the endpoint as a setting and as an action.
var rtNever = []string{
	// stopping, and anything that ends the session
	"stop", "disconnect", "shutdown", "exit", "kill", "quit", "logout", "sign_out", "signout", "do_reset", "reset",
	"force_stop", "desired_running", "WantRunning", "toggle",
	// accounts, the login server, credentials
	"authkey", "auth_key", "AuthKey", "login_server", "LoginServer", "ControlURL", "login", "login_interactive",
	"switch_account", "add_account", "delete_account", "profile", "automation_secret", "automation_enabled",
	"socks5_user", "socks5_pass", "cp_user", "cp_pass", "taildrive_proxy_pass", "admin_api_keys", "tailcat_key",
	// listeners and what they are bound to
	"socks5", "httpproxy", "dns_proxy", "lan_access_enabled", "enable_webui", "webui_addr", "taildrive_proxy_ip",
	// the daemon's own arguments and network position
	"extra_args_raw", "ExtraUpArgs", "hostname", "advertise_routes", "advertise_exit_node", "AdvertiseRoutes", "RunSSH",
	"ShieldsUp", "cp_enabled", "cp_host", "cp_byedpi_enabled", "cp_byedpi_flags", "honest_hostinfo",
	// root mode, and what needs a dialog or a gesture on the device
	"root_mode_enabled", "root_tun_enabled", "root_dns_redirect", "root_vpn_bypass", "tun_mode_enabled", "tun_engine",
	"tun_excluded_apps", "taildrop_root_uri", "taildrive_enabled", "taildrive_shares", "storage_permission",
	// serving this device to others
	"serve", "funnel", "serve_config",
	// this feature itself
	"remote_management", "remote_pair", "remote_revoke", "pairing_code",
	// backups, updates, lifecycle switches
	"backup_export", "backup_import", "update", "install_update", "auto_start", "force_bg", "service_watchdog",
	// odd shapes
	"", " ", "accept_dns ", "ACCEPT_DNS", "accept_dns\x00", "../accept_dns", "accept_dns,authkey", "*",
	RemoteKeyExitNode, "device_class", "app_version",
}

// The allow-lists are exactly what the review agreed to; growing one means
// changing this test on purpose.
func TestRemoteAllowListContents(t *testing.T) {
	var writable, readonly []string
	for _, s := range remoteSettings {
		if s.writable {
			writable = append(writable, s.key)
			if s.kind != "bool" {
				t.Errorf("%s: a writable setting of kind %q has no validation", s.key, s.kind)
			}
		} else {
			readonly = append(readonly, s.key)
		}
	}
	if got := strings.Join(writable, ","); got != "accept_dns,accept_routes,exit_node_lan_access" {
		t.Errorf("writable settings: %s", got)
	}
	if got := strings.Join(readonly, ","); got != "device_class,app_version" {
		t.Errorf("read-only settings: %s", got)
	}
	if got := strings.Join(remoteActions, ","); got != "reconnect" {
		t.Errorf("actions: %s", got)
	}
	for _, never := range rtNever {
		if s, ok := remoteSettingFor(never); ok && s.writable {
			t.Errorf("%q is a writable remote setting", never)
		}
		if remoteActionKnown(never) {
			t.Errorf("%q is a remote action", never)
		}
	}
	// No route's path speaks of what must stay on the device.
	var paths []string
	for _, rt := range remoteRoutes {
		paths = append(paths, rt.method+" "+rt.path)
		for _, word := range []string{"stop", "logout", "login", "key", "account", "profile", "root", "serve", "funnel", "drive", "download", "content", "exec", "localapi", "prefs"} {
			if strings.Contains(rt.path, word) {
				t.Errorf("route %s", rt.path)
			}
		}
		if _, ok := remoteDefaultPolicy().Rights[rt.op]; rt.op != "" && !ok {
			t.Errorf("route %s: operation %q has no entry in the rights table", rt.path, rt.op)
		}
	}
	want := "GET /v1/hello,POST /v1/pair,POST /v1/unpair,GET /v1/status,GET /v1/exit-nodes,POST /v1/exit-node," +
		"GET /v1/settings,POST /v1/settings,POST /v1/actions,GET /v1/files,POST /v1/files/delete,GET /v1/log"
	if got := strings.Join(paths, ","); got != want {
		t.Errorf("routes:\n got %s\nwant %s", got, want)
	}
	// An operation the rights table does not name is the owner's alone.
	p := remoteDefaultPolicy()
	if p.allows("something new", remoteGuest) || !p.allows("something new", remoteOwner) || p.allows(remoteOpStatus, remoteNobody) {
		t.Error("the default for an unnamed operation")
	}
}

// Nothing that is not on a list reaches the app, whoever asks.
func TestRemoteAllowListEnforced(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	before := len(r.app.called())
	for _, key := range rtNever {
		for _, value := range []string{"true", "false", "", "1", "tskey-auth-abc"} {
			a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": key, "value": value})
			if a.status != 403 || a.str("error") != "forbidden" {
				t.Errorf("setting %q=%q: %d %s", key, value, a.status, a.raw)
			}
		}
		a := r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": key})
		if a.status != 403 || a.str("error") != "forbidden" {
			t.Errorf("action %q: %d %s", key, a.status, a.raw)
		}
	}
	// A listed setting's name is not an action, nor the other way round.
	r.want(r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": "accept_dns"}), 403, "forbidden")
	r.want(r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": "reconnect", "value": "true"}), 403, "forbidden")
	if calls := r.app.called(); len(calls) != before {
		t.Fatalf("the app was called for something not on a list: %q", calls[before:])
	}
	if !r.hasOp("denied") {
		t.Errorf("the attempts left no trace: %q", r.ops())
	}

	// What is on the lists goes through, checked.
	for _, s := range remoteSettings {
		if !s.writable {
			continue
		}
		for _, bad := range []string{"", "yes", "1", "TRUE", "true ", "null", "tskey-auth-abc"} {
			r.want(r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": s.key, "value": bad}), 400, "bad_request")
		}
		if a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": s.key, "value": "true"}); a.status != 200 {
			t.Errorf("setting %s: %d %s", s.key, a.status, a.raw)
		}
	}
	if a := r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": "reconnect"}); a.status != 200 {
		t.Errorf("reconnect: %d %s", a.status, a.raw)
	}
	want := "set accept_dns=true,set accept_routes=true,set exit_node_lan_access=true,action reconnect"
	if got := strings.Join(r.app.writes(), ","); got != want {
		t.Errorf("what reached the app:\n got %s\nwant %s", got, want)
	}

	// Read back, with the facts.
	a := r.call(rtPhone, "GET", "/v1/settings", owner, nil)
	if a.status != 200 {
		t.Fatalf("settings: %d %s", a.status, a.raw)
	}
	got := map[string]string{}
	for _, raw := range a.obj["settings"].([]any) {
		s := raw.(map[string]any)
		key := s["key"].(string)
		got[key] = s["value"].(string)
		def, ok := remoteSettingFor(key)
		if !ok || s["writable"] != def.writable || s["kind"] != def.kind {
			t.Errorf("setting %v", s)
		}
	}
	if len(got) != len(remoteSettings) || got["accept_dns"] != "true" || got["accept_routes"] != "true" || got["device_class"] != "tv" || got["app_version"] != "4.11.0" {
		t.Errorf("settings read back: %v", got)
	}
	ops := strings.Join(r.ops(), ",")
	if !strings.HasPrefix(ops, "action,setting,setting,setting,") {
		t.Errorf("action log: %s", ops)
	}
	if ev := r.m.actions()[1]; ev.Detail != "exit_node_lan_access=true" || !ev.OK || ev.Name != rtPhone.name || ev.Class != "owner" {
		t.Errorf("logged: %+v", ev)
	}
}

// What a guest may do is a row of the rights table, and nothing else.
func TestRemoteGuestRights(t *testing.T) {
	r := newRemoteRig(t)
	guest := r.pair(rtFriend)
	dir := t.TempDir()
	r.m.taildropDir = func() string { return dir }
	if err := os.WriteFile(filepath.Join(dir, "photo.jpg"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}

	for _, path := range []string{"/v1/status", "/v1/exit-nodes", "/v1/settings", "/v1/files"} {
		if a := r.call(rtFriend, "GET", path, guest, nil); a.status != 200 {
			t.Errorf("guest GET %s: %d %s", path, a.status, a.raw)
		}
	}
	if a := r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]string{"id": rtExitA.id}); a.status != 200 {
		t.Errorf("guest picking an exit node: %d %s", a.status, a.raw)
	}
	writes := len(r.app.writes())
	r.want(r.call(rtFriend, "POST", "/v1/settings", guest, map[string]string{"key": "accept_dns", "value": "false"}), 403, "forbidden")
	r.want(r.call(rtFriend, "POST", "/v1/actions", guest, map[string]string{"action": "reconnect"}), 403, "forbidden")
	r.want(r.call(rtFriend, "POST", "/v1/files/delete", guest, map[string]string{"name": "photo.jpg"}), 403, "forbidden")
	r.want(r.call(rtFriend, "GET", "/v1/log", guest, nil), 403, "forbidden")
	if len(r.app.writes()) != writes {
		t.Errorf("a refused guest reached the app: %q", r.app.writes())
	}
	if _, err := os.Stat(filepath.Join(dir, "photo.jpg")); err != nil {
		t.Error("a guest deleted a file")
	}
	// The settings a guest sees are marked as not for it to change.
	a := r.call(rtFriend, "GET", "/v1/settings", guest, nil)
	for _, raw := range a.obj["settings"].([]any) {
		if s := raw.(map[string]any); s["writable"] != false {
			t.Errorf("guest sees %v as writable", s)
		}
	}
	if !r.hasOp("denied") {
		t.Errorf("action log: %q", r.ops())
	}

	// The table is the knob: take a right away, hand one out.
	r = newRemoteRig(t, func(p *remotePolicy) {
		p.Rights[remoteOpSetExitNode] = remoteOwner
		p.Rights[remoteOpLog] = remoteGuest
	})
	guest = r.pair(rtFriend)
	r.want(r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]string{"id": rtExitA.id}), 403, "forbidden")
	if a := r.call(rtFriend, "GET", "/v1/log", guest, nil); a.status != 200 {
		t.Errorf("guest log under a policy that allows it: %d", a.status)
	}
}

func TestRemoteStatus(t *testing.T) {
	r := newRemoteRig(t)
	r.d.set(func(d *remoteFakeDaemon) {
		d.health = []string{"Tailscale can't reach the DNS servers", strings.Repeat("h", 2000)}
	})
	token := r.pair(rtPhone)
	a := r.call(rtPhone, "GET", "/v1/status", token, nil)
	if a.status != 200 {
		t.Fatalf("status: %d %s", a.status, a.raw)
	}
	for k, want := range map[string]any{
		"running": true, "backendState": "Running", "version": "1.104.1-test", "appVersion": "4.11.0",
		"deviceClass": "tv", "name": rtSelf.name, "dnsName": rtSelf.name + ".tailnet.ts.net", "nodeId": rtSelf.id,
	} {
		if a.obj[k] != want {
			t.Errorf("%s = %v, want %v", k, a.obj[k], want)
		}
	}
	if _, has := a.obj["exitNode"]; has {
		t.Errorf("an exit node without one in use: %s", a.raw)
	}
	health := a.obj["health"].([]any)
	if len(health) != 2 || len(health[1].(string)) != 300 {
		t.Errorf("health: %v", health)
	}
	if you := a.obj["you"].(map[string]any); you["class"] != "owner" || you["left"] != float64(-1) {
		t.Errorf("you: %v", you)
	}
	// Nothing in a status answer that could be a secret or a path.
	for _, word := range []string{"token", "sha", "/data/", "socks", "key"} {
		if strings.Contains(strings.ToLower(a.raw), word) {
			t.Errorf("status mentions %q: %s", word, a.raw)
		}
	}
}

func TestRemoteExitNodes(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	a := r.call(rtPhone, "GET", "/v1/exit-nodes", token, nil)
	nodes, _ := a.obj["nodes"].([]any)
	if a.status != 200 || len(nodes) != 2 {
		t.Fatalf("exit nodes: %d %s", a.status, a.raw)
	}
	first, second := nodes[0].(map[string]any), nodes[1].(map[string]any)
	if first["id"] != rtExitA.id || first["name"] != rtExitA.name || first["online"] != true || first["selected"] != nil {
		t.Errorf("first: %v", first)
	}
	if second["id"] != rtExitB.id || second["online"] != false || second["country"] != "Sweden" {
		t.Errorf("second: %v", second)
	}

	// Only a node the daemon offers; the app never sees an id made up by a
	// paired device.
	for _, id := range []string{rtPhone.id, rtFriend.id, rtSelf.id, "nNOSUCH", "100.64.0.20"} {
		if a := r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": id}); a.status != 404 && a.status != 400 {
			t.Errorf("exit node %q: %d %s", id, a.status, a.raw)
		}
	}
	for _, id := range []string{"../x", "n A", "best", strings.Repeat("n", 65), "n\x00"} {
		a := r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": id})
		if id == "best" {
			r.want(a, 404, "not_found")
		} else {
			r.want(a, 400, "bad_request")
		}
	}
	if w := r.app.writes(); len(w) != 0 {
		t.Fatalf("the app was handed an exit node: %q", w)
	}

	if a := r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": rtExitA.id}); a.status != 200 {
		t.Fatalf("set: %d %s", a.status, a.raw)
	}
	// The app applies it; the daemon then reports it.
	r.d.change(rtExitA, func(n *rtNode) { n.exitNode = true })
	st := r.call(rtPhone, "GET", "/v1/status", token, nil)
	if exit, _ := st.obj["exitNode"].(map[string]any); exit["id"] != rtExitA.id || exit["name"] != rtExitA.name || exit["online"] != true {
		t.Errorf("status after: %s", st.raw)
	}
	if a := r.call(rtPhone, "POST", "/v1/exit-node", token, map[string]string{"id": ""}); a.status != 200 {
		t.Fatalf("clear: %d %s", a.status, a.raw)
	}
	if got := strings.Join(r.app.writes(), ","); got != "set exit_node="+rtExitA.id+",set exit_node=" {
		t.Errorf("what reached the app: %s", got)
	}
	log := r.m.actions()
	if log[0].Op != "exit_node" || log[0].Detail != "none" || log[1].Detail != rtExitA.name {
		t.Errorf("action log: %+v", log[:2])
	}
}

func TestRemoteFileNames(t *testing.T) {
	for _, ok := range []string{"photo.jpg", "отчёт за октябрь.pdf", "a", "archive.tar.gz", "name with spaces", "partial", "x.partial.txt", "~file", "-rf", "CON", "a..b"} {
		if !remoteFileNameOK(ok) {
			t.Errorf("%q refused", ok)
		}
	}
	for _, bad := range []string{
		"", ".", "..", "../x", "../../etc/passwd", "a/b", "/etc/passwd", "a/../b", "./a", "a/", `a\b`, `..\x`, `C:\x`,
		".hidden", ".taildrop-1.part", "x.partial", "x.deleted", "movie.mkv.partial",
		"a\x00b", "a\nb", "a\rb", "a\tb", "a\x7fb", "bad\xffutf8", strings.Repeat("n", 256),
	} {
		if remoteFileNameOK(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestRemoteFiles(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	root := t.TempDir()
	dir := filepath.Join(root, "taildrop", "1760000000000")
	if err := os.MkdirAll(filepath.Join(dir, "sub"), 0o700); err != nil {
		t.Fatal(err)
	}
	write := func(path string) {
		t.Helper()
		if err := os.WriteFile(path, []byte("contents"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	outside := filepath.Join(root, "tailscaled.state")
	write(outside)
	write(filepath.Join(root, "taildrop", "neighbour.txt"))
	for _, name := range []string{"photo.jpg", "отчёт.pdf", "movie.mkv.partial", ".hidden", "gone.txt", "gone.txt.deleted", filepath.Join("sub", "inner.txt")} {
		write(filepath.Join(dir, name))
	}
	if err := os.Symlink(outside, filepath.Join(dir, "link.state")); err != nil {
		t.Fatal(err)
	}

	// No Taildrop directory yet: an empty list, not an error.
	if a := r.call(rtPhone, "GET", "/v1/files", token, nil); a.status != 200 || len(a.obj["files"].([]any)) != 0 {
		t.Errorf("files with no directory: %d %s", a.status, a.raw)
	}
	r.m.taildropDir = func() string { return dir }

	list := func() string {
		t.Helper()
		a := r.call(rtPhone, "GET", "/v1/files", token, nil)
		if a.status != 200 {
			t.Fatalf("files: %d %s", a.status, a.raw)
		}
		// Names, sizes and dates. Never where the files are.
		if strings.Contains(a.raw, root) || strings.Contains(strings.ToLower(a.raw), "path") {
			t.Errorf("the listing gives a path away: %s", a.raw)
		}
		var names []string
		for _, raw := range a.obj["files"].([]any) {
			f := raw.(map[string]any)
			if f["size"] != float64(8) || f["modTime"].(float64) < 1e9 {
				t.Errorf("file %v", f)
			}
			names = append(names, f["name"].(string))
		}
		return strings.Join(names, ",")
	}
	if got := list(); got != "photo.jpg,отчёт.pdf" {
		t.Fatalf("listed: %s", got)
	}

	// Everything that is not one of the listed names stays where it is.
	for _, name := range []string{
		"../neighbour.txt", "../../tailscaled.state", outside, "sub/inner.txt", "sub", ".", "..", "",
		".hidden", "movie.mkv.partial", "gone.txt", "gone.txt.deleted", "link.state", "nothing-here.txt",
		"photo.jpg/", "./photo.jpg", "photo.jpg\x00", "PHOTO.JPG", `..\neighbour.txt`,
	} {
		a := r.call(rtPhone, "POST", "/v1/files/delete", token, map[string]string{"name": name})
		if a.status != 400 && a.status != 404 {
			t.Errorf("delete %q: %d %s", name, a.status, a.raw)
		}
	}
	for _, kept := range []string{
		outside, filepath.Join(root, "taildrop", "neighbour.txt"), filepath.Join(dir, "sub", "inner.txt"),
		filepath.Join(dir, ".hidden"), filepath.Join(dir, "movie.mkv.partial"), filepath.Join(dir, "gone.txt"),
		filepath.Join(dir, "gone.txt.deleted"), filepath.Join(dir, "link.state"), filepath.Join(dir, "photo.jpg"),
	} {
		if _, err := os.Lstat(kept); err != nil {
			t.Errorf("%s is gone", kept)
		}
	}
	if r.hasOp("file_deleted") {
		t.Errorf("action log: %q", r.ops())
	}

	if a := r.call(rtPhone, "POST", "/v1/files/delete", token, map[string]string{"name": "отчёт.pdf"}); a.status != 200 {
		t.Fatalf("delete: %d %s", a.status, a.raw)
	}
	if got := list(); got != "photo.jpg" {
		t.Errorf("listed after the delete: %s", got)
	}
	r.want(r.call(rtPhone, "POST", "/v1/files/delete", token, map[string]string{"name": "отчёт.pdf"}), 404, "not_found")
	if ev := r.m.actions()[0]; ev.Op != "file_deleted" || ev.Detail != "отчёт.pdf" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	// There is no route that returns what is in a file.
	for _, path := range []string{"/v1/files/photo.jpg", "/v1/files/get", "/v1/files/download", "/v1/file"} {
		r.want(r.call(rtPhone, "GET", path, token, nil), 404, "not_found")
	}
}

func TestRemoteLog(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	var entries []LogEntry
	add := func(msg string) {
		entries = append(entries, LogEntry{Unix: int64(len(entries)), Level: "INFO", Category: "CORE", Message: msg})
	}
	for i := 0; i < 300; i++ {
		add("ordinary line")
	}
	secrets := []string{
		"Login with authkey tskey-auth-kABCDEF-GHIJKL",
		"LocalAPI: AuthURL ready url=https://login.tailscale.com/a/1a2b3c4d5e6f",
		"register at https://hs.example.com/register/hskey-authreq-abcdef",
		"Proxy: Using SOCKS5 via ALL_PROXY url=socks5://user:hunter2@10.0.0.1:1080",
		"socks5 password accepted",
		"Authorization: Bearer abc",
		"automation secret changed",
	}
	for _, s := range secrets {
		add(s)
	}
	add(strings.Repeat("й", 2000))
	add("the last line")
	var asked atomic.Int64
	r.m.logTail = func(n int) []LogEntry {
		asked.Store(int64(n))
		if len(entries) > n {
			return entries[len(entries)-n:]
		}
		return entries
	}

	a := r.call(rtPhone, "GET", "/v1/log", token, nil)
	lines := a.obj["lines"].([]any)
	if a.status != 200 || asked.Load() != remoteLogDefault || len(lines) != remoteLogDefault-len(secrets) {
		t.Fatalf("log: %d, asked for %d, %d lines", a.status, asked.Load(), len(lines))
	}
	for _, s := range []string{"tskey", "hunter2", "/a/1a2b", "hskey", "Bearer", "password", "secret"} {
		if strings.Contains(a.raw, s) {
			t.Errorf("the log tail carries %q", s)
		}
	}
	last := lines[len(lines)-1].(map[string]any)
	long := lines[len(lines)-2].(map[string]any)["message"].(string)
	if last["message"] != "the last line" || last["category"] != "CORE" || len(long) > remoteLogLine+3 || !strings.HasSuffix(long, "…") {
		t.Errorf("last %v, long line %d bytes", last, len(long))
	}

	r.call(rtPhone, "GET", "/v1/log?lines=100000", token, nil)
	if asked.Load() != remoteLogMax {
		t.Errorf("asked for %d lines, want the cap %d", asked.Load(), remoteLogMax)
	}
	r.call(rtPhone, "GET", "/v1/log?lines=7", token, nil)
	if asked.Load() != 7 {
		t.Errorf("asked for %d lines, want 7", asked.Load())
	}
	for _, q := range []string{"0", "-5", "many", ""} {
		r.want(r.call(rtPhone, "GET", "/v1/log?lines="+q, token, nil), 400, "bad_request")
	}

	// The real buffer: its newest entries, oldest first.
	LogAndroid("INFO", "TEST", "remote log tail marker one")
	LogAndroid("INFO", "TEST", "remote log tail marker two")
	tail := remoteLogTail(2)
	if len(tail) != 2 || tail[0].Message != "remote log tail marker one" || tail[1].Message != "remote log tail marker two" {
		t.Errorf("tail of the buffer: %+v", tail)
	}
}

func TestRemoteAppUnavailable(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	set := map[string]string{"key": "accept_dns", "value": "false"}

	// The app refuses, in its own words.
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
	if ev := r.m.actions()[0]; ev.Op != "exit_node" || ev.OK {
		t.Errorf("a refused change is logged as %+v", ev)
	}
	// A setting it cannot read is marked, the rest still come.
	a = r.call(rtPhone, "GET", "/v1/settings", token, nil)
	if a.status != 200 || !strings.Contains(a.raw, `"error":"unavailable"`) {
		t.Errorf("settings while the app fails: %d %s", a.status, a.raw)
	}
	r.app.mu.Lock()
	r.app.fail = nil
	r.app.mu.Unlock()

	// The app hangs: the request is given up, and the next one is refused at
	// once rather than queued behind it.
	r.m.delegateTimeout = 100 * time.Millisecond
	block := make(chan struct{})
	r.app.mu.Lock()
	r.app.block = block
	r.app.mu.Unlock()
	began := time.Now()
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, set), 503, "unavailable")
	r.want(r.call(rtPhone, "POST", "/v1/actions", token, map[string]string{"action": "reconnect"}), 503, "unavailable")
	if took := time.Since(began); took > 3*time.Second {
		t.Errorf("two requests against a hung app took %v", took)
	}
	if calls := r.app.writes(); len(calls) != 4 {
		t.Errorf("calls into a hung app: %q", calls)
	}
	r.app.mu.Lock()
	r.app.block = nil
	r.app.mu.Unlock()
	close(block)
	deadline := time.Now().Add(2 * time.Second)
	for len(r.m.delegateSlot) != 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if a := r.call(rtPhone, "POST", "/v1/settings", token, set); a.status != 200 {
		t.Errorf("after the app came back: %d %s", a.status, a.raw)
	}

	// A value the app returns is checked too.
	r.app.mu.Lock()
	r.app.settings["accept_routes"] = "tskey-auth-leaked"
	r.app.settings["device_class"] = "<script>"
	r.app.mu.Unlock()
	r.m.setDelegate(r.app)
	a = r.call(rtPhone, "GET", "/v1/settings", token, nil)
	if strings.Contains(a.raw, "tskey") || strings.Contains(a.raw, "script") {
		t.Errorf("a value of the wrong kind was passed on: %s", a.raw)
	}

	// No app registered at all.
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
}

// What the managed device's own screen is told.
func TestRemoteState(t *testing.T) {
	r := newRemoteRig(t)
	st := r.m.state()
	if !st.Available || !st.Enabled || !st.Listening || st.Port != RemotePort || st.Error != "" || st.Inbound != "netstack" || !st.Delegate || st.Paired != 0 || st.Pairing != nil || st.LastPairing != nil {
		t.Errorf("fresh state: %+v", st)
	}
	if p := st.Policy; !p.OwnerNeedsCode || !p.AllowGuests || p.CodeSeconds != 600 || p.CodeAttempts != 5 || p.GuestSeconds != 86400 || p.OwnerSeconds != 0 || p.MaxPairings != 8 {
		t.Errorf("policy: %+v", p)
	}
	rev := st.Rev
	code := r.code()
	st = r.m.state()
	if st.Rev == rev || st.Pairing == nil || st.Pairing.Code != code || st.Pairing.Left != 600 || st.Pairing.AttemptsLeft != 5 || st.Pairing.LastWrong != nil {
		t.Errorf("with a code on show: %+v / %+v", st, st.Pairing)
	}
	rev = st.Rev
	if r.m.state().Rev != rev {
		t.Error("the revision moved though nothing happened")
	}
	r.pair(rtPhone)
	if st = r.m.state(); st.Rev == rev || st.Paired != 1 {
		t.Errorf("after a pairing: %+v", st)
	}

	// The daemon runs on a real TUN — native TUN, Root Mode — where a peer's
	// connection does not come to a loopback listener.
	r.d.set(func(d *remoteFakeDaemon) { d.tun = true })
	r.forgetSelf()
	if st = r.m.state(); st.Inbound != "none" {
		t.Errorf("inbound on a TUN: %q", st.Inbound)
	}
	r.d.set(func(d *remoteFakeDaemon) { d.down = true })
	r.forgetSelf()
	if st = r.m.state(); st.Inbound != "unknown" {
		t.Errorf("inbound without a daemon: %q", st.Inbound)
	}
}

// Start, restart and stop of the listener, on a real port.
func TestRemoteLifecycle(t *testing.T) {
	d, clock := newRemoteFakeDaemon(), newRemoteFakeClock()
	m := newRemoteManager(remoteDefaultPolicy(), clock, d)
	store := filepath.Join(t.TempDir(), "remote", "p.json")
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ln.Close()
	t.Cleanup(func() { m.apply(false, "", addr) })
	dial := func() bool {
		c, err := net.DialTimeout("tcp4", addr, time.Second)
		if err != nil {
			return false
		}
		c.Close()
		return true
	}

	// Off by default: nothing listens.
	m.apply(false, store, addr)
	if st := m.state(); st.Enabled || st.Listening || !st.Available || dial() {
		t.Fatalf("off: %+v", st)
	}
	m.apply(true, store, addr)
	if st := m.state(); !st.Enabled || !st.Listening || st.Error != "" || !dial() {
		t.Fatalf("on: %+v", st)
	}
	// Only on loopback.
	if host, _, _ := net.SplitHostPort(addr); host != "127.0.0.1" {
		t.Fatalf("listening on %s", addr)
	}
	m.apply(true, store, addr) // the same run again: nothing to do
	if v := m.startPairing(); !v.OK {
		t.Fatalf("no code: %+v", v)
	}
	// Settings applied to the running daemon carry the switch, not a profile:
	// the pairings and the code on show stay as they are.
	m.apply(true, "", addr)
	if st := m.state(); !st.Listening || st.Pairing == nil || m.store.path != store {
		t.Fatalf("after settings were applied: %+v", st)
	}

	// The daemon restarts: the listener goes, the port is free at once, the
	// code stays.
	if !m.release() || dial() {
		t.Fatal("still listening after a release")
	}
	if st := m.state(); st.Listening || !st.Enabled || st.Pairing == nil {
		t.Fatalf("released: %+v", st)
	}
	m.apply(true, store, addr)
	if st := m.state(); !st.Listening || st.Pairing == nil || !dial() {
		t.Fatalf("after the restart: %+v", st)
	}

	// Switched off: no listener, no code.
	m.apply(false, store, addr)
	if st := m.state(); st.Enabled || st.Listening || st.Pairing != nil || dial() {
		t.Fatalf("switched off: %+v", st)
	}
	if st := m.state(); st.LastPairing == nil || st.LastPairing.Result != "cancelled" {
		t.Errorf("the code that was on show: %+v", st.LastPairing)
	}

	// The port is taken — another app got there first: said, not hidden.
	squatter, err := net.Listen("tcp4", addr)
	if err != nil {
		t.Fatal(err)
	}
	m.apply(true, store, addr)
	if st := m.state(); !st.Enabled || st.Listening || st.Error == "" {
		t.Errorf("with the port taken: %+v", st)
	}
	squatter.Close()
	m.apply(true, store, addr)
	if st := m.state(); !st.Listening || st.Error != "" {
		t.Errorf("once the port is free: %+v", st)
	}
}

func TestRemoteApplyFromOptions(t *testing.T) {
	// The zero value of the options keeps the endpoint closed.
	if (StartOptions{}).RemoteManagement {
		t.Fatal("remote management is on by default")
	}
	remoteApply(nil)
	remoteSwitch(nil)
	remoteApply(&StartOptions{})
	if st := remote.state(); st.Enabled || st.Listening || st.Available {
		t.Errorf("after options that do not ask for it: %+v", st)
	}
	// Settings applied before any run has named a profile: there are no
	// pairings to serve, so the switch alone opens nothing.
	remoteSwitch(&StartOptions{RemoteManagement: true, SocketPath: "/nonexistent/tailscaled.sock", StatePath: "/nonexistent/states/1"})
	if st := remote.state(); st.Enabled || st.Listening || st.Available {
		t.Errorf("after the switch alone: %+v", st)
	}
	if v := remote.startPairing(); v.OK {
		t.Errorf("a code with the feature off: %+v", v)
	}
	if RemotePort < 1024 || RemotePort >= 32768 {
		t.Errorf("port %d is privileged or in the ephemeral range", RemotePort)
	}
}

func TestRemoteHostIsAddress(t *testing.T) {
	for _, ok := range []string{"100.64.0.1", "100.64.0.1:28115", "[fd7a:115c:a1e0::1]:28115", "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0::1]", "127.0.0.1:1"} {
		if !remoteHostIsAddress(ok) {
			t.Errorf("%q refused", ok)
		}
	}
	for _, bad := range []string{"", "tv.tailnet.ts.net", "tv.tailnet.ts.net:28115", "localhost:28115", "evil.example", "100.64.0.1.evil.example:28115", ":28115"} {
		if remoteHostIsAddress(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
}
