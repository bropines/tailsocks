package appctr

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
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
	"tun_excluded_apps", "taildrop_root_uri", "taildrop_folder_path", "taildrive_shares", "storage_permission",
	"storage", "vpn", "grant", "grant_permission", "permission",
	// serving this device to others
	"serve", "funnel", "serve_config", "taildrive_proxy_ip", "taildrive_proxy_port", "taildrive_proxy_auth_enabled",
	"taildrive_proxy_username",
	// this feature itself, and the two keys the bridge keeps for its own calls into the app
	"remote_management", "remote_pair", "remote_revoke", "pairing_code", remoteKeyReport, remoteKeyAsk,
	// backups, updates, lifecycle switches, what the device looks like to the person in front of it
	"backup_export", "backup_import", "update", "install_update", "auto_start", "force_bg", "service_watchdog",
	"auto_reconnect_attempts", "app_locale", "ui_device_override", "show_message", "message",
	// odd shapes
	"", " ", "accept_dns ", "ACCEPT_DNS", "accept_dns\x00", "../accept_dns", "accept_dns,authkey", "*",
	RemoteKeyExitNode, "device_class", "app_version",
}

// rtAllowList is the lists as the review agreed to them, and as the app's own
// tests read them (app/src/test/.../RemoteAllowListTest.kt): neither side can
// grow one without the other noticing.
type rtAllowList struct {
	// Settings are "key", or "key=value" for a switch that goes one way.
	Settings    []string          `json:"settings"`
	ReadOnly    []string          `json:"readOnly"`
	Actions     []string          `json:"actions"`
	Permissions []string          `json:"permissions"`
	Askable     []string          `json:"askable"`
	AskResults  []string          `json:"askResults"`
	Routes      []string          `json:"routes"`
	Rights      map[string]string `json:"rights"`
	Report      string            `json:"report"`
	Ask         string            `json:"ask"`
}

func rtCurrentAllowList() rtAllowList {
	l := rtAllowList{Rights: map[string]string{}, Report: remoteKeyReport, Ask: remoteKeyAsk}
	for _, s := range remoteSettings {
		switch {
		case !s.writable:
			l.ReadOnly = append(l.ReadOnly, s.key)
		case s.only != "":
			l.Settings = append(l.Settings, s.key+"="+s.only)
		default:
			l.Settings = append(l.Settings, s.key)
		}
	}
	l.Actions = append(l.Actions, remoteActions...)
	l.Permissions = append(l.Permissions, remotePermissions...)
	for _, id := range remotePermissions {
		if remoteAskable[id] {
			l.Askable = append(l.Askable, id)
		}
	}
	for v := range remoteAskResults {
		l.AskResults = append(l.AskResults, v)
	}
	sort.Strings(l.AskResults)
	for _, rt := range remoteRoutes {
		l.Routes = append(l.Routes, rt.method+" "+rt.path)
	}
	for op, class := range remoteDefaultPolicy().Rights {
		l.Rights[op] = class.String()
	}
	return l
}

