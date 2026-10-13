package appctr

// What the app says of itself in a status answer, the request for a
// permission's page, the switches that go one way, and the exit node the
// device's own daemon would pick.

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// reportCalls counts how often the app was asked for its report.
func (a *remoteFakeApp) reportCalls() int {
	n := 0
	for _, c := range a.called() {
		if c == "get "+remoteKeyReport {
			n++
		}
	}
	return n
}

func rtPermissions(t *testing.T, app map[string]any) (ids []string, state map[string]string, ask map[string]bool) {
	t.Helper()
	state, ask = map[string]string{}, map[string]bool{}
	for _, raw := range app["permissions"].([]any) {
		p := raw.(map[string]any)
		id := p["id"].(string)
		ids = append(ids, id)
		state[id], ask[id] = p["state"].(string), p["ask"].(bool)
	}
	return ids, state, ask
}

// A status answer carries the app's report, cut down to what the bridge lists
// and to what the caller may know.
func TestRemoteReport(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)

	a := r.call(rtPhone, "GET", "/v1/status", owner, nil)
	app, _ := a.obj["app"].(map[string]any)
	if a.status != 200 || app == nil {
		t.Fatalf("status: %d %s", a.status, a.raw)
	}
	ids, state, ask := rtPermissions(t, app)
	// The bridge's list and order; a permission it does not list is not passed
	// on, and of two entries for one permission the first is the one.
	if got := strings.Join(ids, ","); got != strings.Join(remotePermissions, ",") {
		t.Errorf("permissions: %s", got)
	}
	for id, want := range map[string]string{
		"storage": "missing", "notifications": "granted", "battery": "na", "vpn": "missing",
		"alarms": "granted", "install": "missing", "autostart": "unknown",
	} {
		if state[id] != want {
			t.Errorf("%s is %q, want %q", id, state[id], want)
		}
	}
	// Askable: missing or unknown, the app has a page for it, and it is on the
	// list. Never what is granted or absent, and never the VPN consent.
	for id, want := range map[string]bool{
		"storage": true, "install": true, "notifications": false, "battery": false,
		"vpn": false, "alarms": false, "autostart": false,
	} {
		if ask[id] != want {
			t.Errorf("ask %s = %v, want %v", id, ask[id], want)
		}
	}
	receive := app["receive"].(map[string]any)
	if receive["state"] != "needs_access" || receive["folder"] != "Movies/From phone" || receive["reset"] != true {
		t.Errorf("receive: %v", receive)
	}
	saved := app["saved"].([]any)
	if f := saved[0].(map[string]any); len(saved) != 1 || f["name"] != "holiday.mp4" || f["size"] != float64(812647424) || f["folder"] != "Download/Taildrop" {
		t.Errorf("saved: %v", saved)
	}
	folders := app["folders"].([]any)
	if f := folders[0].(map[string]any); len(folders) != 1 || f["name"] != "Downloads" || f["path"] != "/storage/emulated/0/Download" {
		t.Errorf("folders: %v", folders)
	}
	// Counted here, not taken from the app.
	if app["folderCount"] != float64(1) {
		t.Errorf("folderCount: %v", app["folderCount"])
	}
	// A field the bridge does not know stays on the device.
	for _, word := range []string{"logTail", "auth-key", "tskey", "camera"} {
		if strings.Contains(a.raw, word) {
			t.Errorf("the status passes %q on: %s", word, a.raw)
		}
	}

	// A guest sees what is held and what is missing, and where files go. It is
	// offered nothing to ask for or reset, and is given no path.
	g := r.call(rtFriend, "GET", "/v1/status", guest, nil)
	gapp, _ := g.obj["app"].(map[string]any)
	if g.status != 200 || gapp == nil {
		t.Fatalf("guest status: %d %s", g.status, g.raw)
	}
	_, gstate, gask := rtPermissions(t, gapp)
	if gstate["storage"] != "missing" || gstate["notifications"] != "granted" {
		t.Errorf("guest sees states %v", gstate)
	}
	for id, v := range gask {
		if v {
			t.Errorf("a guest is offered to ask for %s", id)
		}
	}
	if rc := gapp["receive"].(map[string]any); rc["reset"] != false || rc["state"] != "needs_access" {
		t.Errorf("guest receive: %v", rc)
	}
	if _, has := gapp["folders"]; has || gapp["folderCount"] != float64(1) || strings.Contains(g.raw, "/storage/") {
		t.Errorf("a guest is given the shared folders: %s", g.raw)
	}
	// And what the guest was shown did not change what the owner is.
	a = r.call(rtPhone, "GET", "/v1/status", owner, nil)
	if _, _, ask := rtPermissions(t, a.obj["app"].(map[string]any)); !ask["storage"] || !strings.Contains(a.raw, "/storage/emulated/0/Download") {
		t.Errorf("the owner's view after a guest looked: %s", a.raw)
	}
}

