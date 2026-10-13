package appctr

// The level a device is managed at, what a status answer says of the device
// and of the caller's rights, guests kept out, the device's own name on its
// screen, and the changes the app is told to show.

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeFile(dir, name string) error {
	return os.WriteFile(filepath.Join(dir, name), []byte("contents"), 0o600)
}

func rtStrings(v any) []string {
	var out []string
	list, _ := v.([]any)
	for _, s := range list {
		out = append(out, s.(string))
	}
	return out
}

func rtHas(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

// rtFullRoutes is every route that takes full control, with a body that is
// well formed.
var rtFullRoutes = []struct {
	method, path string
	body         any
}{
	{"POST", "/v1/shares/add", map[string]string{"name": "Movies", "path": "/storage/emulated/0/Movies"}},
	{"POST", "/v1/storage/list", map[string]string{"path": ""}},
	{"POST", "/v1/storage/send", map[string]string{"path": "/storage/emulated/0/a.txt"}},
	{"GET", "/v1/diag/report", nil},
	{"GET", "/v1/diag/log", nil},
}

// What shows more of the device, or what is on it, is done at the second
// level only: switched on at the device, given up from anywhere.
func TestRemoteLevels(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	set := func(key, value string) rtReply {
		t.Helper()
		return r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": key, "value": value})
	}
	settings := func() map[string]map[string]any {
		t.Helper()
		out := map[string]map[string]any{}
		a := r.call(rtPhone, "GET", "/v1/settings", owner, nil)
		for _, raw := range a.obj["settings"].([]any) {
			s := raw.(map[string]any)
			out[s["key"].(string)] = s
		}
		return out
	}

	st := r.call(rtPhone, "GET", "/v1/status", owner, nil)
	if st.str("level") != "basic" || strings.Join(rtStrings(st.obj["caps"]), ",") != strings.Join(remoteCaps, ",") {
		t.Fatalf("status: level %q, caps %v", st.str("level"), st.obj["caps"])
	}
	can := rtStrings(st.obj["you"].(map[string]any)["can"])
	for _, op := range []string{"status", "settings.set", "action", "permission.request", "folders", "confirm", "identify", "shares.remove"} {
		if !rtHas(can, op) {
			t.Errorf("an owner at the basic level cannot %s: %v", op, can)
		}
	}
	gst := r.call(rtFriend, "GET", "/v1/status", guest, nil)
	gcan := rtStrings(gst.obj["you"].(map[string]any)["can"])
	if got := strings.Join(gcan, ","); got != "status,exit_nodes,exit_node.set,settings,files,unpair,confirm,identify" {
		t.Errorf("a guest can %s", got)
	}

	// At the basic level: not for an owner yet, never for a guest, and the app
	// hears of neither.
	before := len(r.app.called())
	for _, rt := range rtFullRoutes {
		op := ""
		for _, known := range remoteRoutes {
			if known.path == rt.path {
				op = known.op
			}
		}
		if rtHas(can, op) || rtHas(gcan, op) {
			t.Errorf("%s is offered at the basic level", op)
		}
		a := r.call(rtPhone, rt.method, rt.path, owner, rt.body)
		r.want(a, 403, "needs_full")
		r.want(r.call(rtFriend, rt.method, rt.path, guest, rt.body), 403, "forbidden")
	}
	r.want(set("advertise_routes", "192.168.5.0/24"), 403, "needs_full")
	if len(r.app.called()) != before {
		t.Fatalf("the app was asked for something that takes full control: %q", r.app.called()[before:])
	}
	if s := settings(); s["taildrive_enabled"]["only"] != "false" || s["taildrive_enabled"]["needs"] != "full" ||
		s["advertise_routes"]["needs"] != "full" || s["advertise_routes"]["only"] != nil || s["auto_reconnect"]["needs"] != nil {
		t.Errorf("settings at the basic level: %v", s)
	}
	// Clearing the routes narrows what the device offers: any owner may.
	if a := set("advertise_routes", ""); a.status != 200 {
		t.Errorf("clearing the routes: %d %s", a.status, a.raw)
	}

	// Full control, switched on at the device.
	r.m.setAccess(true, false)
	st = r.call(rtPhone, "GET", "/v1/status", owner, nil)
	can = rtStrings(st.obj["you"].(map[string]any)["can"])
	if st.str("level") != "full" || strings.Join(can, ",") != strings.Join(remoteOps, ",") {
		t.Errorf("an owner at the full level: %q can %v", st.str("level"), can)
	}
	gst = r.call(rtFriend, "GET", "/v1/status", guest, nil)
	if got := strings.Join(rtStrings(gst.obj["you"].(map[string]any)["can"]), ","); got != strings.Join(gcan, ",") {
		t.Errorf("full control gave a guest something: %s", got)
	}
	for _, rt := range rtFullRoutes {
		r.want(r.call(rtFriend, rt.method, rt.path, guest, rt.body), 403, "forbidden")
	}
	if s := settings(); s["taildrive_enabled"]["only"] != nil || s["taildrive_enabled"]["needs"] != nil || s["advertise_routes"]["needs"] != nil ||
		s["auto_reconnect"]["only"] != "true" || s["full_control"]["only"] != "false" {
		t.Errorf("settings at the full level: %v", s)
	}
	for _, key := range []string{"taildrive_enabled", "taildrive_proxy_enabled", "advertise_exit_node"} {
		if a := set(key, "true"); a.status != 200 || r.app.value(key) != "true" {
			t.Errorf("%s=true with full control: %d %s", key, a.status, a.raw)
		}
	}
	// A list of routes is read here and handed to the app the one way.
	if a := set("advertise_routes", " 192.168.5.7/24 ,10.0.0.0/8,192.168.5.0/24"); a.status != 200 || r.app.value("advertise_routes") != "192.168.5.0/24,10.0.0.0/8" {
		t.Errorf("routes: %d %s, the app has %q", a.status, a.raw, r.app.value("advertise_routes"))
	}
	if ev := r.m.actions()[0]; ev.Detail != "advertise_routes=192.168.5.0/24,10.0.0.0/8" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	many := make([]string, remoteMaxRoutes+1)
	for i := range many {
		many[i] = netip.PrefixFrom(netip.AddrFrom4([4]byte{10, byte(i), 0, 0}), 16).String()
	}
	for _, bad := range []string{"0.0.0.0/0", "::/0", "10.0.0.0/8,0.0.0.0/0", "10.0.0.1", "a,b", "10.0.0.0/33", "10.0.0.0/8;rm", strings.Join(many, ",")} {
		r.want(set("advertise_routes", bad), 400, "bad_request")
	}
	if r.app.value("advertise_routes") != "192.168.5.0/24,10.0.0.0/8" {
		t.Errorf("a refused list reached the app: %q", r.app.value("advertise_routes"))
	}
	// The other one-way switches stay one-way at any level.
	r.want(set("auto_reconnect", "false"), 403, "forbidden")
	r.want(set("full_control", "true"), 403, "forbidden")

	// Given up from afar, and at once.
	if a := set("full_control", "false"); a.status != 200 {
		t.Fatalf("giving full control up: %d %s", a.status, a.raw)
	}
	if st = r.call(rtPhone, "GET", "/v1/status", owner, nil); st.str("level") != "basic" {
		t.Errorf("level after giving it up: %q", st.str("level"))
	}
	for _, rt := range rtFullRoutes {
		r.want(r.call(rtPhone, rt.method, rt.path, owner, rt.body), 403, "needs_full")
	}
	r.want(set("taildrive_enabled", "true"), 403, "needs_full")
	r.want(set("full_control", "true"), 403, "forbidden")
	if st := r.m.state(); st.Rev == 0 {
		t.Error("the state did not move")
	}
}

// A manager of the first build knows three switches and two facts. It draws
// whatever else it is sent under the bare key, with a switch that works — so
// it is not sent more; this build's own client says that it knows more.
func TestRemoteOlderManager(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	keys := func(a rtReply) string {
		t.Helper()
		if a.status != 200 {
			t.Fatalf("settings: %d %s", a.status, a.raw)
		}
		var out []string
		for _, raw := range a.obj["settings"].([]any) {
			out = append(out, raw.(map[string]any)["key"].(string))
		}
		return strings.Join(out, ",")
	}
	old := r.send(rtPhone, "GET", "/v1/settings", owner, nil, func(q *http.Request) { q.Header.Del(remoteClientHeader) })
	if got := keys(old); got != "accept_dns,accept_routes,exit_node_lan_access,device_class,app_version" {
		t.Errorf("an older manager is shown %s", got)
	}
	// And in the first build's words: no mark it would not know what to do with.
	for _, word := range []string{"guard", "only", "needs"} {
		if strings.Contains(old.raw, `"`+word+`"`) {
			t.Errorf("an older manager is sent %q: %s", word, old.raw)
		}
	}
	// What it does know works as it did: a change that is not guarded, an
	// exit node, the reconnect — none of them waiting for a confirmation it
	// would never send.
	plain := func(q *http.Request) { q.Header.Del(remoteClientHeader) }
	for _, rq := range []struct {
		path string
		body any
	}{
		{"/v1/settings", map[string]string{"key": "accept_routes", "value": "true"}},
		{"/v1/exit-node", map[string]string{"id": rtExitA.id}},
		{"/v1/actions", map[string]string{"action": "reconnect"}},
	} {
		if a := r.send(rtPhone, "POST", rq.path, owner, rq.body, plain); a.status != 200 || a.raw != "{}\n" {
			t.Errorf("%s from an older manager: %d %q", rq.path, a.status, a.raw)
		}
	}
	r.clock.advance(5 * time.Minute)
	r.m.guardTick()
	if r.app.value("accept_routes") != "true" || r.app.value(RemoteKeyExitNode) != rtExitA.id || len(r.reverted()) != 0 {
		t.Errorf("an older manager's change was put back: %+v", r.reverted())
	}
	// Its status is the first build's with more in it, which it does not read.
	if a := r.send(rtPhone, "GET", "/v1/status", owner, nil, plain); a.status != 200 || a.obj["running"] != true || a.obj["you"].(map[string]any)["class"] != "owner" {
		t.Errorf("status for an older manager: %d %s", a.status, a.raw)
	}

	// This build's client says what it is, and is shown everything.
	if got := keys(r.call(rtPhone, "GET", "/v1/settings", owner, nil)); !strings.Contains(got, "tun_mode") || !strings.Contains(got, "full_control") {
		t.Errorf("this build's manager is shown %s", got)
	}
	phone := newRtPhoneSide(t, r, rtPhone)
	a := phone.env.do(rtSelf.id, "GET", "/v1/settings", owner, "", remoteRequestTimeout)
	if a.Status != 200 || !strings.Contains(a.Body, `"tun_mode"`) || !strings.Contains(a.Body, `"guard":"always"`) {
		t.Errorf("through the real client: %d %s", a.Status, a.Body)
	}
}

// With guests switched off on the device, who cannot be an owner is nobody —
// to pair, and with a pairing it already has.
func TestRemoteNoGuests(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	if !r.m.state().Policy.AllowGuests {
		t.Fatal("guests are not allowed by default")
	}
	r.m.setAccess(false, true)
	if r.m.state().Policy.AllowGuests {
		t.Error("the state still says guests are allowed")
	}
	r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	r.want(r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]string{"id": rtExitA.id}), 401, "unauthorized")
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": r.code()}), 403, "not_allowed")
	r.want(r.call(rtTagged, "POST", "/v1/pair", "", map[string]string{"code": r.code()}), 403, "not_allowed")
	if left := r.m.state().Pairing.AttemptsLeft; left != r.m.policy.CodeAttempts {
		t.Errorf("a guest that may not pair spent a guess: %d left", left)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", owner, nil); a.status != 200 {
		t.Errorf("an owner with guests off: %d %s", a.status, a.raw)
	}
	if len(r.app.writes()) != 0 {
		t.Errorf("the app was asked: %q", r.app.writes())
	}
	// The pairing is refused, not removed: guests let in again, it works.
	r.m.setAccess(false, false)
	if a := r.call(rtFriend, "GET", "/v1/status", guest, nil); a.status != 200 {
		t.Errorf("the guest after guests were let in again: %d %s", a.status, a.raw)
	}
}

