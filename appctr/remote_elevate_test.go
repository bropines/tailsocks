package appctr

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// rtOwnerOps is every operation but asking for full rights: what an owner may
// use at the full level. An owner has nothing to ask for.
func rtOwnerOps() []string {
	var out []string
	for _, op := range remoteOps {
		if op != remoteOpElevateAsk {
			out = append(out, op)
		}
	}
	return out
}

// you is the caller's part of its status.
func (r *remoteRig) you(n *rtNode, token string) map[string]any {
	r.t.Helper()
	a := r.call(n, "GET", "/v1/status", token, nil)
	if a.status != 200 {
		r.t.Fatalf("status as %s: %d %s", n.name, a.status, a.raw)
	}
	return a.obj["you"].(map[string]any)
}

// elevatedLeft is what the caller's full rights have left by its status; -1
// when it has none.
func (r *remoteRig) elevatedLeft(n *rtNode, token string) int64 {
	r.t.Helper()
	you := r.you(n, token)
	el, ok := you["elevated"].(map[string]any)
	if !ok {
		if you["class"] != "guest" {
			r.t.Errorf("no full rights, and the class is %v", you["class"])
		}
		return -1
	}
	if you["class"] != "owner" {
		r.t.Errorf("full rights, and the class is %v", you["class"])
	}
	return int64(el["left"].(float64))
}

// toldElevations waits for the app to have been told of n lists of full
// rights in force and returns them.
func (a *remoteFakeApp) toldElevations(t *testing.T, n int) []string {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		a.mu.Lock()
		got := append([]string(nil), a.elevations...)
		a.mu.Unlock()
		if len(got) >= n || time.Now().After(deadline) {
			return got
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// lastEvent is the newest line of the action log with op.
func (r *remoteRig) lastEvent(op string) *remoteEvent {
	for _, ev := range r.m.actions() {
		if ev.Op == op {
			return &ev
		}
	}
	return nil
}

func newElevateRig(t *testing.T) *remoteRig {
	r := newRemoteRig(t)
	r.m.elevateAsks = newRemoteLimiter(1e6, 1e6)
	r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestElevateAsk] = "shown" })
	return r
}

