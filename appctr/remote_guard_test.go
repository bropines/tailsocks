package appctr

// Guarded changes: kept when confirmed over the channel in time, put back by
// the device itself when not.

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// rtGuard reads the guard of an answer.
func rtGuard(t *testing.T, a rtReply) (id, key string, seconds, after float64) {
	t.Helper()
	g, _ := a.obj["guard"].(map[string]any)
	if g == nil {
		t.Fatalf("no guard in %d %s", a.status, a.raw)
	}
	id, _ = g["id"].(string)
	key, _ = g["key"].(string)
	seconds, _ = g["seconds"].(float64)
	if left, ok := g["left"].(float64); ok {
		seconds = left
	}
	after, _ = g["after"].(float64)
	if id == "" {
		t.Fatalf("a guard without an id: %s", a.raw)
	}
	return id, key, seconds, after
}

func (r *remoteRig) reverted() []remoteEvent {
	var out []remoteEvent
	for _, ev := range r.m.actions() {
		if ev.Op == "reverted" {
			out = append(out, ev)
		}
	}
	return out
}

// Confirmed: after the change has settled, by the device that made it.
func TestRemoteGuardConfirm(t *testing.T) {
	r := newRemoteRig(t)
	// A guest may pick the exit node, and so may guard and confirm that.
	guest, tablet := r.pair(rtFriend), r.pair(rtTablet)
	confirm := func(n *rtNode, token, id string) rtReply {
		t.Helper()
		return r.call(n, "POST", "/v1/confirm", token, map[string]string{"id": id})
	}

	a := r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]any{"id": rtExitA.id, "guard": true})
	if a.status != 200 {
		t.Fatalf("guarded exit node: %d %s", a.status, a.raw)
	}
	id, key, seconds, after := rtGuard(t, a)
	if key != RemoteKeyExitNode || seconds != 60 || after != 5 || r.app.value(RemoteKeyExitNode) != rtExitA.id {
		t.Fatalf("guard: %s; the app has %q", a.raw, r.app.value(RemoteKeyExitNode))
	}
	// The caller's status carries it, another device's does not.
	st := r.call(rtFriend, "GET", "/v1/status", guest, nil)
	if sid, skey, left, safter := rtGuard(t, st); sid != id || skey != key || left != 60 || safter != 5 {
		t.Errorf("status.guard: %s", st.raw)
	}
	if st := r.call(rtTablet, "GET", "/v1/status", tablet, nil); st.obj["guard"] != nil {
		t.Errorf("another device is shown the guard: %s", st.raw)
	}

	// Too early: the change has not had time to cut anything yet.
	c := confirm(rtFriend, guest, id)
	if c.status != 409 || c.str("error") != "too_early" || c.num("after") != 5 {
		t.Errorf("confirming at once: %d %s", c.status, c.raw)
	}
	r.clock.advance(3 * time.Second)
	if c = confirm(rtFriend, guest, id); c.status != 409 || c.num("after") != 2 {
		t.Errorf("confirming after 3 s: %d %s", c.status, c.raw)
	}
	r.clock.advance(3 * time.Second)
	// Not this id, and not another device with the right one.
	r.want(confirm(rtFriend, guest, "gnotthisone"), 404, "not_found")
	r.want(confirm(rtTablet, tablet, id), 404, "not_found")
	r.want(r.call(rtFriend, "POST", "/v1/confirm", guest, map[string]string{"id": ""}), 400, "bad_request")
	r.want(r.call(rtFriend, "POST", "/v1/confirm", guest, map[string]string{"id": id, "key": "exit_node"}), 400, "bad_request")
	if st := r.call(rtFriend, "GET", "/v1/status", guest, nil); st.obj["guard"] == nil {
		t.Fatal("a wrong confirmation ended the wait")
	}

	if c = confirm(rtFriend, guest, id); c.status != 200 {
		t.Fatalf("confirm: %d %s", c.status, c.raw)
	}
	if ev := r.m.actions()[0]; ev.Op != "confirmed" || ev.Detail != RemoteKeyExitNode || !ev.OK || ev.Name != rtFriend.name || ev.Class != "guest" {
		t.Errorf("logged: %+v", ev)
	}
	if st := r.call(rtFriend, "GET", "/v1/status", guest, nil); st.obj["guard"] != nil {
		t.Errorf("status after the confirmation: %s", st.raw)
	}
	// Once: there is nothing left to confirm, and nothing is put back later.
	r.want(confirm(rtFriend, guest, id), 404, "not_found")
	r.clock.advance(5 * time.Minute)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != rtExitA.id || len(r.reverted()) != 0 {
		t.Errorf("a confirmed change was put back: the app has %q, log %+v", r.app.value(RemoteKeyExitNode), r.reverted())
	}

	// A change that is not guarded answers as it always did: an older manager
	// neither asks for a guard nor knows to confirm one.
	if a := r.call(rtFriend, "POST", "/v1/exit-node", guest, map[string]string{"id": ""}); a.status != 200 || a.raw != "{}\n" {
		t.Errorf("an unguarded change: %d %q", a.status, a.raw)
	}
	r.clock.advance(5 * time.Minute)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != "" {
		t.Error("an unguarded change was put back")
	}
}