// What the device's own page is told about who may do what comes from the
// lists the endpoint enforces.
func TestRemoteAccess(t *testing.T) {
	r := newRemoteRig(t)
	v := r.m.access()
	if v.Level != "basic" || !v.Guests {
		t.Errorf("access: %+v", v)
	}
	if got := strings.Join(v.Guest.Ops, ","); got != "status,exit_nodes,exit_node.set,settings,files,unpair,confirm,identify" {
		t.Errorf("a guest's operations: %s", got)
	}
	if got := strings.Join(v.Full.Ops, ","); got != "shares.add,storage.list,storage.send,diag.report,diag.log" {
		t.Errorf("what full control adds: %s", got)
	}
	for _, op := range v.Full.Ops {
		if rtHas(v.Owner.Ops, op) || rtHas(v.Guest.Ops, op) {
			t.Errorf("%s is listed at the basic level", op)
		}
	}
	if len(v.Owner.Ops)+len(v.Full.Ops) != len(remoteOps) {
		t.Errorf("an owner's operations: %v + %v", v.Owner.Ops, v.Full.Ops)
	}
	settings := map[string]remoteAccessSetting{}
	for _, s := range v.Owner.Settings {
		settings[s.Key] = s
	}
	if s := settings["tun_mode"]; s.Guard != "always" || s.Only != "" {
		t.Errorf("tun_mode: %+v", s)
	}
	if s := settings["taildrive_enabled"]; s.Only != "false" || s.Needs != "full" {
		t.Errorf("taildrive_enabled: %+v", s)
	}
	if s := settings["auto_reconnect"]; s.Only != "true" || s.Needs != "" {
		t.Errorf("auto_reconnect: %+v", s)
	}
	if _, listed := settings["device_class"]; listed || len(settings) != 11 {
		t.Errorf("settings: %v", settings)
	}
	var fullKeys []string
	for _, s := range v.Full.Settings {
		fullKeys = append(fullKeys, s.Key)
	}
	if got := strings.Join(fullKeys, ","); got != "taildrive_enabled,taildrive_proxy_enabled,advertise_exit_node,advertise_routes" {
		t.Errorf("settings full control opens: %s", got)
	}
	if strings.Join(v.Owner.Actions, ",") != strings.Join(remoteActions, ",") ||
		strings.Join(v.Owner.Permissions, ",") != "storage,notifications,battery,alarms,install,autostart" ||
		strings.Join(v.Never, ",") != strings.Join(remoteNever, ",") || len(v.Guest.Settings)+len(v.Guest.Actions)+len(v.Guest.Permissions) != 0 {
		t.Errorf("access: %+v", v)
	}

	r.m.setAccess(true, true)
	v = r.m.access()
	if v.Level != "full" || v.Guests || len(v.Full.Ops) != 0 || len(v.Full.Settings) != 0 || len(v.Owner.Ops) != len(remoteOps) {
		t.Errorf("access at the full level: %+v", v)
	}
	for _, s := range v.Owner.Settings {
		if s.Needs != "" || (s.Key == "taildrive_enabled" && s.Only != "") {
			t.Errorf("%+v at the full level", s)
		}
	}
	// A knob of the policy moves the page with it.
	r = newRemoteRig(t, func(p *remotePolicy) {
		p.Rights[remoteOpSetExitNode] = remoteOwner
		p.Full[remoteOpAskPermission] = true
		p.AllowGuests = false
	})
	v = r.m.access()
	if rtHas(v.Guest.Ops, remoteOpSetExitNode) || rtHas(v.Owner.Ops, remoteOpAskPermission) || !rtHas(v.Full.Ops, remoteOpAskPermission) || v.Guests {
		t.Errorf("access under another policy: %+v", v)
	}
	// And the exported form is that document.
	var doc remoteAccessView
	if err := json.Unmarshal([]byte(RemoteAccessJSON()), &doc); err != nil || doc.Level != "basic" || len(doc.Never) == 0 {
		t.Errorf("RemoteAccessJSON: %v %+v", err, doc)
	}
}