// The allow-lists are exactly the file both halves are tested against;
// growing one means changing that file on purpose.
func TestRemoteAllowListFile(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "remote_allowlist.json"))
	if err != nil {
		t.Fatal(err)
	}
	var want rtAllowList
	if err := json.Unmarshal(raw, &want); err != nil {
		t.Fatal(err)
	}
	got, _ := json.MarshalIndent(rtCurrentAllowList(), "", "  ")
	expect, _ := json.MarshalIndent(want, "", "  ")
	if string(got) != string(expect) {
		t.Errorf("the allow-lists are not testdata/remote_allowlist.json:\n got %s\nwant %s", got, expect)
	}
	// Every askable permission is a listed one, and asking is never for a guest.
	for id := range remoteAskable {
		found := false
		for _, p := range remotePermissions {
			found = found || p == id
		}
		if !found {
			t.Errorf("%q can be asked for and is not a listed permission", id)
		}
	}
	if remoteAskable["vpn"] {
		t.Error("the VPN consent can be asked for from afar")
	}
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
	if got := strings.Join(writable, ","); got != "accept_dns,accept_routes,exit_node_lan_access,auto_reconnect,taildrive_enabled,taildrive_proxy_enabled" {
		t.Errorf("writable settings: %s", got)
	}
	if got := strings.Join(readonly, ","); got != "device_class,app_version" {
		t.Errorf("read-only settings: %s", got)
	}
	if got := strings.Join(remoteActions, ","); got != "reconnect,receive_folder_default" {
		t.Errorf("actions: %s", got)
	}
	// What widens the device's exposure goes off only; what keeps the device
	// reachable goes on only.
	for key, only := range map[string]string{
		"accept_dns": "", "accept_routes": "", "exit_node_lan_access": "",
		"auto_reconnect": "true", "taildrive_enabled": "false", "taildrive_proxy_enabled": "false",
	} {
		if s, _ := remoteSettingFor(key); s.only != only {
			t.Errorf("%s may be written as %q from afar, want %q", key, s.only, only)
		}
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
		for _, word := range []string{"stop", "logout", "login", "key", "account", "profile", "root", "serve", "funnel", "drive", "download", "content", "exec", "localapi", "prefs", "log"} {
			if strings.Contains(rt.path, word) {
				t.Errorf("route %s", rt.path)
			}
		}
		if _, ok := remoteDefaultPolicy().Rights[rt.op]; rt.op != "" && !ok {
			t.Errorf("route %s: operation %q has no entry in the rights table", rt.path, rt.op)
		}
	}
	want := "GET /v1/hello,POST /v1/pair,POST /v1/unpair,GET /v1/status,GET /v1/exit-nodes,POST /v1/exit-node," +
		"GET /v1/settings,POST /v1/settings,POST /v1/actions,GET /v1/files,POST /v1/files/delete,POST /v1/permissions/request"
	if got := strings.Join(paths, ","); got != want {
		t.Errorf("routes:\n got %s\nwant %s", got, want)
	}
	// Exactly two routes answer without the token, and the rights table names
	// every other one.
	var open []string
	for _, rt := range remoteRoutes {
		if rt.op == "" {
			open = append(open, rt.path)
		}
	}
	if got := strings.Join(open, ","); got != "/v1/hello,/v1/pair" {
		t.Errorf("routes that need no token: %s", got)
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
		value := "true"
		if s.only != "" {
			// A switch that goes one way from afar: the other way is refused
			// before the app hears of it.
			value = s.only
			other := map[string]string{"true": "false", "false": "true"}[s.only]
			r.want(r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": s.key, "value": other}), 403, "forbidden")
		}
		if a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": s.key, "value": value}); a.status != 200 {
			t.Errorf("setting %s: %d %s", s.key, a.status, a.raw)
		}
	}
	for _, action := range remoteActions {
		if a := r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": action}); a.status != 200 {
			t.Errorf("%s: %d %s", action, a.status, a.raw)
		}
	}
	want := "set accept_dns=true,set accept_routes=true,set exit_node_lan_access=true,set auto_reconnect=true," +
		"set taildrive_enabled=false,set taildrive_proxy_enabled=false,action reconnect,action receive_folder_default"
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
		only, _ := s["only"].(string)
		if !ok || s["writable"] != def.writable || s["kind"] != def.kind || only != def.only {
			t.Errorf("setting %v", s)
		}
	}
	if len(got) != len(remoteSettings) || got["accept_dns"] != "true" || got["accept_routes"] != "true" || got["device_class"] != "tv" || got["app_version"] != "4.11.0" ||
		got["auto_reconnect"] != "true" || got["taildrive_enabled"] != "false" || got["taildrive_proxy_enabled"] != "false" {
		t.Errorf("settings read back: %v", got)
	}
	ops := strings.Join(r.ops(), ",")
	if !strings.HasPrefix(ops, "action,action,setting,setting,setting,setting,setting,setting,") {
		t.Errorf("action log: %s", ops)
	}
	if ev := r.m.actions()[2]; ev.Detail != "taildrive_proxy_enabled=false" || !ev.OK || ev.Name != rtPhone.name || ev.Class != "owner" {
		t.Errorf("logged: %+v", ev)
	}
	if ev := r.m.actions()[0]; ev.Op != "action" || ev.Detail != "receive_folder_default" || !ev.OK {
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
		p.Rights[remoteOpSetSetting] = remoteGuest
	})
	guest = r.pair(rtFriend)
	r.want(r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]string{"id": rtExitA.id}), 403, "forbidden")
	if a := r.call(rtFriend, "POST", "/v1/settings", guest, map[string]string{"key": "accept_dns", "value": "false"}); a.status != 200 {
		t.Errorf("a guest changing a setting under a policy that allows it: %d %s", a.status, a.raw)
	}
}