// The report is asked for once in a while, not with every poll, and again
// right after something was changed.
func TestRemoteReportIsReused(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	for i := 0; i < 4; i++ {
		if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] == nil {
			t.Fatalf("status: %d %s", a.status, a.raw)
		}
	}
	if n := r.app.reportCalls(); n != 1 {
		t.Errorf("four polls asked the app %d times", n)
	}
	r.app.set(func(a *remoteFakeApp) {
		a.report = `{"permissions":[{"id":"storage","state":"granted"}],"receive":{"state":"ready","folder":"Download/Taildrop"}}`
	})
	// Still the one from before: nothing was changed through this endpoint.
	a := r.call(rtPhone, "GET", "/v1/status", token, nil)
	if _, state, _ := rtPermissions(t, a.obj["app"].(map[string]any)); state["storage"] != "missing" {
		t.Errorf("a reused report: %s", a.raw)
	}
	for _, change := range []func() rtReply{
		func() rtReply {
			return r.call(rtPhone, "POST", "/v1/settings", token, map[string]string{"key": "accept_dns", "value": "false"})
		},
		func() rtReply {
			return r.call(rtPhone, "POST", "/v1/actions", token, map[string]string{"action": "receive_folder_default"})
		},
		func() rtReply {
			return r.call(rtPhone, "POST", "/v1/permissions/request", token, map[string]string{"permission": "storage"})
		},
	} {
		before := r.app.reportCalls()
		if c := change(); c.status != 200 {
			t.Fatalf("change: %d %s", c.status, c.raw)
		}
		a = r.call(rtPhone, "GET", "/v1/status", token, nil)
		if n := r.app.reportCalls(); n != before+1 {
			t.Errorf("after a change the app was asked %d times", n-before)
		}
	}
	app := a.obj["app"].(map[string]any)
	if _, state, ask := rtPermissions(t, app); state["storage"] != "granted" || ask["storage"] || len(app["saved"].([]any)) != 0 {
		t.Errorf("the report after the change: %s", a.raw)
	}
	if rc := app["receive"].(map[string]any); rc["state"] != "ready" || rc["reset"] != false {
		t.Errorf("receive: %v", rc)
	}
	// A new registration starts from nothing.
	r.m.setDelegate(r.app)
	before := r.app.reportCalls()
	r.call(rtPhone, "GET", "/v1/status", token, nil)
	if n := r.app.reportCalls(); n != before+1 {
		t.Errorf("after the app registered again it was asked %d times", n-before)
	}
}

