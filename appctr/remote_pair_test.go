package appctr

import (
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRemotePairSuccess(t *testing.T) {
	r := newRemoteRig(t)
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.obj["pairing"] != false || a.obj["paired"] != false || a.obj["needsCode"] != true {
		t.Errorf("hello before: %s", a.raw)
	}
	shown := r.m.startPairing()
	if !shown.OK || shown.Left != 600 || shown.Expires != r.clock.Now().Unix()+600 {
		t.Fatalf("code on show: %+v", shown)
	}
	// The QR is the same code, with the node to send it to.
	qr := remoteParseQR(shown.QR)
	if !qr.OK || qr.Code != shown.Code || qr.NodeID != rtSelf.id || qr.Name != rtSelf.name {
		t.Errorf("QR %q parsed as %+v", shown.QR, qr)
	}
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.obj["pairing"] != true {
		t.Errorf("hello with a code on show: %s", a.raw)
	}

	a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": shown.Code})
	if a.status != 200 || len(a.str("token")) != 43 || a.str("class") != "owner" || a.str("nodeId") != rtSelf.id || a.str("name") != rtSelf.name {
		t.Fatalf("pair: %d %s", a.status, a.raw)
	}
	token := a.str("token")

	st := r.m.state()
	if st.Pairing != nil || st.Paired != 1 || st.LastPairing == nil || st.LastPairing.Result != "paired" || st.LastPairing.Name != rtPhone.name || st.LastPairing.Class != "owner" {
		t.Errorf("state after pairing: %+v, last %+v", st, st.LastPairing)
	}
	// Only the hash of the token is kept.
	p := r.m.store.find(rtPhone.id)
	if p == nil || p.TokenSHA256 != remoteHashToken(token) || p.UserID != int64(rtUserSelf) || p.Login == "" {
		t.Fatalf("stored pairing: %+v", p)
	}
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.obj["paired"] != true || a.obj["pairing"] != false || a.str("class") != "owner" {
		t.Errorf("hello after: %s", a.raw)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("status: %d %s", a.status, a.raw)
	}
	if ops := r.ops(); len(ops) != 1 || ops[0] != "paired" {
		t.Errorf("action log: %q", ops)
	}
}

func TestRemotePairWrongCode(t *testing.T) {
	r := newRemoteRig(t)
	code := r.code()
	for i := 1; i <= 2; i++ {
		a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)})
		r.want(a, 403, "wrong")
		if a.num("attemptsLeft") != float64(5-i) {
			t.Errorf("guess %d: %s", i, a.raw)
		}
	}
	st := r.m.state()
	if st.Pairing == nil || st.Pairing.AttemptsLeft != 3 || st.Pairing.LastWrong == nil || st.Pairing.LastWrong.Name != rtFriend.name || st.Pairing.LastWrong.NodeID != rtFriend.id {
		t.Fatalf("what the device shows: %+v", st.Pairing)
	}
	if len(r.m.paired()) != 0 {
		t.Fatal("a wrong code paired")
	}
	// The code is still good for whoever has it.
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("the right code after two wrong ones: %d %s", a.status, a.raw)
	}
	if ops := r.ops(); len(ops) != 3 || ops[0] != "paired" || ops[1] != "pair_failed" {
		t.Errorf("action log: %q", ops)
	}
}

// Not six digits is not a guess: refused, and the code loses nothing.
func TestRemotePairMalformedCode(t *testing.T) {
	r := newRemoteRig(t)
	code := r.code()
	for _, bad := range []string{"", "12345", "1234567", "12345a", " " + code[:5], code + "\n", "١٢٣٤٥٦", strings.Repeat("9", 600)} {
		r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": bad}), 400, "bad_request")
	}
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", `{"code":123456}`), 400, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", nil), 400, "bad_request")
	if left := r.m.state().Pairing.AttemptsLeft; left != 5 {
		t.Errorf("%d guesses left, want all 5", left)
	}
}