// Given at the device, full rights make a guest an owner: every owner's
// operation, nothing of the full tier the device has not opened, and the log
// and the app know of it.
func TestRemoteElevateGrant(t *testing.T) {
	r := newElevateRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	routes := func(n *rtNode, token string) rtReply {
		return r.call(n, "POST", "/v1/settings", token, map[string]string{"key": "accept_routes", "value": "true"})
	}
	r.want(routes(rtFriend, guest), 403, "forbidden")
	if !rtHas(rtStrings(r.you(rtFriend, guest)["can"]), remoteOpElevateAsk) {
		t.Error("a guest is not offered to ask for full rights")
	}

	got := r.m.elevate(rtFriend.id, 900, false)
	if !got.OK || got.Left != 900 || got.Seconds != 900 || got.Full || got.Until != r.clock.Now().Unix()+900 {
		t.Fatalf("elevate: %+v", got)
	}
	if left := r.elevatedLeft(rtFriend, guest); left != 900 {
		t.Errorf("left = %d", left)
	}
	can := rtStrings(r.you(rtFriend, guest)["can"])
	for _, op := range []string{"settings.set", "action", "files.delete", "permission.request", "folders", "shares.remove"} {
		if !rtHas(can, op) {
			t.Errorf("an elevated guest cannot %s: %v", op, can)
		}
	}
	if rtHas(can, remoteOpElevateAsk) || rtHas(can, remoteOpDiagLog) {
		t.Errorf("an elevated guest is offered %v", can)
	}
	if a := routes(rtFriend, guest); a.status != 200 {
		t.Errorf("an owner's change by an elevated guest: %d %s", a.status, a.raw)
	}
	// The full tier is still the device's switch's.
	r.want(r.call(rtFriend, "GET", "/v1/diag/log", guest, nil), 403, "needs_full")

	if ev := r.lastEvent("setting"); ev == nil || ev.Class != "owner" || ev.NodeID != rtFriend.id || !ev.OK {
		t.Errorf("the change: %+v", ev)
	}
	if ev := r.lastEvent("elevated"); ev == nil || ev.Detail != "900" || ev.Class != "guest" || ev.Name != rtFriend.name || !ev.OK {
		t.Errorf("the grant in the log: %+v", ev)
	}
	for _, p := range r.m.paired() {
		switch p.NodeID {
		case rtFriend.id:
			if p.Elevated == nil || p.Elevated.Left != 900 || p.Elevated.Seconds != 900 || p.Elevated.Until == 0 {
				t.Errorf("the guest's row: %+v", p.Elevated)
			}
		case rtPhone.id:
			if p.Elevated != nil {
				t.Errorf("the owner's row: %+v", p.Elevated)
			}
		}
	}
	if told := r.app.toldElevations(t, 1); len(told) == 0 || !strings.Contains(told[len(told)-1], `"nodeId":"`+rtFriend.id+`"`) {
		t.Errorf("the app was told %q", told)
	}

	// An owner has nothing to be raised to, and is left as it was.
	if a := r.m.elevate(rtPhone.id, 900, false); a.Error != "not_guest" {
		t.Errorf("an owner's pairing: %+v", a)
	}
	you := r.you(rtPhone, owner)
	if you["class"] != "owner" || you["elevated"] != nil || rtHas(rtStrings(you["can"]), remoteOpElevateAsk) {
		t.Errorf("the owner: %v", you)
	}
	for _, tc := range []struct {
		node    string
		seconds int64
		want    string
	}{{rtFriend.id, 1234, "bad_request"}, {rtFriend.id, -1, "bad_request"}, {"nNOBODY", 900, "not_paired"}} {
		if a := r.m.elevate(tc.node, tc.seconds, false); a.OK || a.Error != tc.want {
			t.Errorf("elevate %s for %d: %+v", tc.node, tc.seconds, a)
		}
	}
	// The exported form is that answer.
	var a remoteElevateAnswer
	if err := json.Unmarshal([]byte(RemoteElevate("nNOBODY", 900, false)), &a); err != nil || a.OK {
		t.Errorf("RemoteElevate: %v %+v", err, a)
	}
}

// The full tier can be part of the grant: for that guest and that time, and the
// device's switch does not move.
func TestRemoteElevateFullTier(t *testing.T) {
	r := newElevateRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	if a := r.m.elevate(rtFriend.id, 3600, true); !a.OK || !a.Full {
		t.Fatalf("elevate: %+v", a)
	}
	st := r.call(rtFriend, "GET", "/v1/status", guest, nil)
	if st.str("level") != "full" || !rtHas(rtStrings(st.obj["you"].(map[string]any)["can"]), remoteOpDiagLog) {
		t.Errorf("status: %s", st.raw)
	}
	if a := r.call(rtFriend, "GET", "/v1/diag/log", guest, nil); a.status != 200 {
		t.Errorf("the scrubbed log: %d %s", a.status, a.raw)
	}
	if ev := r.lastEvent("elevated"); ev == nil || ev.Detail != "3600: full" {
		t.Errorf("logged: %+v", ev)
	}
	// Nobody else got it.
	if r.m.fullControl() {
		t.Error("the device's switch went on")
	}
	r.want(r.call(rtPhone, "GET", "/v1/diag/log", owner, nil), 403, "needs_full")
	if st := r.call(rtPhone, "GET", "/v1/status", owner, nil); st.str("level") != "basic" {
		t.Errorf("the owner's level: %s", st.str("level"))
	}
}