// Whatever the app hands over is read as a report or not at all.
func TestRemoteReportIsChecked(t *testing.T) {
	for _, bad := range []string{"", "not json", `["storage"]`, `"report"`, `{"permissions":"all"}`, strings.Repeat(" ", remoteMaxReport+1)} {
		if rep := remoteParseReport(bad); rep != nil {
			t.Errorf("%.20q read as %+v", bad, rep)
		}
	}
	// An empty object is a report with nothing in it, said as such.
	rep := remoteParseReport(`{}`)
	if rep == nil || len(rep.Permissions) != 0 || rep.Receive.State != "unknown" || rep.Saved == nil || rep.FolderCount != 0 || rep.Asked != nil {
		t.Fatalf("empty report: %+v", rep)
	}
	long := strings.Repeat("n", 400)
	rep = remoteParseReport(`{
	 "permissions":[{"id":"storage","state":"allowed","ask":true},{"id":"alarms","state":"na","ask":true},{"id":"vpn","state":"unknown","ask":true}],
	 "asked":{"id":"storage","result":"notified","age":-5},
	 "receive":{"state":"ready","folder":"` + long + `","reset":true},
	 "saved":[{"name":"","size":1},{"name":"a\nb.txt","size":-4,"time":-1,"folder":"x\u0000y"}],
	 "folders":[{"name":"","path":"/x"},{"name":"a","path":""},{"name":"` + long + `","path":"` + long + `"}]}`)
	if rep == nil {
		t.Fatal("not read")
	}
	// A state that is not one of the four is "unknown" — and then may be asked for.
	if p := rep.Permissions[0]; p.ID != "storage" || p.State != "unknown" || !p.Ask {
		t.Errorf("storage: %+v", p)
	}
	for _, p := range rep.Permissions[1:] {
		if p.Ask {
			t.Errorf("%s can be asked for: %+v", p.ID, p)
		}
	}
	if rep.Asked == nil || rep.Asked.Age != 0 || rep.Asked.Result != "notified" {
		t.Errorf("asked: %+v", rep.Asked)
	}
	// A folder that works is not offered for a reset, whatever the app says.
	if rep.Receive.Reset || len([]rune(rep.Receive.Folder)) != 120 {
		t.Errorf("receive: reset=%v, folder of %d", rep.Receive.Reset, len([]rune(rep.Receive.Folder)))
	}
	if len(rep.Saved) != 1 || rep.Saved[0].Name != "ab.txt" || rep.Saved[0].Size != 0 || rep.Saved[0].Time != 0 || rep.Saved[0].Folder != "xy" {
		t.Errorf("saved: %+v", rep.Saved)
	}
	if len(rep.Folders) != 1 || len(rep.Folders[0].Name) != 64 || len(rep.Folders[0].Path) != 255 || rep.FolderCount != 1 {
		t.Errorf("folders: %d, count %d", len(rep.Folders), rep.FolderCount)
	}
	// A request that is over, or for something that cannot be asked for, is no request.
	for _, asked := range []string{`{"id":"storage","result":"shown"}`, `{"id":"vpn","result":"waiting"}`, `{"id":"storage","result":"granted"}`, `{"id":"camera","result":"waiting"}`} {
		if rep := remoteParseReport(`{"asked":` + asked + `}`); rep == nil || rep.Asked != nil {
			t.Errorf("asked %s was kept: %+v", asked, rep)
		}
	}
	// The lists are bounded.
	many := `{"saved":[` + strings.TrimSuffix(strings.Repeat(`{"name":"f"},`, 40), ",") + `],"folders":[` +
		strings.TrimSuffix(strings.Repeat(`{"name":"s","path":"/p"},`, 80), ",") + `]}`
	if rep := remoteParseReport(many); rep == nil || len(rep.Saved) != remoteMaxSaved || len(rep.Folders) != remoteMaxFolders {
		t.Errorf("unbounded lists: %+v", rep)
	}

	// On the wire: an app whose report is not one, or that fails, leaves the
	// status without that part and otherwise whole.
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	for _, bad := range []string{"not json", ""} {
		r.app.set(func(a *remoteFakeApp) { a.report = bad })
		r.m.forgetReport()
		if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] != nil || a.obj["running"] != true {
			t.Errorf("status with the report %q: %d %s", bad, a.status, a.raw)
		}
	}
	r.app.set(func(a *remoteFakeApp) { a.report, a.fail = rtReport, errors.New("no") })
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] != nil {
		t.Errorf("status while the app fails: %d %s", a.status, a.raw)
	}
	r.app.set(func(a *remoteFakeApp) { a.fail = nil })
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] == nil {
		t.Errorf("status once the app answers: %d %s", a.status, a.raw)
	}
	r.m.setDelegate(nil)
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] != nil {
		t.Errorf("status without the app: %d %s", a.status, a.raw)
	}
}

// A hung app costs a status answer a moment, not the answer.
func TestRemoteReportDoesNotHoldTheStatus(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	r.m.reportWait, r.m.reportAnswer = 50*time.Millisecond, 100*time.Millisecond
	r.m.delegateWait = 100 * time.Millisecond
	release := r.app.hang()
	began := time.Now()
	// The first call into the app is made and does not come back; the next
	// ones find the app busy.
	for i := 0; i < 3; i++ {
		if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] != nil || a.obj["running"] != true {
			t.Errorf("status %d with the app hung: %d %s", i, a.status, a.raw)
		}
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("three polls took %v", took)
	}
	if n := r.app.reportCalls(); n != 1 {
		t.Errorf("the hung app was asked %d times", n)
	}
	// While that call is out, a change is not started behind it.
	r.want(r.call(rtPhone, "POST", "/v1/permissions/request", token, map[string]string{"permission": "storage"}), 503, "unavailable")
	release()
	r.idle()
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 || a.obj["app"] == nil {
		t.Errorf("status after the app came back: %d %s", a.status, a.raw)
	}
}