func TestRemotePairLockout(t *testing.T) {
	r := newRemoteRig(t)
	code := r.code()
	// Five wrong guesses, from whoever: the budget belongs to the code.
	guessers := []*rtNode{rtFriend, rtFriend, rtTagged, rtPhone, rtFriend}
	for i, n := range guessers {
		a := r.call(n, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)})
		if i < 4 {
			r.want(a, 403, "wrong")
		} else {
			r.want(a, 403, "locked")
		}
		if a.num("attemptsLeft") != float64(4-i) {
			t.Errorf("guess %d: %s", i+1, a.raw)
		}
	}
	// The code is dead, also for the device that has it right.
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}), 403, "closed")
	st := r.m.state()
	if st.Pairing != nil || st.LastPairing == nil || st.LastPairing.Result != "locked" || st.LastPairing.Name != rtFriend.name {
		t.Errorf("what the device shows: %+v / %+v", st.Pairing, st.LastPairing)
	}
	if len(r.m.paired()) != 0 {
		t.Error("someone is paired")
	}
	if ops := r.ops(); len(ops) != 5 || ops[0] != "pair_locked" {
		t.Errorf("action log: %q", ops)
	}
	// A new code is a new budget.
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": r.code()}); a.status != 200 {
		t.Errorf("a fresh code: %d %s", a.status, a.raw)
	}
}

func TestRemotePairCodeExpiry(t *testing.T) {
	r := newRemoteRig(t)
	code := r.code()
	r.clock.advance(9*time.Minute + 59*time.Second)
	if p := r.m.state().Pairing; p == nil || p.Left != 1 {
		t.Fatalf("a second before the end: %+v", p)
	}
	// The wall clock has no say: a time sync that jumps back an hour does not
	// give the code an hour.
	r.clock.jump(-time.Hour)
	r.clock.advance(time.Second)
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}), 403, "closed")
	st := r.m.state()
	if st.Pairing != nil || st.LastPairing == nil || st.LastPairing.Result != "expired" {
		t.Errorf("after the end: %+v / %+v", st.Pairing, st.LastPairing)
	}

	// Nor does one that jumps forward end it early.
	code = r.code()
	r.clock.jump(48 * time.Hour)
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("after the wall clock jumped ahead: %d %s", a.status, a.raw)
	}

	// Cancelled from the screen.
	code = r.code()
	r.m.cancelPairing()
	r.want(r.call(rtTablet, "POST", "/v1/pair", "", map[string]string{"code": code}), 403, "closed")
	if last := r.m.state().LastPairing; last == nil || last.Result != "cancelled" {
		t.Errorf("after cancelling: %+v", last)
	}
	// A new code replaces the one on show.
	old := r.code()
	code = r.code()
	if old != code {
		r.want(r.call(rtTablet, "POST", "/v1/pair", "", map[string]string{"code": old}), 403, "wrong")
	}
	if a := r.call(rtTablet, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("the replacement: %d %s", a.status, a.raw)
	}
}

// A code pairs once. Whoever sends it again is told so, and it costs the next
// code nothing.
func TestRemotePairSpentCode(t *testing.T) {
	r := newRemoteRig(t)
	code := r.code()
	first := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code})
	if first.status != 200 {
		t.Fatalf("pair: %d %s", first.status, first.raw)
	}
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}), 409, "used")
	// Also for the device that used it: the token is not handed out twice.
	again := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code})
	r.want(again, 409, "used")
	if again.str("token") != "" {
		t.Error("a token came with the refusal")
	}
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)}), 403, "closed")
	if list := r.m.paired(); len(list) != 1 || list[0].NodeID != rtPhone.id {
		t.Errorf("paired: %+v", list)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", first.str("token"), nil); a.status != 200 {
		t.Errorf("the first token: %d", a.status)
	}
	// Past its own deadline a spent code is just no code.
	r.clock.advance(11 * time.Minute)
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}), 403, "closed")
}