// Not confirmed: the device puts the old value back by itself, and says so.
func TestRemoteGuardRevert(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]any{"key": "accept_routes", "value": "true", "guard": true})
	if a.status != 200 || r.app.value("accept_routes") != "true" {
		t.Fatalf("guarded setting: %d %s", a.status, a.raw)
	}
	id, key, _, _ := rtGuard(t, a)
	if key != "accept_routes" {
		t.Errorf("guard: %s", a.raw)
	}
	// Not before its time, whoever looks.
	r.clock.advance(59 * time.Second)
	r.m.guardTick()
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); r.app.value("accept_routes") != "true" || st.obj["guard"] == nil {
		t.Fatalf("put back early: %s", st.raw)
	}
	// Setting the wall clock is not time passing.
	r.clock.jump(time.Hour)
	r.m.guardTick()
	if r.app.value("accept_routes") != "true" {
		t.Fatal("a jump of the wall clock put the change back")
	}
	r.clock.advance(2 * time.Second)
	r.m.guardTick()
	if r.app.value("accept_routes") != "false" {
		t.Fatal("the change was not put back")
	}
	rev := r.reverted()
	if len(rev) != 1 || rev[0].Detail != "accept_routes: not confirmed" || !rev[0].OK || rev[0].Name != rtPhone.name || rev[0].Class != "owner" {
		t.Errorf("logged: %+v", rev)
	}
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.obj["guard"] != nil {
		t.Errorf("status after the revert: %s", st.raw)
	}
	// A confirmation that comes too late is told so, and changes nothing.
	c := r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": id})
	r.want(c, 404, "not_found")
	if !strings.Contains(c.str("message"), "put back") || r.app.value("accept_routes") != "false" {
		t.Errorf("a late confirmation: %s", c.raw)
	}
	// Said on the device's screen too.
	found := false
	for _, n := range r.app.noticed(t, 2) {
		found = found || strings.Contains(n, `"op":"reverted"`)
	}
	if !found {
		t.Error("the revert was not said on the device")
	}
	// Once.
	r.m.guardTick()
	if len(r.reverted()) != 1 {
		t.Errorf("reverted again: %+v", r.reverted())
	}

	// The exit node goes back to the one before…
	r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]string{"id": rtExitA.id})
	a = r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]any{"id": rtExitB.id, "guard": true})
	rtGuard(t, a)
	r.clock.advance(61 * time.Second)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != rtExitA.id {
		t.Errorf("the exit node after the revert: %q", r.app.value(RemoteKeyExitNode))
	}
	// …and to none when the one before is no longer the app's to set.
	a = r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]any{"id": rtExitB.id, "guard": true})
	rtGuard(t, a)
	r.app.set(func(a *remoteFakeApp) {
		a.refusals["set "+RemoteKeyExitNode+"="+rtExitA.id] = "not one of this device's peers"
	})
	r.clock.advance(61 * time.Second)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != "" {
		t.Errorf("the exit node when the old one is gone: %q", r.app.value(RemoteKeyExitNode))
	}
	if ev := r.reverted()[0]; !ev.OK || !strings.Contains(ev.Detail, "none") {
		t.Errorf("logged: %+v", ev)
	}
}