// What no owner may do from afar, no elevated guest may either.
func TestRemoteElevateNeverList(t *testing.T) {
	r := newElevateRig(t)
	guest := r.pair(rtFriend)
	r.m.elevate(rtFriend.id, 3600, true)
	before := len(r.app.writes())
	r.want(r.call(rtFriend, "POST", "/v1/settings", guest, map[string]string{"key": "full_control", "value": "true"}), 403, "forbidden")
	for _, key := range append(rtNever, "login_server", "auth_key", "root_mode", "control_proxy", "bypass") {
		r.want(r.call(rtFriend, "POST", "/v1/settings", guest, map[string]string{"key": key, "value": "true"}), 403, "forbidden")
		r.want(r.call(rtFriend, "POST", "/v1/actions", guest, map[string]string{"action": key}), 403, "forbidden")
	}
	r.want(r.call(rtFriend, "POST", "/v1/permissions/request", guest, map[string]string{"permission": "vpn"}), 403, "forbidden")
	// Nor give rights, its own or anyone's: no route leads there.
	for _, rt := range remoteRoutes {
		if strings.Contains(rt.path, "elevate") && rt.path != "/v1/elevate/request" {
			t.Errorf("route %s", rt.path)
		}
	}
	r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 0}), 409, "refused")
	if w := r.app.writes(); len(w) != before {
		t.Errorf("the app was asked: %q", w[before:])
	}
}

// They end with their time, taken back, with the switch, with guests shut out
// and with the pairing; each but the last is a line of the log, and the app is
// told every time.
func TestRemoteElevateEnds(t *testing.T) {
	ended := func(r *remoteRig, why string) {
		t.Helper()
		if ev := r.lastEvent("elevation_ended"); ev == nil || ev.Detail != why || ev.NodeID != rtFriend.id || ev.Class != "guest" {
			t.Errorf("the end in the log: %+v, want %s", ev, why)
		}
	}
	toldNone := func(r *remoteRig) {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		for {
			told := r.app.toldElevations(t, 2)
			if len(told) >= 2 && told[len(told)-1] == `{"elevations":[]}` {
				return
			}
			if time.Now().After(deadline) {
				t.Errorf("the app was told %q", told)
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}

	t.Run("time", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 900, false)
		r.clock.advance(899 * time.Second)
		if left := r.elevatedLeft(rtFriend, guest); left != 1 {
			t.Errorf("left = %d", left)
		}
		r.clock.advance(time.Second)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("after its time: left = %d", left)
		}
		r.want(r.call(rtFriend, "POST", "/v1/settings", guest, map[string]string{"key": "accept_routes", "value": "true"}), 403, "forbidden")
		ended(r, "time")
		toldNone(r)
		if r.m.store.find(rtFriend.id).Elevation != nil {
			t.Error("still in the store")
		}
	})

	t.Run("by the timer, with nobody asking", func(t *testing.T) {
		r := newElevateRig(t)
		r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 900, false)
		r.clock.advance(901 * time.Second)
		r.m.elevationTick()
		ended(r, "time")
	})

	t.Run("taken back", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 28800, true)
		if a := r.m.elevateEnd(rtFriend.id); !a.OK {
			t.Fatalf("take back: %+v", a)
		}
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("left = %d", left)
		}
		ended(r, "taken_back")
		toldNone(r)
		if a := r.m.elevateEnd(rtFriend.id); a.Error != "not_elevated" {
			t.Errorf("again: %+v", a)
		}
		var a remoteElevateAnswer
		if err := json.Unmarshal([]byte(RemoteElevateEnd(rtFriend.id)), &a); err != nil || a.OK {
			t.Errorf("RemoteElevateEnd: %v %+v", err, a)
		}
	})

	t.Run("the switch off", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.m.setElevation(false)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("left = %d", left)
		}
		ended(r, "switch_off")
		toldNone(r)
		if a := r.m.elevate(rtFriend.id, 3600, false); a.Error != "off" {
			t.Errorf("given with the switch off: %+v", a)
		}
		if rtHas(rtStrings(r.you(rtFriend, guest)["can"]), remoteOpElevateAsk) {
			t.Error("a guest is offered to ask with the switch off")
		}
		r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 900}), 403, "forbidden")
		if v := r.m.access(); v.Elevate || rtHas(v.Guest.Ops, remoteOpElevateAsk) {
			t.Errorf("access: %+v", v)
		}
		// On again, nothing comes back by itself.
		r.m.setElevation(true)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("after the switch was on again: left = %d", left)
		}
		if v := r.m.access(); !v.Elevate || !rtHas(v.Guest.Ops, remoteOpElevateAsk) || rtHas(v.Owner.Ops, remoteOpElevateAsk) {
			t.Errorf("access: %+v", v)
		}
		// The exported switch is the same one.
		RemoteAllowElevation(true)
	})

	t.Run("guests shut out", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.m.setAccess(false, true)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
		ended(r, "guests_off")
		if a := r.m.elevate(rtFriend.id, 3600, false); a.Error != "no_guests" {
			t.Errorf("given with guests shut out: %+v", a)
		}
		r.m.setAccess(false, false)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("guests let in again: left = %d", left)
		}
	})

	t.Run("the pairing revoked", func(t *testing.T) {
		r := newElevateRig(t)
		r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.m.revoke(rtFriend.id)
		toldNone(r)
	})

	t.Run("the guest unpairs itself", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		if a := r.call(rtFriend, "POST", "/v1/unpair", guest, nil); a.status != 200 {
			t.Fatalf("unpair: %d %s", a.status, a.raw)
		}
		toldNone(r)
	})

	t.Run("paired again", func(t *testing.T) {
		r := newElevateRig(t)
		r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		guest := r.pair(rtFriend)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("a new pairing: left = %d", left)
		}
		toldNone(r)
	})

	t.Run("the pairing runs out", func(t *testing.T) {
		r := newElevateRig(t)
		r.pair(rtFriend)
		r.clock.advance(3 * time.Hour)
		// Until the pairing ends: what it has left.
		if a := r.m.elevate(rtFriend.id, 0, false); !a.OK || a.Seconds != 21*3600 {
			t.Fatalf("until the pairing ends: %+v", a)
		}
		// And a length the pairing does not have is cut to it.
		if a := r.m.elevate(rtFriend.id, 28800, false); !a.OK || a.Seconds != 28800 {
			t.Fatalf("eight hours: %+v", a)
		}
		r.clock.advance(14 * time.Hour)
		if a := r.m.elevate(rtFriend.id, 28800, false); !a.OK || a.Seconds != 7*3600 {
			t.Fatalf("eight hours with seven left: %+v", a)
		}
		r.clock.advance(7 * time.Hour)
		r.m.state()
		if ops := r.ops(); len(ops) < 2 || ops[0] != "expired" || ops[1] != "elevation_ended" {
			t.Errorf("the log: %v", ops)
		}
		toldNone(r)
	})
}