// What the device is, and what it offers the tailnet to route.
func TestRemoteDeviceFacts(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtFriend) // a guest is told as well
	expiry := time.Date(2027, 1, 10, 0, 0, 0, 0, time.UTC)
	lan, ten := netip.MustParsePrefix("192.168.5.0/24"), netip.MustParsePrefix("10.0.0.0/8")
	r.d.set(func(d *remoteFakeDaemon) {
		d.keyExpiry = expiry
		d.advertised = []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0"), lan, ten}
		d.approved = []netip.Prefix{lan}
	})
	r.forgetSelf()
	a := r.call(rtFriend, "GET", "/v1/status", token, nil)
	dev, _ := a.obj["device"].(map[string]any)
	if a.status != 200 || dev == nil {
		t.Fatalf("status: %d %s", a.status, a.raw)
	}
	for k, want := range map[string]any{
		"model": "Xiaomi MiTV-MOOQ1", "android": "11", "sdk": float64(30), "build": "0e629420", "mode": "tun", "engine": "hev",
		// The daemon's, not the 5 the app put there.
		"keyExpiry": float64(expiry.Unix()),
	} {
		if dev[k] != want {
			t.Errorf("device.%s = %v, want %v", k, dev[k], want)
		}
	}
	if strings.Contains(a.raw, "serial") || strings.Contains(a.raw, "R58M") || strings.Contains(a.raw, "controlplane") {
		t.Errorf("the status passes on what the bridge does not list: %s", a.raw)
	}
	app := a.obj["app"].(map[string]any)
	if _, inside := app["device"]; inside {
		t.Errorf("the device's facts are told twice: %s", a.raw)
	}
	if rc := app["receive"].(map[string]any); rc["free"] != float64(51234567890) || rc["waiting"] != float64(2) {
		t.Errorf("receive: %v", rc)
	}
	adv := a.obj["advertise"].(map[string]any)
	if exit := adv["exitNode"].(map[string]any); exit["on"] != true || exit["approved"] != false {
		t.Errorf("exit node offered: %v", exit)
	}
	routes := adv["routes"].([]any)
	if len(routes) != 2 || routes[0].(map[string]any)["cidr"] != "192.168.5.0/24" || routes[0].(map[string]any)["approved"] != true ||
		routes[1].(map[string]any)["cidr"] != "10.0.0.0/8" || routes[1].(map[string]any)["approved"] != false {
		t.Errorf("routes offered: %v", routes)
	}
	// Approved by the tailnet's admin.
	r.d.set(func(d *remoteFakeDaemon) { d.approved = append(d.approved, netip.MustParsePrefix("0.0.0.0/0")) })
	r.forgetSelf()
	a = r.call(rtFriend, "GET", "/v1/status", token, nil)
	if exit := a.obj["advertise"].(map[string]any)["exitNode"].(map[string]any); exit["approved"] != true {
		t.Errorf("exit node approved: %v", exit)
	}

	// The app says nothing of the device, or nonsense: the daemon's part is
	// still there, the rest is empty rather than made up.
	r.app.set(func(a *remoteFakeApp) {
		a.report = `{"device":{"model":"X","android":"14","sdk":34000,"mode":"vpn","engine":"hev"},"receive":{"state":"inbox","free":-9}}`
	})
	r.m.forgetReport()
	a = r.call(rtFriend, "GET", "/v1/status", token, nil)
	dev = a.obj["device"].(map[string]any)
	if dev["model"] != "X" || dev["sdk"] != float64(0) || dev["mode"] != "" || dev["engine"] != "" || dev["keyExpiry"] != float64(expiry.Unix()) {
		t.Errorf("device from a careless app: %v", dev)
	}
	if rc := a.obj["app"].(map[string]any)["receive"].(map[string]any); rc["free"] != float64(-1) {
		t.Errorf("free: %v", rc)
	}
	r.m.setDelegate(nil)
	a = r.call(rtFriend, "GET", "/v1/status", token, nil)
	if dev = a.obj["device"].(map[string]any); dev["keyExpiry"] != float64(expiry.Unix()) || dev["model"] != "" || a.obj["app"] != nil {
		t.Errorf("device without the app: %s", a.raw)
	}
	// A key that does not expire, and a daemon that keeps its prefs to itself.
	r.d.set(func(d *remoteFakeDaemon) { d.keyExpiry = time.Time{} })
	r.forgetSelf()
	a = r.call(rtFriend, "GET", "/v1/status", token, nil)
	if dev = a.obj["device"].(map[string]any); dev["keyExpiry"] != float64(0) {
		t.Errorf("a key that does not expire: %v", dev)
	}
}

