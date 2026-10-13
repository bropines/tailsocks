package appctr

// remote_guard.go — guarded changes: a change made over the management channel
// that could cut that very channel is kept only if the device that made it
// says, over the channel and after the change has taken effect, that it can
// still reach this one.
//
// The exit node is the plain case: a dead one takes the relay path with it,
// and then nobody can pick another from afar. So the device applies the new
// value, remembers the old one, and waits policy.GuardTTL. A confirmation —
// POST /v1/confirm with the id the change was answered with, from the same
// node, no sooner than policy.GuardSettle after the change — keeps it.
// Without one the device puts the old value back by itself and writes
// "reverted: not confirmed" to its log. That makes changes safe to offer which
// otherwise would have to stay at the device: switching between proxy and TUN
// is guarded whatever the caller asks.
//
// One change waits at a time. The same caller changing the same thing again
// replaces it and keeps the original value to go back to; anything else that
// asks for a guard meanwhile is refused. The waiting change is in the store
// file too: an app that was restarted before the confirmation came finds it
// there and reverts it, since nobody confirmed.
//
// Time is counted on the boot-time clock, like a pairing's: setting the wall
// clock neither hurries a revert nor holds it off.

import (
	"crypto/rand"
	"encoding/base32"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// remoteGuardRetry spaces the attempts at a revert the app would not take.
const remoteGuardRetry = 5 * time.Second

// remoteGuard is the change that waits for its confirmation.
type remoteGuard struct {
	remoteStoredGuard
	// boot and madeUp are the boot-time clock when the change was made.
	boot   string
	madeUp time.Duration
	// orphan: found in the store by a run that did not make it. Its time is up.
	orphan bool
	// busy: a revert is in flight.
	busy  bool
	timer *time.Timer
}

// remoteStoredGuard is what the store file keeps of it.
type remoteStoredGuard struct {
	ID string `json:"id"`
	// Key is RemoteKeyExitNode or a setting's key; Old the value to put back.
	Key string `json:"key"`
	Old string `json:"old"`
	New string `json:"new"`
	// Op is how the change was logged: "exit_node" or "setting".
	Op     string `json:"op"`
	NodeID string `json:"nodeId"`
	Name   string `json:"name,omitempty"`
	Class  string `json:"class,omitempty"`
}

// remoteGuardView is a waiting change as the device that made it is told.
type remoteGuardView struct {
	ID  string `json:"id"`
	Key string `json:"key"`
	// Seconds is how long the device waits before it reverts, counted from
	// the answer that carries it; in a status answer the same is Left.
	Seconds int64 `json:"seconds,omitempty"`
	Left    int64 `json:"left,omitempty"`
	// After is how many seconds have to pass before a confirmation is taken.
	After int64 `json:"after"`
}

func remoteNewGuardID() (string, error) {
	var b [5]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return "g" + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b[:])), nil
}

// guardTimesLocked is how long g has before it is reverted and how long before
// it can be confirmed; both zero when its time is up. Caller holds m.mu.
func (m *remoteManager) guardTimesLocked(g *remoteGuard) (left, after time.Duration) {
	boot, up := m.clock.Boot()
	if g.orphan || boot != g.boot {
		return 0, 0
	}
	age := up - g.madeUp
	if left = m.policy.GuardTTL - age; left < 0 {
		left = 0
	}
	if after = m.policy.GuardSettle - age; after < 0 || left == 0 {
		after = 0
	}
	return left, after
}

func remoteCeilSeconds(d time.Duration) int64 {
	return int64((d + time.Second - 1) / time.Second)
}

// guardFor is the waiting change of the calling node, for its status.
func (m *remoteManager) guardFor(nodeID string) *remoteGuardView {
	m.mu.Lock()
	defer m.mu.Unlock()
	g := m.guard
	if g == nil || g.NodeID != nodeID {
		return nil
	}
	left, after := m.guardTimesLocked(g)
	if left == 0 {
		return nil
	}
	return &remoteGuardView{ID: g.ID, Key: g.Key, Left: remoteCeilSeconds(left), After: remoteCeilSeconds(after)}
}