// One change waits at a time; the mode is guarded whatever is sent; going
// back by hand leaves nothing to put back.
func TestRemoteGuardRules(t *testing.T) {
	r := newRemoteRig(t)
	owner, tablet := r.pair(rtPhone), r.pair(rtTablet)
	set := func(n *rtNode, token, key, value string, guard bool) rtReply {
		t.Helper()
		body := map[string]any{"key": key, "value": value}
		if guard {
			body["guard"] = true
		}
		return r.call(n, "POST", "/v1/settings", token, body)
	}

	// Proxy or TUN: never without the guard.
	a := set(rtPhone, owner, "tun_mode", "true", false)
	if a.status != 200 || r.app.value("tun_mode") != "true" {
		t.Fatalf("tun_mode: %d %s", a.status, a.raw)
	}
	first, key, _, _ := rtGuard(t, a)
	if key != "tun_mode" {
		t.Errorf("guard: %s", a.raw)
	}
	// A setting that cannot cut anything is not guarded even when asked.
	if a := set(rtPhone, owner, "auto_reconnect", "true", true); a.status != 200 || a.obj["guard"] != nil {
		t.Errorf("auto_reconnect with a guard asked for: %d %s", a.status, a.raw)
	}

	// Meanwhile: no second guard, for another setting or from another device —
	// and the app does not hear of the refused one.
	writes := len(r.app.writes())
	r.want(set(rtPhone, owner, "accept_dns", "false", true), 409, "guard_pending")
	r.want(set(rtTablet, tablet, "tun_mode", "false", false), 409, "guard_pending")
	r.want(r.call(rtTablet, "POST", "/v1/exit-node", tablet, map[string]any{"id": rtExitA.id, "guard": true}), 409, "guard_pending")
	if len(r.app.writes()) != writes {
		t.Errorf("a refused change reached the app: %q", r.app.writes()[writes:])
	}
	// What is not guarded is not held back.
	if a := set(rtTablet, tablet, "accept_dns", "false", false); a.status != 200 || r.app.value("accept_dns") != "false" {
		t.Errorf("an unguarded change while one waits: %d %s", a.status, a.raw)
	}
	// Another device cannot confirm it away either.
	r.clock.advance(10 * time.Second)
	r.want(r.call(rtTablet, "POST", "/v1/confirm", tablet, map[string]string{"id": first}), 404, "not_found")

	// Back by hand before confirming: nothing waits any more, nothing is put
	// back later, and the old id confirms nothing.
	if a := set(rtPhone, owner, "tun_mode", "false", false); a.status != 200 || a.obj["guard"] != nil {
		t.Fatalf("back by hand: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": first}), 404, "not_found")
	r.clock.advance(2 * time.Minute)
	r.m.guardTick()
	if r.app.value("tun_mode") != "false" || len(r.reverted()) != 0 {
		t.Errorf("after going back by hand: the app has %q, log %+v", r.app.value("tun_mode"), r.reverted())
	}
	// And now another guarded change is taken.
	if a := set(rtTablet, tablet, "accept_dns", "true", true); a.status != 200 || a.obj["guard"] == nil {
		t.Errorf("a guarded change after the first was settled: %d %s", a.status, a.raw)
	}
	r.clock.advance(2 * time.Minute)
	r.m.guardTick()
	if r.app.value("accept_dns") != "false" {
		t.Error("the tablet's change was not put back")
	}

	// The same thing changed twice before a confirmation: what to go back to
	// is what was there before either, and only the newer id confirms.
	a = r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]any{"id": rtExitA.id, "guard": true})
	one, _, _, _ := rtGuard(t, a)
	r.clock.advance(20 * time.Second)
	a = r.call(rtPhone, "POST", "/v1/exit-node", owner, map[string]any{"id": rtExitB.id, "guard": true})
	two, _, seconds, _ := rtGuard(t, a)
	if one == two || seconds != 60 {
		t.Errorf("the second guard: %s (the first was %s)", a.raw, one)
	}
	r.clock.advance(10 * time.Second)
	r.want(r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": one}), 404, "not_found")
	// The first one's minute is over; the second one's is not.
	r.clock.advance(35 * time.Second)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != rtExitB.id {
		t.Fatal("the second change went with the first one's deadline")
	}
	r.clock.advance(20 * time.Second)
	r.m.guardTick()
	if r.app.value(RemoteKeyExitNode) != "" {
		t.Errorf("put back to %q, not to what was there before both", r.app.value(RemoteKeyExitNode))
	}

	// Another policy, other numbers: they are the answer's, not the client's.
	r = newRemoteRig(t, func(p *remotePolicy) { p.GuardTTL, p.GuardSettle = 20*time.Second, 0 })
	owner = r.pair(rtPhone)
	a = r.call(rtPhone, "POST", "/v1/settings", owner, map[string]any{"key": "accept_dns", "value": "false", "guard": true})
	id, _, seconds, after := rtGuard(t, a)
	if seconds != 20 || after != 0 {
		t.Errorf("guard under another policy: %s", a.raw)
	}
	if c := r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": id}); c.status != 200 {
		t.Errorf("confirming at once with nothing to wait for: %d %s", c.status, c.raw)
	}
}