// Two phones, one code, the same moment: one pairing.
func TestRemotePairRace(t *testing.T) {
	for round := 0; round < 20; round++ {
		r := newRemoteRig(t)
		code := r.code()
		racers := []*rtNode{rtPhone, rtTablet, rtFriend, rtTagged}
		replies := make([]rtReply, len(racers))
		var wg sync.WaitGroup
		start := make(chan struct{})
		for i, n := range racers {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				replies[i] = r.call(n, "POST", "/v1/pair", "", map[string]string{"code": code})
			}()
		}
		close(start)
		wg.Wait()
		won := 0
		for i, a := range replies {
			switch {
			case a.status == 200 && a.str("token") != "":
				won++
				if list := r.m.paired(); len(list) != 1 || list[0].NodeID != racers[i].id {
					t.Fatalf("round %d: %s won but the list is %+v", round, racers[i].name, list)
				}
			case a.status == 409 && a.str("error") == "used":
			default:
				t.Fatalf("round %d: %s got %d %s", round, racers[i].name, a.status, a.raw)
			}
		}
		if won != 1 {
			t.Fatalf("round %d: %d devices paired with one code", round, won)
		}
	}
}

// Pairing again replaces: the old token stops working, the list does not grow.
func TestRemotePairAgain(t *testing.T) {
	r := newRemoteRig(t)
	old := r.pair(rtPhone)
	r.clock.advance(time.Hour)
	fresh := r.pair(rtPhone)
	if old == fresh {
		t.Fatal("the same token twice")
	}
	r.want(r.call(rtPhone, "GET", "/v1/status", old, nil), 401, "unauthorized")
	if a := r.call(rtPhone, "GET", "/v1/status", fresh, nil); a.status != 200 {
		t.Errorf("the new token: %d %s", a.status, a.raw)
	}
	if list := r.m.paired(); len(list) != 1 || list[0].Created != r.clock.Now().Unix() {
		t.Errorf("paired: %+v", list)
	}
}

func TestRemotePairFull(t *testing.T) {
	r := newRemoteRig(t, func(p *remotePolicy) { p.MaxPairings = 2 })
	phone := r.pair(rtPhone)
	r.pair(rtTablet)
	code := r.code()
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}), 409, "full")
	// The code was right, so it is neither counted nor spent: making room on
	// the device lets the same code through.
	if p := r.m.state().Pairing; p == nil || p.AttemptsLeft != 5 {
		t.Fatalf("the code after a full list: %+v", p)
	}
	// A device that is on the list already may still replace its own entry.
	if !r.m.revoke(rtTablet.id) {
		t.Fatal("nothing to revoke")
	}
	if a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("after making room: %d %s", a.status, a.raw)
	}
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": r.code()}); a.status != 200 {
		t.Errorf("a paired device pairing again on a full list: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
}

// The knob: this account's own devices need no code. Everyone else still does.
func TestRemotePairOwnerWithoutCode(t *testing.T) {
	r := newRemoteRig(t, func(p *remotePolicy) { p.OwnerNeedsCode = false })
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.obj["needsCode"] != false {
		t.Errorf("hello for an owner: %s", a.raw)
	}
	if a := r.call(rtFriend, "GET", "/v1/hello", "", nil); a.obj["needsCode"] != true {
		t.Errorf("hello for a guest: %s", a.raw)
	}
	// No code on show at all.
	a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": ""})
	if a.status != 200 || a.str("class") != "owner" {
		t.Fatalf("an owner without a code: %d %s", a.status, a.raw)
	}
	if ev := r.m.actions()[0]; ev.Op != "paired" || ev.Detail != "without a code" {
		t.Errorf("action log: %+v", ev)
	}
	for _, n := range []*rtNode{rtFriend, rtTagged, rtSharedIn} {
		r.want(r.call(n, "POST", "/v1/pair", "", map[string]string{"code": ""}), 400, "bad_request")
	}
	// A code on show is not spent by an owner who did not need it.
	code := r.code()
	if a := r.call(rtTablet, "POST", "/v1/pair", "", map[string]string{"code": ""}); a.status != 200 {
		t.Fatalf("a second owner: %d %s", a.status, a.raw)
	}
	if a := r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 || a.str("class") != "guest" {
		t.Errorf("the guest with the code: %d %s", a.status, a.raw)
	}

	// The default: no code, no pairing — for an owner too.
	r = newRemoteRig(t)
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": ""}), 400, "bad_request")
	r.code()
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": ""}), 400, "bad_request")
	if len(r.m.paired()) != 0 {
		t.Error("paired without a code under the default policy")
	}
}