// changeGuarded makes one change of key through the app and holds it for a
// confirmation. op and detail are for the log, as in change.
func (m *remoteManager) changeGuarded(rq *remoteRequest, op, key, value, detail string) (int, any) {
	m.guarding.Lock()
	defer m.guarding.Unlock()

	m.mu.Lock()
	m.adoptGuardLocked()
	g := m.guard
	var old string
	replaces := false
	if g != nil {
		if left, _ := m.guardTimesLocked(g); left == 0 || g.NodeID != rq.caller.NodeID || g.Key != key {
			// Another device's, another setting's, or one whose time is up
			// and which is being put back right now.
			m.mu.Unlock()
			return http.StatusConflict, remoteError{Error: "guard_pending", Message: "another change is waiting to be confirmed"}
		}
		// The same thing changed again before the first was confirmed: what
		// to go back to is still what was there before either.
		old, replaces = g.Old, true
	}
	m.mu.Unlock()

	if !replaces {
		v, _, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.GetSetting(key) })
		if err != nil {
			// Without the old value there is nothing to go back to, so the
			// change is not made at all — and that is what is said.
			m.record(rq, op, detail+" (not asked of the app)", false)
			return http.StatusServiceUnavailable, remoteError{Error: "unavailable", Message: "the app did not say what the setting is now; nothing was changed"}
		}
		old = v
	}
	status, body := m.change(rq, op, detail, func(d RemoteDelegate) error { return d.SetSetting(key, value) })
	if status != http.StatusOK && status != http.StatusGatewayTimeout {
		return status, body
	}
	if old == value {
		// Back where it was before the change that waited, or never away from
		// it: nothing is left to put back.
		m.clearGuard(g)
		return status, body
	}
	view, err := m.armGuard(rq, op, key, old, value)
	if err != nil {
		// Applied, and cannot be watched over. Said as it is.
		slog.Error("Remote management: a guarded change could not be armed", "err", err)
		return status, body
	}
	if status == http.StatusOK {
		return http.StatusOK, struct {
			Guard remoteGuardView `json:"guard"`
		}{view}
	}
	// The app has not answered: the change may still be applied, and if it
	// is, it is put back unless confirmed like any other.
	refusal, _ := body.(remoteError)
	return status, struct {
		remoteError
		Guard remoteGuardView `json:"guard"`
	}{refusal, view}
}