// Kept with the pairing: an app or daemon restarted in the same boot keeps them
// to the second.
func TestRemoteElevateSurvivesRestart(t *testing.T) {
	r := newElevateRig(t)
	guest := r.pair(rtFriend)
	r.m.elevate(rtFriend.id, 3600, true)
	r.clock.advance(600 * time.Second)
	r.restart()
	r.m.elevateAsks = newRemoteLimiter(1e6, 1e6)
	if left := r.elevatedLeft(rtFriend, guest); left != 3000 {
		t.Errorf("after a restart: left = %d", left)
	}
	if a := r.call(rtFriend, "GET", "/v1/diag/log", guest, nil); a.status != 200 {
		t.Errorf("the full tier after a restart: %d", a.status)
	}
	// What the store says is the answer, read back from disk.
	raw, err := os.ReadFile(r.store)
	if err != nil || !strings.Contains(string(raw), `"elevation"`) || !strings.Contains(string(raw), `"seconds": 3600`) {
		t.Errorf("the store: %v %s", err, raw)
	}
}

// The clock. Within a boot the boot-time clock decides and the wall clock can
// only end them sooner; across a reboot they never get back more than they had
// at the last write, nor more than their length.
func TestRemoteElevateClock(t *testing.T) {
	t.Run("set back within a boot", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 900, false)
		r.clock.jump(-48 * time.Hour)
		if left := r.elevatedLeft(rtFriend, guest); left != 900 {
			t.Errorf("left = %d", left)
		}
		r.clock.advance(900 * time.Second)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("after its length: left = %d", left)
		}
	})

	t.Run("set forward ends them", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.clock.jump(30 * time.Minute)
		if left := r.elevatedLeft(rtFriend, guest); left != 1800 {
			t.Errorf("the wall clock half an hour on: left = %d", left)
		}
		r.clock.jump(2 * time.Hour)
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("the wall clock past the end: left = %d", left)
		}
	})

	t.Run("given while the clock was ahead", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.clock.jump(48 * time.Hour)
		r.m.elevate(rtFriend.id, 900, false)
		r.clock.jump(-48 * time.Hour)
		r.clock.reboot(0)
		r.restart()
		if left := r.elevatedLeft(rtFriend, guest); left != 900 {
			t.Errorf("left = %d: never more than the length", left)
		}
	})

	t.Run("a reboot with the clock behind", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.clock.advance(20 * time.Minute)
		// In use: the store is written again, and with it what is left.
		if left := r.elevatedLeft(rtFriend, guest); left != 2400 {
			t.Fatalf("left = %d", left)
		}
		r.clock.reboot(0)
		r.clock.jump(-24 * time.Hour)
		r.restart()
		if left := r.elevatedLeft(rtFriend, guest); left != 2400 {
			t.Errorf("left = %d, want what the last write had", left)
		}
	})

	t.Run("a reboot with the clock right", func(t *testing.T) {
		r := newElevateRig(t)
		guest := r.pair(rtFriend)
		r.m.elevate(rtFriend.id, 3600, false)
		r.clock.advance(20 * time.Minute)
		r.elevatedLeft(rtFriend, guest)
		r.clock.reboot(10 * time.Minute)
		r.restart()
		if left := r.elevatedLeft(rtFriend, guest); left != 1800 {
			t.Errorf("left = %d: the time off counts", left)
		}
		r.clock.reboot(time.Hour)
		r.restart()
		if left := r.elevatedLeft(rtFriend, guest); left != -1 {
			t.Errorf("off for longer than was left: left = %d", left)
		}
	})
}