// The knob: no guests. A node that cannot be an owner cannot pair, and does
// not get to guess.
func TestRemotePairGuestsOff(t *testing.T) {
	r := newRemoteRig(t, func(p *remotePolicy) { p.AllowGuests = false })
	code := r.code()
	for _, n := range []*rtNode{rtFriend, rtTagged, rtSharedIn, rtSharee} {
		if a := r.call(n, "GET", "/v1/hello", "", nil); a.status != 200 || a.str("class") != "" {
			t.Errorf("hello for %s: %d %s", n.name, a.status, a.raw)
		}
		r.want(r.call(n, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)}), 403, "not_allowed")
		r.want(r.call(n, "POST", "/v1/pair", "", map[string]string{"code": code}), 403, "not_allowed")
	}
	if p := r.m.state().Pairing; p == nil || p.AttemptsLeft != 5 {
		t.Errorf("the code after refused nodes: %+v", p)
	}
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": code}); a.status != 200 {
		t.Errorf("an owner: %d %s", a.status, a.raw)
	}
}

// A code needs the feature on and this node connected.
func TestRemoteStartPairingRefused(t *testing.T) {
	r := newRemoteRig(t)
	r.d.set(func(d *remoteFakeDaemon) { d.noSelf = true })
	if v := r.m.startPairing(); v.OK || v.Code != "" || v.Error == "" {
		t.Errorf("a code without an identity: %+v", v)
	}
	r.d.set(func(d *remoteFakeDaemon) { d.noSelf = false })
	r.m.configure(false, "")
	if v := r.m.startPairing(); v.OK || v.Code != "" || v.Error == "" {
		t.Errorf("a code with the feature off: %+v", v)
	}
	fresh := newRemoteManager(remoteDefaultPolicy(), r.clock, r.d)
	if v := fresh.startPairing(); v.OK {
		t.Errorf("a code before any run: %+v", v)
	}
	if st := fresh.state(); st.Available || st.Enabled || st.Listening {
		t.Errorf("state before any run: %+v", st)
	}
}

func TestRemoteQR(t *testing.T) {
	for _, bad := range []string{
		"", "482913", "tsremote:", "tsremote:1?", "tsremote:1?n=nA&c=12345", "tsremote:1?n=&c=123456",
		"tsremote:2?n=nA&c=123456", "tailsocks://remote?n=nA&c=123456", "https://example.com/?n=nA&c=123456",
		"tsremote:1?n=../../etc&c=123456", "tsremote:1?n=nA&c=123456&h=" + strings.Repeat("x", 600),
		"tsremote:1?n=nA%ZZ&c=123456",
	} {
		if q := remoteParseQR(bad); q.OK {
			t.Errorf("%q parsed as %+v", bad, q)
		}
	}
	q := remoteParseQR("tsremote:1?c=004200&h=TV+%D0%B2+%D0%B7%D0%B0%D0%BB%D0%B5%0A&n=n7HDtmCNTRL")
	if !q.OK || q.Code != "004200" || q.NodeID != "n7HDtmCNTRL" || q.Name != "TV в зале" {
		t.Errorf("parsed: %+v", q)
	}
	if !strings.Contains(RemoteParseQR("nonsense"), `"ok":false`) {
		t.Error("RemoteParseQR on nonsense")
	}
}