// The log stays on the device: no route returns it, whoever asks and however.
func TestRemoteNoLogRoute(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	LogAndroid("INFO", "TEST", "Running Tailscale CLI args=[up --auth-key=0123456789abcdef0123456789abcdef]")
	for _, path := range []string{"/v1/log", "/v1/log?lines=100", "/v1/logs", "/v1/status/log", "/v1/files/log"} {
		for _, method := range []string{"GET", "POST"} {
			a := r.call(rtPhone, method, path, owner, nil)
			if a.status != 404 || strings.Contains(a.raw, "auth-key") {
				t.Errorf("%s %s: %d %s", method, path, a.status, a.raw)
			}
		}
	}
	// Nor does anything else the owner can read carry a line of it.
	for _, path := range []string{"/v1/status", "/v1/settings", "/v1/files", "/v1/exit-nodes", "/v1/hello"} {
		if a := r.call(rtPhone, "GET", path, owner, nil); a.status != 200 || strings.Contains(a.raw, "auth-key") || strings.Contains(a.raw, "0123456789abcdef") {
			t.Errorf("GET %s: %d %s", path, a.status, a.raw)
		}
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

// What the managed device's own screen is told.
func TestRemoteState(t *testing.T) {
	r := newRemoteRig(t)
	st := r.m.state()
	if !st.Available || !st.Enabled || !st.Listening || st.Port != RemotePort || st.Error != "" || st.Inbound != "netstack" || !st.Delegate || st.Paired != 0 || st.Pairing != nil || st.LastPairing != nil {
		t.Errorf("fresh state: %+v", st)
	}
	if p := st.Policy; !p.AllowGuests || p.CodeSeconds != 600 || p.CodeAttempts != 5 || p.GuestSeconds != 86400 || p.OwnerSeconds != 0 || p.MaxPairings != 8 {
		t.Errorf("policy: %+v", p)
	}
	if st.Unsaved {
		t.Error("a fresh store reads as unsaved")
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

	// Two runs reach for the port together and one of them loses it to the
	// other: the endpoint is up, and the loser's "address already in use" is
	// not left in the state beside "listening".
	m.listen(addr)
	if st := m.state(); !st.Listening || st.Error != "" {
		t.Errorf("after a second listen on the port we hold: listening=%v error=%q", st.Listening, st.Error)
	}
	m.mu.Lock()
	stale := m.listenErr
	m.mu.Unlock()
	if stale != "" {
		t.Errorf("an error was kept while listening: %q", stale)
	}
	// And one that did get recorded, while there was no listener, does not
	// outlive the next successful start.
	m.release()
	squatter, err = net.Listen("tcp4", addr)
	if err != nil {
		t.Fatal(err)
	}
	m.listen(addr)
	if st := m.state(); st.Listening || st.Error == "" {
		t.Errorf("with the port taken again: %+v", st)
	}
	squatter.Close()
	m.listen(addr)
	if st := m.state(); !st.Listening || st.Error != "" {
		t.Errorf("listening again: listening=%v error=%q", st.Listening, st.Error)
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

// A handler that fails takes its own connection down and nothing else.
func TestRemoteHandlerFailure(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	r.m.taildropDir = func() string { panic("a bug in a handler, with " + token + " in its message") }
	if !r.shut(rtPhone, "GET", "/v1/files", token, nil) {
		t.Error("an answer from a handler that panicked")
	}
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("the next request: %d %s", a.status, a.raw)
	}
	// What is logged says that it happened, not what was in it.
	said := false
	for _, line := range strings.Split(GetLogs(), "\n") {
		if strings.Contains(line, token) {
			t.Errorf("the token is in the log: %q", line)
		}
		said = said || strings.Contains(line, "a request handler failed")
	}
	if !said {
		t.Error("the failure is not in the log")
	}
}

// The bridge's own lifecycle: letting go of a daemon closes the listener and
// keeps the code, applying settings that switch the feature off drops both.
func TestRemoteFollowsTheDaemon(t *testing.T) {
	saved := remote
	remote = newRemoteManager(remoteDefaultPolicy(), newRemoteFakeClock(), newRemoteFakeDaemon())
	t.Cleanup(func() {
		remote.configure(false, "")
		remote = saved
	})
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ln.Close()
	store := filepath.Join(t.TempDir(), "remote", "p.json")
	remote.apply(true, store, addr)

	var shown struct {
		OK   bool   `json:"ok"`
		Code string `json:"code"`
		QR   string `json:"qr"`
	}
	if err := json.Unmarshal([]byte(RemoteStartPairing()), &shown); err != nil || !shown.OK || !remoteIsCode(shown.Code) {
		t.Fatalf("RemoteStartPairing: %+v, %v", shown, err)
	}
	if !strings.Contains(RemoteParseQR(shown.QR), shown.Code) {
		t.Errorf("the QR %q does not carry the code", shown.QR)
	}
	if !strings.Contains(RemoteStateJSON(), `"listening":true`) || !strings.Contains(RemoteStateJSON(), `"code":"`+shown.Code+`"`) {
		t.Fatalf("state: %s", RemoteStateJSON())
	}

	// Stop, a restart, a detach: everything the bridge owns is released.
	releaseGoResources()
	if c, err := net.DialTimeout("tcp4", addr, time.Second); err == nil {
		c.Close()
		t.Fatal("still listening after the daemon was released")
	}
	if st := remote.state(); st.Listening || !st.Enabled || st.Pairing == nil || st.Pairing.Code != shown.Code {
		t.Fatalf("after the release: %+v", st)
	}

	// Settings that switch it off, applied whatever the daemon is doing.
	remoteSwitch(&StartOptions{})
	if st := remote.state(); st.Enabled || st.Listening || st.Pairing != nil || !st.Available {
		t.Fatalf("after switching off: %+v", st)
	}
	RemoteCancelPairing()
	if RemotePairedJSON() != "[]" || RemoteActionsJSON() != "[]" {
		t.Error("an empty store answered with something")
	}
	if got := RemoteRevoke("nPHONE"); got != `{"ok":false,"removed":0,"error":"not_paired"}` {
		t.Errorf("RemoteRevoke of nobody: %s", got)
	}
	if got := RemoteRevokeAll(); got != `{"ok":true,"removed":0}` {
		t.Errorf("RemoteRevokeAll of nothing: %s", got)
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