// A change that waits is in the store: an app restarted before anyone
// confirmed finds it there and puts it back.
func TestRemoteGuardSurvivesARestart(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	a := r.call(rtPhone, "POST", "/v1/settings", owner, map[string]any{"key": "tun_mode", "value": "true"})
	id, _, _, _ := rtGuard(t, a)
	r.restart()
	// Nobody is there to confirm it: its id is another run's.
	r.want(r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": id}), 404, "not_found")
	// The app is not up yet: the revert waits for it, and no other guarded
	// change gets in front of it.
	r.m.setDelegate(nil)
	r.m.guardTick()
	if r.app.value("tun_mode") != "true" || len(r.reverted()) != 0 {
		t.Fatalf("reverted without the app: %q %+v", r.app.value("tun_mode"), r.reverted())
	}
	r.m.setDelegate(r.app)
	r.want(r.call(rtPhone, "POST", "/v1/settings", owner, map[string]any{"key": "accept_dns", "value": "false", "guard": true}), 409, "guard_pending")
	r.m.guardTick()
	if r.app.value("tun_mode") != "false" {
		t.Fatal("the change of the run before was not put back")
	}
	if rev := r.reverted(); len(rev) != 1 || rev[0].Detail != "tun_mode: not confirmed" || rev[0].Name != rtPhone.name {
		t.Errorf("logged: %+v", rev)
	}
	// Done with: a third run finds nothing.
	r.restart()
	r.m.guardTick()
	if len(r.reverted()) != 1 || r.app.value("tun_mode") != "false" {
		t.Errorf("reverted again after another restart: %+v", r.reverted())
	}
	// A confirmed change leaves nothing in the store either.
	a = r.call(rtPhone, "POST", "/v1/settings", owner, map[string]any{"key": "tun_mode", "value": "true"})
	id, _, _, _ = rtGuard(t, a)
	r.clock.advance(6 * time.Second)
	if c := r.call(rtPhone, "POST", "/v1/confirm", owner, map[string]string{"id": id}); c.status != 200 {
		t.Fatalf("confirm: %d %s", c.status, c.raw)
	}
	r.restart()
	r.m.guardTick()
	if r.app.value("tun_mode") != "true" {
		t.Error("a confirmed change was put back after a restart")
	}
}

// What the app does with a guarded change is what the answer says.
func TestRemoteGuardAppTrouble(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	guarded := map[string]any{"key": "accept_routes", "value": "true", "guard": true}
	noGuard := func() {
		t.Helper()
		if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.obj["guard"] != nil {
			t.Errorf("a guard after a change that was not made: %s", st.raw)
		}
	}

	// The app cannot say what the setting is now: nothing to go back to, so
	// nothing is changed.
	r.app.set(func(a *remoteFakeApp) { a.refusals["get accept_routes"] = "no" })
	r.want(r.call(rtPhone, "POST", "/v1/settings", owner, guarded), 503, "unavailable")
	if r.app.value("accept_routes") != "false" || len(r.app.writes()) != 0 {
		t.Errorf("changed without knowing the old value: %q", r.app.writes())
	}
	noGuard()
	r.app.set(func(a *remoteFakeApp) { delete(a.refusals, "get accept_routes") })

	// The app refuses the change: nothing waits.
	r.app.set(func(a *remoteFakeApp) {
		a.refusals["set accept_routes=true"] = "set by an extra argument on the device"
	})
	a := r.call(rtPhone, "POST", "/v1/settings", owner, guarded)
	r.want(a, 409, "refused")
	noGuard()
	r.app.set(func(a *remoteFakeApp) { delete(a.refusals, "set accept_routes=true") })

	// No app at all.
	r.m.setDelegate(nil)
	r.want(r.call(rtPhone, "POST", "/v1/settings", owner, guarded), 503, "unavailable")
	r.m.setDelegate(r.app)
	noGuard()

	// The app takes the change and does not answer: it may still be applied,
	// so it waits for a confirmation like any other — the answer says both.
	r.m.delegateWait, r.m.delegateAnswer = 100*time.Millisecond, 100*time.Millisecond
	r.app.set(func(a *remoteFakeApp) { a.blockOnly = "set " })
	release := r.app.hang()
	a = r.call(rtPhone, "POST", "/v1/settings", owner, guarded)
	r.want(a, 504, "pending")
	rtGuard(t, a)
	release()
	r.idle()
	if r.app.value("accept_routes") != "true" {
		t.Fatal("the late change was not applied")
	}
	r.clock.advance(61 * time.Second)
	r.m.guardTick()
	if r.app.value("accept_routes") != "false" || len(r.reverted()) != 1 {
		t.Errorf("a change applied late was not put back: %q %+v", r.app.value("accept_routes"), r.reverted())
	}

	// The app refuses to take the old value back: said in the log, not tried
	// for ever.
	a = r.call(rtPhone, "POST", "/v1/settings", owner, guarded)
	rtGuard(t, a)
	r.app.set(func(a *remoteFakeApp) { a.fail = errors.New("no") })
	r.clock.advance(61 * time.Second)
	r.m.guardTick()
	r.app.set(func(a *remoteFakeApp) { a.fail = nil })
	if ev := r.reverted()[0]; ev.OK || !strings.Contains(ev.Detail, "refused") {
		t.Errorf("logged: %+v", ev)
	}
	noGuard()
}