// A pairing is for one node and its user, as they were.
func TestRemotePairingBinding(t *testing.T) {
	r := newRemoteRig(t)
	phone := r.pair(rtPhone)
	friend := r.pair(rtFriend)

	// The phone is handed to another user: same node id, another owner.
	r.d.change(rtPhone, func(n *rtNode) { n.user = rtUserOther })
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	r.d.change(rtPhone, func(n *rtNode) { n.user = rtUserSelf })
	if a := r.call(rtPhone, "GET", "/v1/status", phone, nil); a.status != 200 {
		t.Fatalf("back with its user: %d %s", a.status, a.raw)
	}
	// It gets a tag: it is no longer a person's device, so not an owner.
	r.d.change(rtPhone, func(n *rtNode) { n.tags = []string{"tag:kiosk"} })
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	r.d.change(rtPhone, func(n *rtNode) { n.tags = nil })
	// Its key expires.
	r.d.change(rtPhone, func(n *rtNode) { n.expired = true })
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 403, "forbidden")
	r.d.change(rtPhone, func(n *rtNode) { n.expired = false })

	// The guest's pairing is unaffected by all that — but it too is one
	// user's: handed on to a third, the node is a guest nobody let in.
	if a := r.call(rtFriend, "GET", "/v1/status", friend, nil); a.status != 200 {
		t.Fatalf("the guest: %d %s", a.status, a.raw)
	}
	r.d.change(rtFriend, func(n *rtNode) { n.user = 3003 })
	r.want(r.call(rtFriend, "GET", "/v1/status", friend, nil), 401, "unauthorized")
	r.d.change(rtFriend, func(n *rtNode) { n.user = rtUserOther })
	// And guests can be shut out altogether.
	r.m.mu.Lock()
	r.m.policy.AllowGuests = false
	r.m.mu.Unlock()
	r.want(r.call(rtFriend, "GET", "/v1/status", friend, nil), 401, "unauthorized")
	r.m.mu.Lock()
	r.m.policy.AllowGuests = true
	r.m.mu.Unlock()

	// This node gets a tag: it has no person to share with a phone any more.
	r.d.change(rtSelf, func(n *rtNode) { n.tags = []string{"tag:tv"} })
	r.forgetSelf()
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	if a := r.call(rtFriend, "GET", "/v1/status", friend, nil); a.status != 200 {
		t.Errorf("the guest of a tagged node: %d %s", a.status, a.raw)
	}
	// Pairing the phone again makes it what it now is: a guest.
	if a := r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": r.code()}); a.status != 200 || a.str("class") != "guest" {
		t.Errorf("pairing with a tagged node: %d %s", a.status, a.raw)
	}
	if !r.hasOp("denied") {
		t.Errorf("the refusals left no trace: %q", r.ops())
	}
}

// The pairings belong to this node's identity. A store that turns up under
// another node is emptied before anyone is let in by it.
func TestRemoteStoreFollowsSelf(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	if r.m.store.data.Self != rtSelf.id {
		t.Fatalf("store self = %q", r.m.store.data.Self)
	}
	// The device logs in afresh and comes back as another node.
	r.d.change(rtSelf, func(n *rtNode) { n.id = "nNEWSELF" })
	r.forgetSelf()
	r.want(r.call(rtPhone, "GET", "/v1/status", token, nil), 401, "unauthorized")
	if list := r.m.paired(); len(list) != 0 {
		t.Errorf("pairings of the old identity survived: %+v", list)
	}
	if r.m.store.data.Self != "nNEWSELF" || !r.hasOp("reset") {
		t.Errorf("store self %q, log %q", r.m.store.data.Self, r.ops())
	}
	// And on disk.
	r.restart()
	if list := r.m.paired(); len(list) != 0 {
		t.Errorf("after a restart: %+v", list)
	}
}

