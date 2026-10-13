package appctr

// A removal — a revocation, a device letting go, an expiry — must not come
// back with the next start of the app, whatever state the disk is in.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// rtLock makes the store's directory, and with file also the store itself,
// refuse writes, and returns the way back.
func rtLock(t *testing.T, r *remoteRig, file bool) (unlock func()) {
	t.Helper()
	if os.Geteuid() == 0 {
		t.Skip("root writes anywhere")
	}
	dir := filepath.Dir(r.store)
	unlock = func() {
		os.Chmod(dir, 0o700)
		os.Chmod(r.store, 0o600)
	}
	t.Cleanup(unlock)
	if file {
		if err := os.Chmod(r.store, 0o400); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	return unlock
}

// A removal is on disk before it is called done. When the directory will not
// take a new file — a full disk does that — the store is overwritten where it
// lies: the revoked device stays out after a restart, and the others stay in.
func TestRemoteRemovalsAreDurable(t *testing.T) {
	for name, remove := range map[string]func(r *remoteRig, token string){
		"revoked on the device": func(r *remoteRig, _ string) {
			if got := r.m.revoke(rtPhone.id); !got.OK || got.Removed != 1 || got.Error != "" {
				r.t.Errorf("revoke: %+v", got)
			}
		},
		"unpaired by itself": func(r *remoteRig, token string) {
			if a := r.call(rtPhone, "POST", "/v1/unpair", token, nil); a.status != 200 {
				r.t.Errorf("unpair: %d %s", a.status, a.raw)
			}
		},
	} {
		t.Run(name, func(t *testing.T) {
			r := newRemoteRig(t)
			phone, tablet := r.pair(rtPhone), r.pair(rtTablet)
			unlock := rtLock(t, r, false)
			remove(r, phone)
			if st := r.m.state(); st.Unsaved || st.Paired != 1 {
				t.Errorf("state after the removal: %+v", st)
			}
			r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
			unlock()
			r.restart()
			r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
			if a := r.call(rtTablet, "GET", "/v1/status", tablet, nil); a.status != 200 {
				t.Errorf("the pairing that was not removed, after the restart: %d %s", a.status, a.raw)
			}
			if list := r.m.paired(); len(list) != 1 || list[0].NodeID != rtTablet.id {
				t.Errorf("paired after the restart: %+v", list)
			}
		})
	}

	t.Run("all revoked", func(t *testing.T) {
		r := newRemoteRig(t)
		phone, tablet := r.pair(rtPhone), r.pair(rtTablet)
		unlock := rtLock(t, r, false)
		if got := r.m.revokeAll(); !got.OK || got.Removed != 2 {
			t.Errorf("revokeAll: %+v", got)
		}
		unlock()
		r.restart()
		r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
		r.want(r.call(rtTablet, "GET", "/v1/status", tablet, nil), 401, "unauthorized")
	})

	t.Run("expired", func(t *testing.T) {
		r := newRemoteRig(t)
		owner, guest := r.pair(rtPhone), r.pair(rtFriend)
		r.clock.advance(24 * time.Hour)
		unlock := rtLock(t, r, false)
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
		unlock()
		// It comes up after a reboot with its clock a week behind: were the
		// expired entry still in the file, with the day it had left when the
		// file was last written before that, the guest would be back.
		r.clock.reboot(-7 * 24 * time.Hour)
		r.restart()
		r.want(r.call(rtFriend, "GET", "/v1/status", guest, nil), 401, "unauthorized")
		if a := r.call(rtPhone, "GET", "/v1/status", owner, nil); a.status != 200 {
			t.Errorf("the owner after the restart: %d %s", a.status, a.raw)
		}
	})

	t.Run("this node became another", func(t *testing.T) {
		r := newRemoteRig(t)
		phone := r.pair(rtPhone)
		unlock := rtLock(t, r, false)
		r.d.change(rtSelf, func(n *rtNode) { n.id = "nNEWSELF" })
		r.forgetSelf()
		r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
		unlock()
		// Back under the old identity after a restart — a restored backup,
		// say: the pairings that were dropped stay dropped.
		r.d.change(rtSelf, func(n *rtNode) { n.id = rtSelf.id })
		r.restart()
		r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	})
}

// When nothing at all can be written, a removal is still in force here and
// now, is reported as not saved, is retried until it is, and a store that
// cannot be written is not believed by the next start.
func TestRemoteRemovalThatCannotBeWritten(t *testing.T) {
	r := newRemoteRig(t)
	phone, tablet, friend := r.pair(rtPhone), r.pair(rtTablet), r.pair(rtFriend)
	unlock := rtLock(t, r, true)

	got := r.m.revoke(rtPhone.id)
	if got.OK || got.Removed != 1 || got.Error != "not_saved" {
		t.Fatalf("revoke with nothing writable: %+v, want not ok, 1 removed, not_saved", got)
	}
	if raw := remoteReply(got); raw != `{"ok":false,"removed":1,"error":"not_saved"}` {
		t.Errorf("as the screen gets it: %s", raw)
	}
	// In force all the same.
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	if st := r.m.state(); !st.Unsaved || st.Paired != 2 {
		t.Errorf("state: %+v, want unsaved with two paired", st)
	}
	// A device that lets go by itself is told the same.
	r.want(r.call(rtFriend, "POST", "/v1/unpair", friend, nil), 500, "not_saved")
	r.want(r.call(rtFriend, "GET", "/v1/status", friend, nil), 401, "unauthorized")
	if all := r.m.revokeAll(); all.OK || all.Removed != 1 || all.Error != "not_saved" {
		t.Errorf("revokeAll with nothing writable: %+v", all)
	}
	r.want(r.call(rtTablet, "GET", "/v1/status", tablet, nil), 401, "unauthorized")
	// Nothing new is granted on a store in that state.
	r.want(r.call(rtPhone, "POST", "/v1/pair", "", map[string]string{"code": r.code()}), 500, "unavailable")
	if len(r.m.paired()) != 0 {
		t.Errorf("paired: %+v", r.m.paired())
	}

	// The app is killed and started again, the disk as it was: the file still
	// lists all three, and none of them is let in by it.
	raw, err := os.ReadFile(r.store)
	if err != nil || !strings.Contains(string(raw), rtPhone.id) || !strings.Contains(string(raw), rtTablet.id) {
		t.Fatalf("the file was meant to be untouched: %v\n%s", err, raw)
	}
	r.restart()
	for node, token := range map[*rtNode]string{rtPhone: phone, rtTablet: tablet, rtFriend: friend} {
		r.want(r.call(node, "GET", "/v1/status", token, nil), 401, "unauthorized")
	}
	if st := r.m.state(); st.Paired != 0 || !st.Unsaved {
		t.Errorf("state after a restart on an unwritable store: %+v", st)
	}
	if !r.hasOp("reset") {
		t.Errorf("action log: %q", r.ops())
	}

	// The disk works again: the next look at the state, a few seconds on,
	// writes what is in memory, and from then on that is what the file says.
	unlock()
	r.clock.advance(remoteRetryEvery)
	if st := r.m.state(); st.Unsaved {
		t.Errorf("still unsaved once the disk works: %+v", st)
	}
	r.restart()
	for node, token := range map[*rtNode]string{rtPhone: phone, rtTablet: tablet, rtFriend: friend} {
		r.want(r.call(node, "GET", "/v1/status", token, nil), 401, "unauthorized")
	}
	if got := r.m.revokeAll(); !got.OK || got.Removed != 0 {
		t.Errorf("revokeAll on the empty list: %+v", got)
	}
}

// The same, with the process surviving: the write that failed is retried, and
// once it lands a restart finds the removal and nothing else changed.
func TestRemoteRemovalIsRetried(t *testing.T) {
	r := newRemoteRig(t)
	phone, tablet := r.pair(rtPhone), r.pair(rtTablet)
	unlock := rtLock(t, r, true)
	if got := r.m.revoke(rtPhone.id); got.OK || got.Error != "not_saved" {
		t.Fatalf("revoke: %+v", got)
	}
	unlock()
	// Not hammered: asked again at once, the disk is left alone.
	if st := r.m.state(); !st.Unsaved {
		t.Fatalf("retried before its time: %+v", st)
	}
	r.clock.advance(remoteRetryEvery)
	// Any request passes through the retry, not only the screen's poll.
	if a := r.call(rtTablet, "GET", "/v1/status", tablet, nil); a.status != 200 {
		t.Fatalf("the tablet: %d %s", a.status, a.raw)
	}
	if st := r.m.state(); st.Unsaved {
		t.Fatalf("not written though the disk works: %+v", st)
	}
	r.restart()
	r.want(r.call(rtPhone, "GET", "/v1/status", phone, nil), 401, "unauthorized")
	if a := r.call(rtTablet, "GET", "/v1/status", tablet, nil); a.status != 200 {
		t.Errorf("the tablet after the restart: %d %s", a.status, a.raw)
	}
}