// A guest asks; the person at the device is asked; nothing is given by that.
func TestRemoteElevateRequest(t *testing.T) {
	r := newElevateRig(t)
	owner, guest := r.pair(rtPhone), r.pair(rtFriend)
	r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestElevateAsk] = "notified" })
	a := r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 3600})
	if a.status != 200 || a.str("result") != "notified" {
		t.Fatalf("ask: %d %s", a.status, a.raw)
	}
	want := `request elevate_ask {"from":"` + rtFriend.name + `","nodeId":"` + rtFriend.id + `","seconds":3600}`
	if w := r.app.writes(); len(w) == 0 || w[len(w)-1] != want {
		t.Errorf("the app was asked %q", w)
	}
	if ev := r.m.actions()[0]; ev.Op != "elevate_asked" || ev.Detail != "3600: notified" || ev.Class != "guest" || !ev.OK {
		t.Errorf("logged: %+v", ev)
	}
	if left := r.elevatedLeft(rtFriend, guest); left != -1 {
		t.Error("asking gave something")
	}
	// Until the pairing ends is asked as 0; nothing else is a length.
	if a := r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 0}); a.status != 200 {
		t.Errorf("until the pairing ends: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 7200}), 400, "bad_request")
	r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]any{"seconds": 900, "full": true}), 400, "bad_request")
	// The name shown is the tailnet's, not the caller's.
	r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]any{"seconds": 900, "from": "the-owner"}), 400, "bad_request")
	// An owner has nothing to ask for.
	r.want(r.call(rtPhone, "POST", "/v1/elevate/request", owner, map[string]int{"seconds": 900}), 409, "refused")
	// An answer that is not one.
	r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestElevateAsk] = "granted" })
	if a := r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 900}); a.status != 502 {
		t.Errorf("the app answered something else: %d %s", a.status, a.raw)
	}
	r.app.set(func(a *remoteFakeApp) { a.answers[remoteRequestElevateAsk] = "shown" })

	// Twice in a row, then once a minute.
	r.m.elevateAsks = newRemoteLimiter(remoteElevateAskRate, remoteElevateAskBurst)
	for i := 0; i < remoteElevateAskBurst; i++ {
		if a := r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 900}); a.status != 200 {
			t.Fatalf("ask %d: %d %s", i, a.status, a.raw)
		}
	}
	r.want(r.call(rtFriend, "POST", "/v1/elevate/request", guest, map[string]int{"seconds": 900}), 429, "slow_down")
	// A budget of its own: identify, which shares the permission pages', is not spent.
	if a := r.call(rtFriend, "POST", "/v1/identify", guest, nil); a.status != 200 {
		t.Errorf("identify after the requests: %d %s", a.status, a.raw)
	}
}