// armGuard makes the change just made the waiting one.
func (m *remoteManager) armGuard(rq *remoteRequest, op, key, old, value string) (remoteGuardView, error) {
	id, err := remoteNewGuardID()
	if err != nil {
		return remoteGuardView{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if prev := m.guard; prev != nil && prev.timer != nil {
		prev.timer.Stop()
	}
	g := &remoteGuard{remoteStoredGuard: remoteStoredGuard{
		ID: id, Key: key, Old: old, New: value, Op: op,
		NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(),
	}}
	g.boot, g.madeUp = m.clock.Boot()
	g.timer = time.AfterFunc(m.policy.GuardTTL+time.Second, m.guardTick)
	m.guard = g
	if m.store != nil {
		stored := g.remoteStoredGuard
		m.store.data.Guard = &stored
		m.saveLocked()
	}
	m.rev++
	return remoteGuardView{ID: id, Key: key, Seconds: remoteCeilSeconds(m.policy.GuardTTL), After: remoteCeilSeconds(m.policy.GuardSettle)}, nil
}

// clearGuard forgets g if it is the one that waits.
func (m *remoteManager) clearGuard(g *remoteGuard) {
	if g == nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.clearGuardLocked(g)
}

func (m *remoteManager) clearGuardLocked(g *remoteGuard) {
	if m.guard != g {
		return
	}
	if g.timer != nil {
		g.timer.Stop()
	}
	m.guard = nil
	if m.store != nil && m.store.data.Guard != nil {
		m.store.data.Guard = nil
		m.saveLocked()
	}
	m.rev++
}

// adoptGuardLocked takes over a change the store holds and this run did not
// make: an earlier run of the app was ended before anyone confirmed it.
// Caller holds m.mu.
func (m *remoteManager) adoptGuardLocked() {
	if m.guard != nil || m.store == nil || m.store.data.Guard == nil {
		return
	}
	stored := *m.store.data.Guard
	if stored.Key == "" || stored.NodeID == "" {
		m.store.data.Guard = nil
		return
	}
	m.guard = &remoteGuard{remoteStoredGuard: stored, orphan: true}
}

func (m *remoteManager) handleConfirm(rq *remoteRequest) (int, any) {
	var in struct {
		ID string `json:"id"`
	}
	if !remoteDecode(rq.body, &in) || in.ID == "" || len(in.ID) > 32 {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	m.mu.Lock()
	g := m.guard
	if g == nil || g.ID != in.ID || g.NodeID != rq.caller.NodeID {
		m.mu.Unlock()
		// Not this caller's, never made, or reverted already: all one answer.
		return http.StatusNotFound, remoteError{Error: "not_found", Message: "no change is waiting for that confirmation; if one was, it has been put back"}
	}
	left, after := m.guardTimesLocked(g)
	switch {
	case left == 0 || g.busy:
		m.mu.Unlock()
		return http.StatusNotFound, remoteError{Error: "not_found", Message: "no change is waiting for that confirmation; if one was, it has been put back"}
	case after > 0:
		m.mu.Unlock()
		return http.StatusConflict, struct {
			remoteError
			After int64 `json:"after"`
		}{remoteError{Error: "too_early", Message: "the change has not had time to take effect"}, remoteCeilSeconds(after)}
	}
	m.clearGuardLocked(g)
	m.auditLocked(remoteEvent{NodeID: g.NodeID, Name: g.Name, Class: g.Class, Op: "confirmed", Detail: remoteGuardDetail(g), OK: true})
	m.saveLocked()
	m.mu.Unlock()
	slog.Info("Remote management: confirmed", "device", g.Name, "node", g.NodeID, "what", g.Key)
	return http.StatusOK, struct{}{}
}

// remoteGuardDetail names a waiting change in the log: the setting and its
// value; for the exit node the key alone, the node's name being in the line
// of the change itself.
func remoteGuardDetail(g *remoteGuard) string {
	if g.Key == RemoteKeyExitNode {
		return g.Key
	}
	return g.Key + "=" + g.New
}

// guardTick puts the waiting change back if its time is up. The timer armed
// with the change calls it, and so does whatever starts a run (remoteApply):
// a change left by an earlier run has nobody to confirm it.
func (m *remoteManager) guardTick() {
	m.mu.Lock()
	m.adoptGuardLocked()
	g := m.guard
	if g == nil || g.busy {
		m.mu.Unlock()
		return
	}
	if left, _ := m.guardTimesLocked(g); left > 0 {
		m.mu.Unlock()
		return
	}
	g.busy = true
	m.mu.Unlock()

	done := m.guardRevert(g)

	m.mu.Lock()
	g.busy = false
	if m.guard == g {
		if done {
			m.clearGuardLocked(g)
		} else {
			// The app was not there to take it. It stays due, and is tried
			// again until it is.
			g.timer = time.AfterFunc(remoteGuardRetry, m.guardTick)
		}
	}
	m.mu.Unlock()
}

// guardRevert writes the old value back through the app and logs what came of
// it; false when the app could not be asked and it has to be tried again.
func (m *remoteManager) guardRevert(g *remoteGuard) bool {
	set := func(value string) error {
		_, late, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return "", d.SetSetting(g.Key, value) })
		if errors.Is(err, errRemotePending) {
			// Made and not answered yet. Nothing else can be asked of the app
			// meanwhile, so wait for what it says.
			err = (<-late).err
		}
		return err
	}
	detail := g.Key + ": not confirmed"
	err := set(g.Old)
	if errors.Is(err, errRemoteNoDelegate) || errors.Is(err, errRemoteBusy) {
		return false
	}
	if err != nil && g.Key == RemoteKeyExitNode && g.Old != "" {
		// The exit node from before is gone. None at all is the way back to a
		// device that can be reached.
		if err = set(""); err == nil {
			detail = g.Key + ": not confirmed; the one before is gone, so none"
		}
	}
	ok := err == nil
	if !ok {
		detail += " (the app refused to put it back)"
	}
	m.mu.Lock()
	m.auditLocked(remoteEvent{NodeID: g.NodeID, Name: g.Name, Class: g.Class, Op: "reverted", Detail: detail, OK: ok})
	m.saveLocked()
	// This node's exit node is part of the identity cached for a few seconds.
	m.selfAt = time.Time{}
	m.mu.Unlock()
	m.forgetReport()
	slog.Warn("Remote management: reverted", "device", g.Name, "node", g.NodeID, "what", detail, "ok", ok)
	m.notice(g.Name, "reverted", detail)
	return true
}