// Asking for a permission's page: owners only, listed permissions only, a
// few at a time; the answer is what the device did, and it is in the log.
func TestRemoteAskPermission(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	ask := func(n *rtNode, token, permission string) rtReply {
		t.Helper()
		return r.call(n, "POST", "/v1/permissions/request", token, map[string]string{"permission": permission})
	}

	for _, result := range []string{"shown", "notified", "waiting"} {
		r.app.set(func(a *remoteFakeApp) { a.askResult = result })
		a := ask(rtPhone, owner, "storage")
		if a.status != 200 || a.str("result") != result || a.str("permission") != "storage" {
			t.Fatalf("asked, the app said %s: %d %s", result, a.status, a.raw)
		}
		// Never a word that could be read as a grant.
		if strings.Contains(a.raw, "grant") {
			t.Errorf("the answer speaks of a grant: %s", a.raw)
		}
		if ev := r.m.actions()[0]; ev.Op != "permission" || ev.Detail != "storage: "+result || !ev.OK || ev.Name != rtPhone.name || ev.Class != "owner" {
			t.Errorf("logged: %+v", ev)
		}
	}
	// The app was told which permission and who asks — the tailnet's name for
	// the caller, nothing the caller sent.
	var got string
	r.app.set(func(a *remoteFakeApp) { got = a.asked })
	if got != `{"permission":"storage","from":"`+rtPhone.name+`"}` {
		t.Errorf("what the app was asked: %s", got)
	}
	a := r.call(rtPhone, "POST", "/v1/permissions/request", owner, map[string]string{"permission": "install", "from": "the-owner"})
	r.want(a, 400, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/permissions/request", owner, `{"permission":["storage"]}`), 400, "bad_request")

	// Not on the list — the VPN consent among them: the app is not asked.
	writes := len(r.app.writes())
	for _, id := range []string{"vpn", "camera", "", "STORAGE", "storage ", "storage,install", "all", "*", remoteKeyAsk, "accept_dns"} {
		r.want(ask(rtPhone, owner, id), 403, "forbidden")
	}
	// A guest may look at what is missing and may not ask for it.
	r.want(ask(rtFriend, guest, "storage"), 403, "forbidden")
	if len(r.app.writes()) != writes {
		t.Errorf("a refused request reached the app: %q", r.app.writes()[writes:])
	}
	if !r.hasOp("denied") {
		t.Errorf("the refusals left no trace: %q", r.ops())
	}
	// Neither is it a setting or an action by another door.
	r.want(r.call(rtPhone, "POST", "/v1/settings", owner, map[string]string{"key": remoteKeyAsk, "value": "storage"}), 403, "forbidden")
	r.want(r.call(rtPhone, "POST", "/v1/actions", owner, map[string]string{"action": remoteKeyAsk}), 403, "forbidden")

	// The app says no, in its own words: held already, or no page for it here.
	r.app.set(func(a *remoteFakeApp) { a.fail = errors.New("already granted") })
	a = ask(rtPhone, owner, "notifications")
	r.want(a, 409, "refused")
	if a.str("message") != "already granted" {
		t.Errorf("message: %q", a.str("message"))
	}
	if ev := r.m.actions()[0]; ev.Op != "permission" || ev.Detail != "notifications" || ev.OK {
		t.Errorf("a refused request is logged as %+v", ev)
	}
	r.app.set(func(a *remoteFakeApp) { a.fail = nil })

	// An answer that is not one of the three: the page may be up, nobody knows.
	r.app.set(func(a *remoteFakeApp) { a.askResult = "granted" })
	a = ask(rtPhone, owner, "alarms")
	if a.status != 502 || a.str("error") != "failed" || strings.Contains(a.raw, `"result"`) {
		t.Errorf("an unknown answer: %d %s", a.status, a.raw)
	}
	if ev := r.m.actions()[0]; ev.OK || !strings.HasPrefix(ev.Detail, "alarms") {
		t.Errorf("logged: %+v", ev)
	}
	r.app.set(func(a *remoteFakeApp) { a.askResult = "shown" })

	// No app to ask.
	r.m.setDelegate(nil)
	r.want(ask(rtPhone, owner, "storage"), 503, "unavailable")
	if ev := r.m.actions()[0]; ev.OK || ev.Detail != "storage (not asked of the app)" {
		t.Errorf("logged: %+v", ev)
	}
	r.m.setDelegate(r.app)

	// The app does not answer: it may still show the page, and the log says
	// what came of it.
	r.m.delegateWait, r.m.delegateAnswer = 100*time.Millisecond, 100*time.Millisecond
	release := r.app.hang()
	r.want(ask(rtPhone, owner, "battery"), 504, "pending")
	release()
	r.idle()
	deadline := time.Now().Add(3 * time.Second)
	for !strings.Contains(r.m.actions()[0].Detail, "late") && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if ev := r.m.actions()[0]; !ev.OK || ev.Detail != "battery (applied late)" {
		t.Errorf("the late outcome is logged as %+v", ev)
	}
}

// A paired device cannot keep putting pages in front of a person.
func TestRemoteAskPermissionRate(t *testing.T) {
	r := newRemoteRig(t)
	r.m.asks = newRemoteLimiter(0.001, 2)
	owner, tablet := r.pair(rtPhone), r.pair(rtTablet)
	body := map[string]string{"permission": "storage"}
	for i := 0; i < 2; i++ {
		if a := r.call(rtPhone, "POST", "/v1/permissions/request", owner, body); a.status != 200 {
			t.Fatalf("request %d: %d %s", i, a.status, a.raw)
		}
	}
	asked := len(r.app.writes())
	r.want(r.call(rtPhone, "POST", "/v1/permissions/request", owner, body), 429, "slow_down")
	if len(r.app.writes()) != asked {
		t.Error("a request over the limit reached the app")
	}
	// Another device has a budget of its own.
	if a := r.call(rtTablet, "POST", "/v1/permissions/request", tablet, body); a.status != 200 {
		t.Errorf("another device: %d %s", a.status, a.raw)
	}
	// The defaults: three in a row, then one in twenty seconds.
	l := newRemoteManager(remoteDefaultPolicy(), newRemoteFakeClock(), newRemoteFakeDaemon()).asks
	for i := 0; i < 3; i++ {
		if !l.allow("n") {
			t.Fatalf("request %d refused inside the burst", i)
		}
	}
	if l.allow("n") {
		t.Error("a fourth request in a row")
	}
	l.buckets["n"].at = time.Now().Add(-21 * time.Second)
	if !l.allow("n") || l.allow("n") {
		t.Error("twenty seconds did not buy exactly one request")
	}
}

// The switches that go one way, and the second action.
func TestRemoteOneWay(t *testing.T) {
	r := newRemoteRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	set := func(n *rtNode, token, key, value string) rtReply {
		t.Helper()
		return r.call(n, "POST", "/v1/settings", token, map[string]string{"key": key, "value": value})
	}
	// Everyone reads which way a switch goes.
	a := r.call(rtFriend, "GET", "/v1/settings", guest, nil)
	only := map[string]string{}
	for _, raw := range a.obj["settings"].([]any) {
		s := raw.(map[string]any)
		only[s["key"].(string)], _ = s["only"].(string)
	}
	if only["auto_reconnect"] != "true" || only["taildrive_enabled"] != "false" || only["taildrive_proxy_enabled"] != "false" || only["accept_dns"] != "" {
		t.Errorf("only: %v", only)
	}

	// Sharing folders and the port other apps read shares through are closed
	// from afar and never opened; the device's own recovery is switched on
	// and never off.
	writes := len(r.app.writes())
	for key, refused := range map[string]string{"taildrive_enabled": "true", "taildrive_proxy_enabled": "true", "auto_reconnect": "false"} {
		a := set(rtPhone, owner, key, refused)
		r.want(a, 403, "forbidden")
		if !strings.Contains(a.str("message"), "only at the device") {
			t.Errorf("%s=%s: %s", key, refused, a.raw)
		}
		// And a guest, neither way.
		r.want(set(rtFriend, guest, key, refused), 403, "forbidden")
		r.want(set(rtFriend, guest, key, map[string]string{"true": "false", "false": "true"}[refused]), 403, "forbidden")
	}
	if len(r.app.writes()) != writes {
		t.Fatalf("a refused change reached the app: %q", r.app.writes()[writes:])
	}
	if r.app.value("taildrive_enabled") != "true" || r.app.value("auto_reconnect") != "false" {
		t.Error("a refused change was applied")
	}
	found := false
	for _, ev := range r.m.actions() {
		found = found || (ev.Op == "denied" && strings.HasPrefix(ev.Detail, "only at the device: "))
	}
	if !found {
		t.Errorf("the refusal is not in the log: %+v", r.m.actions())
	}
	for key, value := range map[string]string{"taildrive_enabled": "false", "taildrive_proxy_enabled": "false", "auto_reconnect": "true"} {
		if a := set(rtPhone, owner, key, value); a.status != 200 || r.app.value(key) != value {
			t.Errorf("%s=%s: %d %s", key, value, a.status, a.raw)
		}
		if ev := r.m.actions()[0]; ev.Op != "setting" || ev.Detail != key+"="+value || !ev.OK {
			t.Errorf("logged: %+v", ev)
		}
	}

	// The receive folder goes back to the default when the app agrees that the
	// chosen one does not work; its refusal is passed on.
	action := map[string]string{"action": "receive_folder_default"}
	r.want(r.call(rtFriend, "POST", "/v1/actions", guest, action), 403, "forbidden")
	if a := r.call(rtPhone, "POST", "/v1/actions", owner, action); a.status != 200 {
		t.Errorf("reset: %d %s", a.status, a.raw)
	}
	r.app.set(func(a *remoteFakeApp) { a.fail = errors.New("the folder works; change it on the device") })
	a = r.call(rtPhone, "POST", "/v1/actions", owner, action)
	r.want(a, 409, "refused")
	if a.str("message") != "the folder works; change it on the device" {
		t.Errorf("message: %q", a.str("message"))
	}
	if ev := r.m.actions()[0]; ev.Op != "action" || ev.Detail != "receive_folder_default" || ev.OK {
		t.Errorf("logged: %+v", ev)
	}
}

// The exit node the device's own daemon would pick is marked in the list; it
// is chosen like any other, by its id.
func TestRemoteExitNodeSuggestion(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtFriend)
	suggested := func() []string {
		t.Helper()
		a := r.call(rtFriend, "GET", "/v1/exit-nodes", token, nil)
		nodes, _ := a.obj["nodes"].([]any)
		if a.status != 200 || len(nodes) != 2 {
			t.Fatalf("exit nodes: %d %s", a.status, a.raw)
		}
		var out []string
		for _, raw := range nodes {
			if n := raw.(map[string]any); n["suggested"] == true {
				out = append(out, n["id"].(string))
			}
		}
		return out
	}
	// No opinion: an empty id, as with no node the tailnet recommends.
	if got := suggested(); len(got) != 0 {
		t.Errorf("suggested with none named: %v", got)
	}
	r.d.set(func(d *remoteFakeDaemon) { d.suggest = rtExitA.id })
	if got := suggested(); len(got) != 1 || got[0] != rtExitA.id {
		t.Errorf("suggested: %v", got)
	}
	// The mark is on the answer, not on the list kept for naming the exit
	// node: the next answer, with another opinion, carries only the new one.
	r.d.set(func(d *remoteFakeDaemon) { d.suggest = "" })
	if got := suggested(); len(got) != 0 {
		t.Errorf("an earlier suggestion stuck: %v", got)
	}
	// Not one of this device's exit nodes, offline, or no answer at all.
	for _, id := range []string{rtPhone.id, rtExitB.id, "nNOSUCH", "../x", strings.Repeat("n", 65)} {
		r.d.set(func(d *remoteFakeDaemon) { d.suggest = id })
		if got := suggested(); len(got) != 0 {
			t.Errorf("%q suggested: %v", id, got)
		}
	}
	r.d.set(func(d *remoteFakeDaemon) { d.suggest, d.noSuggest = rtExitA.id, true })
	if got := suggested(); len(got) != 0 {
		t.Errorf("suggested while the daemon cannot say: %v", got)
	}
	// "The best one" is still not something to send: the phone sends an id.
	r.d.set(func(d *remoteFakeDaemon) { d.noSuggest = false })
	r.want(r.call(rtFriend, "POST", "/v1/exit-node", token, map[string]string{"id": "best"}), 404, "not_found")
	if a := r.call(rtFriend, "POST", "/v1/exit-node", token, map[string]string{"id": rtExitA.id}); a.status != 200 {
		t.Errorf("choosing the suggested node: %d %s", a.status, a.raw)
	}
	r.d.change(rtExitA, func(n *rtNode) { n.exitNode = true })
	if st := r.call(rtFriend, "GET", "/v1/status", token, nil); st.status != 200 || strings.Contains(st.raw, "suggested") {
		t.Errorf("status: %d %s", st.status, st.raw)
	}
}