// A tagged device has no owner: there, full rights are the one way a guest
// gets an owner's — and they are allowed.
func TestRemoteElevateTagged(t *testing.T) {
	r := newElevateRig(t)
	r.d.change(rtSelf, func(n *rtNode) { n.tags = []string{"tag:tv"} })
	r.forgetSelf()
	phone := r.pair(rtPhone)
	if you := r.you(rtPhone, phone); you["class"] != "guest" {
		t.Fatalf("this account's phone on a tagged device: %v", you["class"])
	}
	if a := r.m.elevate(rtPhone.id, 3600, false); !a.OK {
		t.Fatalf("elevate: %+v", a)
	}
	if left := r.elevatedLeft(rtPhone, phone); left != 3600 {
		t.Errorf("left = %d", left)
	}
	if a := r.call(rtPhone, "POST", "/v1/settings", phone, map[string]string{"key": "accept_dns", "value": "false"}); a.status != 200 {
		t.Errorf("an owner's change on a tagged device: %d %s", a.status, a.raw)
	}
}

// The store keeps full rights a guest's, for a day at most.
func TestRemoteElevateStore(t *testing.T) {
	hash := remoteHashToken("t")
	e := func(seconds int64) *remoteElevation {
		return &remoteElevation{Granted: 1, Seconds: seconds, Boot: "boot-0", BootDeadline: 4000, Left: seconds}
	}
	d := remoteStoreData{Version: remoteStoreVersion, Self: "nSELF", Pairings: []remotePairing{
		{NodeID: "nOWNER", Class: "owner", TokenSHA256: hash, Elevation: e(900)},
		{NodeID: "nLONG", Class: "guest", TokenSHA256: hash, Elevation: e(int64(remoteElevateMax/time.Second) + 1)},
		{NodeID: "nNONE", Class: "guest", TokenSHA256: hash, Elevation: e(0)},
		{NodeID: "nGOOD", Class: "guest", TokenSHA256: hash, Elevation: e(900)},
	}}
	raw, _ := json.Marshal(d)
	path := filepath.Join(t.TempDir(), "remote", "p.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	s := openRemoteStore(path, newRemoteFakeClock())
	for _, p := range s.data.Pairings {
		if (p.Elevation != nil) != (p.NodeID == "nGOOD") {
			t.Errorf("%s: %+v", p.NodeID, p.Elevation)
		}
	}
}

// A grant that cannot be written is not given.
func TestRemoteElevateNotSaved(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root writes anywhere")
	}
	r := newElevateRig(t)
	guest := r.pair(rtFriend)
	dir := filepath.Dir(r.store)
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(dir, 0o700) })
	if a := r.m.elevate(rtFriend.id, 900, false); a.OK || a.Error != "not_saved" {
		t.Errorf("elevate: %+v", a)
	}
	if r.hasOp("elevated") {
		t.Error("the log says it was given")
	}
	os.Chmod(dir, 0o700)
	if left := r.elevatedLeft(rtFriend, guest); left != -1 {
		t.Errorf("left = %d", left)
	}
}