func TestRemoteRevoke(t *testing.T) {
	r := newRemoteRig(t)
	phone := r.pair(rtPhone)
	tablet := r.pair(rtTablet)
	friend := r.pair(rtFriend)

	if r.m.revoke("nNOBODY") || r.m.revoke("") {
		t.Error("revoked a device that is not paired")
	}
	if !r.m.revoke(rtPhone.id) {
		t.Fatal("revoke said there was nothing")
	}
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	if a := r.call(rtTablet, "GET", "/v1/status", tablet, nil); a.status != 200 {
		t.Errorf("the tablet after the phone was revoked: %d", a.status)
	}
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.obj["paired"] != false {
		t.Errorf("hello after revoke: %s", a.raw)
	}

	// A device may also let go by itself; nobody else's pairing goes with it.
	if a := r.call(rtFriend, "POST", "/v1/unpair", friend, nil); a.status != 200 {
		t.Fatalf("unpair: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtFriend, "GET", "/v1/status", friend, nil), 401, "unauthorized")
	if list := r.m.paired(); len(list) != 1 || list[0].NodeID != rtTablet.id {
		t.Fatalf("paired: %+v", list)
	}

	r.pair(rtFriend)
	if n := r.m.revokeAll(); n != 2 {
		t.Errorf("revokeAll = %d, want 2", n)
	}
	if n := r.m.revokeAll(); n != 0 {
		t.Errorf("second revokeAll = %d", n)
	}
	r.want(r.call(rtTablet, "GET", "/v1/status", tablet, nil), 401, "unauthorized")

	// Revocation is on disk, not just in memory.
	r.restart()
	if list := r.m.paired(); len(list) != 0 {
		t.Errorf("after a restart: %+v", list)
	}
	r.want(r.call(rtTablet, "GET", "/v1/status", tablet, nil), 401, "unauthorized")
	want := []string{"revoked_all", "paired", "unpaired", "revoked", "paired", "paired", "paired"}
	if got := r.ops(); strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("action log: %q, want %q", got, want)
	}
}

func TestRemoteGuestExpiry(t *testing.T) {
	r := newRemoteRig(t)
	owner := r.pair(rtPhone)
	guest := r.pair(rtFriend)

	r.clock.advance(23*time.Hour + 59*time.Minute)
	a := r.call(rtFriend, "GET", "/v1/status", guest, nil)
	if a.status != 200 {
		t.Fatalf("a minute before the end: %d %s", a.status, a.raw)
	}
	if you, _ := a.obj["you"].(map[string]any); you["class"] != "guest" || you["left"] != float64(60) {
		t.Errorf("what the guest is told: %v", a.obj["you"])
	}
	for _, p := range r.m.paired() {
		switch p.NodeID {
		case rtFriend.id:
			if p.Left != 60 || p.Expires != r.clock.Now().Unix()+60 || p.LastUsed != r.clock.Now().Unix() {
				t.Errorf("the guest on the list: %+v", p)
			}
		case rtPhone.id:
			if p.Left != -1 || p.Expires != 0 {
				t.Errorf("the owner on the list: %+v", p)
			}
		}
	}

	r.clock.advance(time.Minute)
	r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	if list := r.m.paired(); len(list) != 1 || list[0].NodeID != rtPhone.id {
		t.Errorf("paired after the guest's day: %+v", list)
	}
	if !r.hasOp("expired") {
		t.Errorf("action log: %q", r.ops())
	}
	if a := r.call(rtFriend, "GET", "/v1/hello", "", nil); a.obj["paired"] != false {
		t.Errorf("hello after expiry: %s", a.raw)
	}
	// The owner is still there a year on.
	r.clock.advance(365 * 24 * time.Hour)
	if a := r.call(rtPhone, "GET", "/v1/status", owner, nil); a.status != 200 {
		t.Errorf("the owner a year later: %d %s", a.status, a.raw)
	}
	// And the expiry is on disk.
	r.restart()
	r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
}

// The knob: an owner's pairing may be given a term too.
func TestRemoteOwnerTTL(t *testing.T) {
	r := newRemoteRig(t, func(p *remotePolicy) { p.OwnerTTL = time.Hour; p.GuestTTL = 0 })
	owner := r.pair(rtPhone)
	guest := r.pair(rtFriend)
	r.clock.advance(time.Hour)
	r.want(r.call(rtPhone, "GET", "/v1/status", owner, nil), 401, "unauthorized")
	if a := r.call(rtFriend, "GET", "/v1/status", guest, nil); a.status != 200 {
		t.Errorf("a guest without a term: %d", a.status)
	}
}

// What the wall clock can and cannot do to a guest's day.
func TestRemoteExpiryAndTheClock(t *testing.T) {
	left := func(r *remoteRig) int64 {
		for _, p := range r.m.paired() {
			if p.NodeID == rtFriend.id {
				return p.Left
			}
		}
		return 0
	}
	const day = int64(24 * 3600)

	t.Run("set back, same boot", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.advance(23 * time.Hour)
		r.clock.jump(-30 * 24 * time.Hour)
		if l := left(r); l != 3600 {
			t.Errorf("left = %d, want 3600", l)
		}
		r.clock.advance(time.Hour)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})

	t.Run("set ahead, same boot", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.jump(30 * 24 * time.Hour)
		if a := r.call(rtFriend, "GET", "/v1/status", guest, nil); a.status != 200 {
			t.Errorf("a time sync ended the pairing: %d", a.status)
		}
		if l := left(r); l != day {
			t.Errorf("left = %d, want a day", l)
		}
	})

	t.Run("reboot, clock right", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.advance(2 * time.Hour)
		r.clock.reboot(10 * time.Hour) // off for ten hours
		r.restart()
		if l := left(r); l != 12*3600 {
			t.Errorf("left = %d, want 12 h: time switched off counts", l)
		}
		r.clock.advance(12 * time.Hour)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})

	t.Run("reboot, off past the end", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.reboot(25 * time.Hour)
		r.restart()
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})

	t.Run("reboot, clock behind", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.advance(20 * time.Hour)
		// The last write to the file is this request's: four hours left.
		r.m.mu.Lock()
		r.m.saveLocked()
		r.m.mu.Unlock()
		// It comes up believing it is 1 January 2020.
		r.clock.reboot(-6 * 365 * 24 * time.Hour)
		r.restart()
		if l := left(r); l != 4*3600 {
			t.Errorf("left = %d, want the 4 h it had when last written, not more", l)
		}
		if a := r.call(rtFriend, "GET", "/v1/status", guest, nil); a.status != 200 {
			t.Errorf("a clock that is behind locked the guest out early: %d", a.status)
		}
		// The time sync arrives; the boot clock already decides.
		r.clock.jump(6 * 365 * 24 * time.Hour)
		if l := left(r); l != 4*3600 {
			t.Errorf("after the sync: left = %d", l)
		}
		r.clock.advance(4 * time.Hour)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})

	t.Run("reboot after reboot, clock behind each time", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		for i := 0; i < 30; i++ {
			r.clock.advance(time.Hour)
			if a := r.call(rtFriend, "GET", "/v1/status", guest, nil); i < 23 && a.status != 200 {
				t.Fatalf("hour %d: %d", i+1, a.status)
			}
			r.m.mu.Lock()
			r.m.saveLocked()
			r.m.mu.Unlock()
			r.clock.reboot(-48 * time.Hour)
			r.restart()
		}
		// Thirty hours of use across thirty reboots with the clock set back
		// two days each time: the day is over all the same.
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})

	t.Run("reboot, clock ahead", func(t *testing.T) {
		r := newRemoteRig(t)
		owner := r.pair(rtPhone)
		guest := r.pair(rtFriend)
		r.clock.reboot(40 * 365 * 24 * time.Hour)
		r.restart()
		// Fails closed for the guest; the owner has no deadline to miss.
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
		if a := r.call(rtPhone, "GET", "/v1/status", owner, nil); a.status != 200 {
			t.Errorf("the owner: %d", a.status)
		}
	})

	t.Run("the app restarts, same boot", func(t *testing.T) {
		r := newRemoteRig(t)
		guest := r.pair(rtFriend)
		r.clock.advance(5 * time.Hour)
		r.clock.jump(-100 * 24 * time.Hour)
		r.restart()
		if l := left(r); l != 19*3600 {
			t.Errorf("left = %d, want 19 h: the boot clock carries over", l)
		}
		r.clock.advance(19 * time.Hour)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
	})
}

// forgetSelf drops the cached identity of this node, as five seconds would.
func (r *remoteRig) forgetSelf() {
	r.m.mu.Lock()
	r.m.selfAt = time.Time{}
	r.m.mu.Unlock()
}