// The relays are dialed again by the bridge itself.
func TestRemoteRelays(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	calls, why := 0, ""
	r.m.relays = func() string { calls++; return why }
	action := map[string]string{"action": "reconnect_relays"}
	r.want(r.call(rtFriend, "POST", "/v1/actions", guest, action), 403, "forbidden")
	if a := r.call(rtPhone, "POST", "/v1/actions", owner, action); a.status != 200 || calls != 1 {
		t.Errorf("reconnect_relays: %d %s, %d calls", a.status, a.raw, calls)
	}
	if ev := r.m.actions()[0]; ev.Op != "action" || ev.Detail != "reconnect_relays" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	why = "tailscaled is not running"
	a := r.call(rtPhone, "POST", "/v1/actions", owner, action)
	r.want(a, 409, "refused")
	if a.str("message") != why || r.m.actions()[0].OK {
		t.Errorf("a failure: %s, logged %+v", a.raw, r.m.actions()[0])
	}
	if w := r.app.writes(); len(w) != 0 {
		t.Errorf("the app was asked: %q", w)
	}
}

// "Managed from <name>": anyone paired may make the device say it, a few
// times, and it says the tailnet's name for the caller.
func TestRemoteIdentify(t *testing.T) {
	r := newRemoteRig(t)
	guest := r.pair(rtFriend)
	for _, result := range []string{"shown", "notified", "unseen"} {
		r.app.set(func(a *remoteFakeApp) { a.identified = result })
		a := r.call(rtFriend, "POST", "/v1/identify", guest, map[string]string{})
		if a.status != 200 || a.str("result") != result {
			t.Fatalf("identify, the app said %s: %d %s", result, a.status, a.raw)
		}
		if ev := r.m.actions()[0]; ev.Op != "identify" || ev.Detail != result || !ev.OK || ev.Class != "guest" {
			t.Errorf("logged: %+v", ev)
		}
	}
	var asked string
	r.app.set(func(a *remoteFakeApp) { asked = a.asked })
	if asked != `{"from":"`+rtFriend.name+`"}` {
		t.Errorf("what the app was asked: %s", asked)
	}
	// With no body at all, as a client that has nothing to say sends it.
	if a := r.call(rtFriend, "POST", "/v1/identify", guest, nil); a.status != 200 {
		t.Errorf("identify without a body: %d %s", a.status, a.raw)
	}
	// The name is not the caller's to choose.
	r.want(r.call(rtFriend, "POST", "/v1/identify", guest, map[string]string{"from": "the-owner"}), 400, "bad_request")
	r.app.set(func(a *remoteFakeApp) { a.identified = "ok" })
	if a := r.call(rtFriend, "POST", "/v1/identify", guest, nil); a.status != 502 {
		t.Errorf("an answer that is not one: %d %s", a.status, a.raw)
	}
	r.app.set(func(a *remoteFakeApp) { a.identified = "shown" })
	r.app.set(func(a *remoteFakeApp) { a.fail = errors.New("no") })
	r.want(r.call(rtFriend, "POST", "/v1/identify", guest, nil), 409, "refused")
	r.app.set(func(a *remoteFakeApp) { a.fail = nil })

	// It shares the budget of what puts something on the screen.
	r.m.asks = newRemoteLimiter(0.001, 1)
	if a := r.call(rtFriend, "POST", "/v1/identify", guest, nil); a.status != 200 {
		t.Fatalf("identify: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtFriend, "POST", "/v1/identify", guest, nil), 429, "slow_down")
}

// A change a paired device makes is said on the device's screen as well as
// logged; a refusal, a look and what is on the screen by itself are not.
func TestRemoteNotice(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	dir := t.TempDir()
	r.m.taildropDir = func() string { return dir }
	write := func(name string) {
		t.Helper()
		if err := writeFile(dir, name); err != nil {
			t.Fatal(err)
		}
	}
	write("a.txt")

	if a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": "accept_dns", "value": "false"}); a.status != 200 {
		t.Fatalf("setting: %d %s", a.status, a.raw)
	}
	got := r.app.noticed(t, 1)
	if len(got) != 1 || got[0] != `{"from":"pixel","op":"setting","detail":"accept_dns=false"}` {
		t.Fatalf("notices: %q", got)
	}
	r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]string{"id": rtExitA.id})
	r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": "reconnect"})
	r.call(rtPhone, "POST", "/v1/files/delete", owner, map[string]string{"name": "a.txt"})
	got = r.app.noticed(t, 4)
	var ops []string
	for _, n := range got {
		var v struct{ Op, Detail string }
		if err := json.Unmarshal([]byte(n), &v); err != nil {
			t.Fatal(err)
		}
		ops = append(ops, v.Op+" "+v.Detail)
	}
	if len(ops) != 4 || !rtHas(ops, "exit_node home-server") || !rtHas(ops, "action reconnect") || !rtHas(ops, "file_deleted a.txt") {
		t.Errorf("notices: %q", ops)
	}

	// Not said: looking, a request that shows itself, what was refused.
	r.call(rtPhone, "GET", "/v1/status", owner, nil)
	r.call(rtPhone, "GET", "/v1/settings", owner, nil)
	r.call(rtPhone, "POST", "/v1/permissions/request", owner, map[string]string{"permission": "storage"})
	r.call(rtPhone, "POST", "/v1/identify", owner, nil)
	r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": "auto_reconnect", "value": "false"})
	r.app.set(func(a *remoteFakeApp) { a.fail = errors.New("no") })
	r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": "accept_dns", "value": "true"})
	r.app.set(func(a *remoteFakeApp) { a.fail = nil })
	time.Sleep(50 * time.Millisecond)
	if got = r.app.noticed(t, 4); len(got) != 4 {
		t.Errorf("more was said than was changed: %q", got[4:])
	}
}
