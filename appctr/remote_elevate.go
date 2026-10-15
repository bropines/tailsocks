package appctr

// remote_elevate.go — full rights for a guest, for a while.
//
// A person at the managed device can give one guest's pairing an owner's
// rights for 15 minutes, an hour, eight hours or until the guest's pairing
// ends (a day at most). Only the app gives them, through RemoteElevate, after
// that person unlocked the device — its fingerprint, face or screen lock, or,
// on a device with no lock at all, a dialog answered on the device itself.
// Nothing that arrives over the network reaches RemoteElevate: a guest can
// only ask (POST /v1/elevate/request), and asking puts a question in front of
// the person at the device, as a request for a permission's page does
// (remote_app.go). Grants nothing by itself, and is rate-limited.
//
// While they last the guest is an owner: every operation, setting, action and
// permission page an owner has, and the full-control tier when the device's
// switch for it is on — or when the person who gave the rights added it to
// them. That second way is part of the grant, not a turn of the device's
// switch: it ends with the grant by construction, no write can leave it on
// behind, and the owners' devices get nothing from it meanwhile. Nothing on
// the lists of remote_policy.go grows: what no owner may do from afar — stop
// TailSocks for good, sign out, accounts and the login server, credentials,
// the control proxy and bypass, Root Mode, granting a permission, switching
// full control on — no elevated guest may either.
//
// They end, and the guest is a guest again, when their time is up; when they
// are taken back at the device (RemoteElevateEnd); when the device's switch
// "let guests get full rights for a while" goes off (StartOptions.
// RemoteNoElevation, RemoteAllowElevation) or guests are shut out; and with the
// pairing itself — expired, revoked, unpaired by the guest, replaced by a new
// pairing. Each end but the last is a line of the action log, under the reason
// (remoteElevateEnds); the pairing's own line says the rest.
//
// Kept with the pairing in the store, so a restart of the app or the daemon
// keeps them, and counted the way a pairing's term is: on the boot-time clock
// within a boot, and across a reboot never longer than they had left at the
// last write. On top of that they end at the wall clock's Granted+Seconds, and
// a clock set back never stretches them beyond Seconds (remoteElevation).
//
// An owner's pairing has nothing to be raised to and is left alone. A tagged
// device has no owner (remote_auth.go, classOf): every pairing there is a
// guest's, and this is the one way a guest there gets an owner's rights. That
// is allowed: the person at the device says so, with its lock.

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"time"
)

// remoteElevateLengths are the lengths, in seconds, full rights are given and
// asked for; 0 is "until the guest's pairing ends", remoteElevateMax at most.
var remoteElevateLengths = []int64{15 * 60, 60 * 60, 8 * 60 * 60, 0}

// remoteElevateMax caps a grant: a guest's pairing lasts a day.
const remoteElevateMax = 24 * time.Hour

// A guest may ask twice in a row and then once a minute: each request puts a
// question in front of the person at the device.
const (
	remoteElevateAskRate  = 1.0 / 60
	remoteElevateAskBurst = 2
)

// Why full rights ended, as the action log's "elevation_ended" line names it.
// The app has words for each (ui/RemoteLogLine.kt).
const (
	remoteElevateEndTime      = "time"
	remoteElevateEndTakenBack = "taken_back"
	remoteElevateEndSwitchOff = "switch_off"
	remoteElevateEndGuestsOff = "guests_off"
)

var remoteElevateEnds = []string{remoteElevateEndTime, remoteElevateEndTakenBack, remoteElevateEndSwitchOff, remoteElevateEndGuestsOff}

func remoteElevateLengthOK(seconds int64) bool {
	for _, l := range remoteElevateLengths {
		if l == seconds {
			return true
		}
	}
	return false
}

// remoteElevatedView is full rights in force: for the device's own page and
// the app's notification (with the guest's node and name), and for the guest's
// own status (without).
type remoteElevatedView struct {
	NodeID string `json:"nodeId,omitempty"`
	Name   string `json:"name,omitempty"`
	// Left is the seconds they have left; Until the same moment as a Unix time
	// by this device's clock; Seconds the length they were given for.
	Left    int64 `json:"left"`
	Until   int64 `json:"until"`
	Seconds int64 `json:"seconds"`
	Full    bool  `json:"full,omitempty"`
}

// elevationAllowedLocked is whether full rights may be given and used here
// now: the policy, the device's switch, and guests let in at all.
func (m *remoteManager) elevationAllowedLocked() bool {
	return m.policy.AllowElevation && !m.noElevate && m.guestsAllowedLocked()
}

// elevationLocked is p's full rights while they are in force, with the seconds
// they have left; nil otherwise. Caller holds m.mu.
func (m *remoteManager) elevationLocked(p *remotePairing) (*remoteElevation, int64) {
	if p == nil || p.Elevation == nil || m.store == nil || remoteClassOf(p.Class) != remoteGuest || !m.elevationAllowedLocked() {
		return nil, 0
	}
	left := m.store.elevationLeft(p.Elevation)
	if left <= 0 {
		return nil, 0
	}
	return p.Elevation, left
}

// elevatedViewLocked is p's full rights as they are shown, nil when none are
// in force; named adds the guest's node and name. Caller holds m.mu.
func (m *remoteManager) elevatedViewLocked(p *remotePairing, named bool) *remoteElevatedView {
	e, left := m.elevationLocked(p)
	if e == nil {
		return nil
	}
	v := &remoteElevatedView{Left: left, Until: m.clock.Now().Unix() + left, Seconds: e.Seconds, Full: e.Full}
	if named {
		v.NodeID, v.Name = p.NodeID, p.Name
	}
	return v
}

// endElevationsLocked takes away the full rights that are over — their time is
// up, the switch is off, guests are shut out — and logs each under its reason.
// It reports how many ended; the caller saves and tells the app. Caller holds
// m.mu.
func (m *remoteManager) endElevationsLocked() int {
	if m.store == nil {
		return 0
	}
	n := 0
	for i := range m.store.data.Pairings {
		p := &m.store.data.Pairings[i]
		if p.Elevation == nil {
			continue
		}
		why := ""
		switch {
		case m.store.elevationLeft(p.Elevation) == 0:
			why = remoteElevateEndTime
		case !m.guestsAllowedLocked():
			why = remoteElevateEndGuestsOff
		case !m.policy.AllowElevation || m.noElevate:
			why = remoteElevateEndSwitchOff
		case remoteClassOf(p.Class) != remoteGuest:
			// Only a damaged store could hold this; the decode drops it.
			why = remoteElevateEndSwitchOff
		default:
			continue
		}
		p.Elevation = nil
		m.auditLocked(remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "elevation_ended", Detail: why, OK: true})
		slog.Info("Remote management: full rights ended", "device", p.Name, "node", p.NodeID, "why", why)
		n++
	}
	return n
}

// elevationsLocked lists the full rights in force, oldest pairing first.
// Caller holds m.mu.
func (m *remoteManager) elevationsLocked() []remoteElevatedView {
	out := []remoteElevatedView{}
	if m.store == nil {
		return out
	}
	for i := range m.store.data.Pairings {
		if v := m.elevatedViewLocked(&m.store.data.Pairings[i], true); v != nil {
			out = append(out, *v)
		}
	}
	return out
}

// tellElevations tells the app which full rights are in force, after they
// changed here: it says so in a notification on the device. On a thread of its
// own, and the list is read when the app is called, so that of two tells the
// later one is the true one.
func (m *remoteManager) tellElevations() {
	go func() {
		_, _, _ = m.callDelegateWithin(m.delegateWait, remoteNoticeAnswer, func(d RemoteDelegate) (string, error) {
			m.mu.Lock()
			list := m.elevationsLocked()
			m.mu.Unlock()
			args, err := json.Marshal(struct {
				Elevations []remoteElevatedView `json:"elevations"`
			}{list})
			if err != nil {
				return "", err
			}
			return d.Request(remoteRequestElevations, string(args))
		})
	}()
}

// armElevationTimerLocked sets a timer for the next full rights to end, so
// that the end is logged and told on time and not at the next request.
// Caller holds m.mu.
func (m *remoteManager) armElevationTimerLocked() {
	if m.elevateTimer != nil {
		m.elevateTimer.Stop()
		m.elevateTimer = nil
	}
	if m.store == nil {
		return
	}
	next := int64(-1)
	for i := range m.store.data.Pairings {
		if _, left := m.elevationLocked(&m.store.data.Pairings[i]); left > 0 && (next < 0 || left < next) {
			next = left
		}
	}
	if next < 0 {
		return
	}
	m.elevateTimer = time.AfterFunc(time.Duration(next)*time.Second+time.Second, m.elevationTick)
}

// elevationTick ends what is over and sets the timer for what is next. The
// timer calls it, and so does whatever starts a run (remoteApply): full rights
// found in the store may have run out meanwhile.
func (m *remoteManager) elevationTick() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return
	}
	m.expireLocked()
	m.armElevationTimerLocked()
}

// setElevation records the device's switch "let guests get full rights for a
// while". Off ends what is in force, at once.
func (m *remoteManager) setElevation(allowed bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.noElevate == !allowed {
		return
	}
	m.noElevate = !allowed
	m.rev++
	m.expireLocked()
}

// RemoteAllowElevation is the device's switch "let guests get full rights for a
// while", for the moment it is turned: it reaches the bridge with the next
// options as well (StartOptions.RemoteNoElevation), and this does not wait for
// those. Off ends the full rights in force.
func RemoteAllowElevation(allowed bool) { remote.setElevation(allowed) }

// remoteElevateAnswer is what RemoteElevate and RemoteElevateEnd return.
type remoteElevateAnswer struct {
	OK bool `json:"ok"`
	// Error is one of: not_paired, not_guest (an owner's pairing), off (the
	// switch is off), no_guests, bad_request (a length that is not offered),
	// not_elevated (nothing to take back), not_saved — for a grant: it was not
	// given; for a take-back: it holds from now on and is being written.
	Error string `json:"error,omitempty"`
	// What was given: the seconds left, the end by this device's clock, the
	// length — a pairing that ends sooner shortens it — and the full-control
	// tier.
	Left    int64 `json:"left,omitempty"`
	Until   int64 `json:"until,omitempty"`
	Seconds int64 `json:"seconds,omitempty"`
	Full    bool  `json:"full,omitempty"`
}

// RemoteElevate gives the guest paired as nodeID an owner's rights for seconds
// — 900, 3600, 28800, or 0 for "until its pairing ends" — and the full-control
// tier as well when full. For the app alone, after a person at the device
// unlocked it: no route of the endpoint leads here. Giving again replaces what
// was given. Returns {"ok","left","until","seconds","full"} or {"ok":false,
// "error"}; see remoteElevateAnswer.
func RemoteElevate(nodeID string, seconds int64, full bool) string {
	return remoteReply(remote.elevate(nodeID, seconds, full))
}

func (m *remoteManager) elevate(nodeID string, seconds int64, full bool) remoteElevateAnswer {
	if !remoteElevateLengthOK(seconds) {
		return remoteElevateAnswer{Error: "bad_request"}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return remoteElevateAnswer{Error: "not_paired"}
	}
	m.expireLocked()
	p := m.store.find(nodeID)
	switch {
	case p == nil:
		return remoteElevateAnswer{Error: "not_paired"}
	case remoteClassOf(p.Class) != remoteGuest:
		return remoteElevateAnswer{Error: "not_guest"}
	case !m.guestsAllowedLocked():
		return remoteElevateAnswer{Error: "no_guests"}
	case !m.elevationAllowedLocked():
		return remoteElevateAnswer{Error: "off"}
	}
	length := int64(remoteElevateMax / time.Second)
	if seconds > 0 && seconds < length {
		length = seconds
	}
	// Never past the pairing: the rights are the pairing's.
	if left := m.store.left(p); left >= 0 && left < length {
		length = left
	}
	if length <= 0 {
		return remoteElevateAnswer{Error: "not_paired"}
	}
	before, wasUnsaved := p.Elevation, m.store.unsaved
	m.store.elevate(p, length, full)
	detail := strconv.FormatInt(length, 10)
	if full {
		detail += ": full"
	}
	if !m.saveLockedWith(remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "elevated", Detail: detail, OK: true}) {
		// A grant that is not on disk is not given.
		p.Elevation = before
		m.store.unsaved = wasUnsaved
		return remoteElevateAnswer{Error: "not_saved"}
	}
	slog.Info("Remote management: full rights given", "device", p.Name, "node", p.NodeID, "seconds", length, "full", full)
	m.armElevationTimerLocked()
	m.tellElevations()
	return remoteElevateAnswer{OK: true, Left: length, Until: m.clock.Now().Unix() + length, Seconds: length, Full: full}
}

// RemoteElevateEnd takes the full rights of the guest paired as nodeID back, at
// once. Returns {"ok"} or {"ok":false,"error"}: not_elevated when none were in
// force, not_saved when the guest is a guest from now on and that is not on
// disk yet.
func RemoteElevateEnd(nodeID string) string { return remoteReply(remote.elevateEnd(nodeID)) }

func (m *remoteManager) elevateEnd(nodeID string) remoteElevateAnswer {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return remoteElevateAnswer{Error: "not_elevated"}
	}
	m.expireLocked()
	p := m.store.find(nodeID)
	if p == nil || p.Elevation == nil {
		return remoteElevateAnswer{Error: "not_elevated"}
	}
	p.Elevation = nil
	m.auditLocked(remoteEvent{NodeID: p.NodeID, Name: p.Name, Class: p.Class, Op: "elevation_ended", Detail: remoteElevateEndTakenBack, OK: true})
	slog.Info("Remote management: full rights taken back", "device", p.Name, "node", p.NodeID)
	saved := m.saveRemovalLocked()
	m.armElevationTimerLocked()
	m.tellElevations()
	if !saved {
		return remoteElevateAnswer{Error: "not_saved"}
	}
	return remoteElevateAnswer{OK: true}
}

// handleElevateRequest puts the guest's request for full rights in front of the
// person at the device, the way a permission's page is (remote_app.go): the
// answer is what the app did about it — "shown", "notified" or "waiting" —
// never that anything was given. Giving takes that person and the device's
// lock (RemoteElevate).
func (m *remoteManager) handleElevateRequest(rq *remoteRequest) (int, any) {
	var in struct {
		Seconds int64 `json:"seconds"`
	}
	if !remoteDecode(rq.body, &in) || !remoteElevateLengthOK(in.Seconds) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	m.mu.Lock()
	allowed := m.elevationAllowedLocked()
	m.mu.Unlock()
	switch {
	case rq.paired != remoteGuest:
		return http.StatusConflict, remoteError{Error: "refused", Message: "an owner's device has full rights already"}
	case rq.elevated != nil:
		return http.StatusConflict, remoteError{Error: "refused", Message: "full rights are given already"}
	case !allowed:
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "this device does not give guests full rights"}
	}
	if !m.elevateAsks.allow(rq.caller.NodeID) {
		return http.StatusTooManyRequests, remoteError{Error: "slow_down"}
	}
	// Who asks is the tailnet's name for the caller, from whois; the caller
	// sends no text that is shown.
	args, err := json.Marshal(struct {
		From    string `json:"from"`
		NodeID  string `json:"nodeId"`
		Seconds int64  `json:"seconds"`
	}{rq.caller.Name, rq.caller.NodeID, in.Seconds})
	if err != nil {
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	result, status, refusal := m.changeTold(rq, "elevate_asked", strconv.FormatInt(in.Seconds, 10), func(d RemoteDelegate) (string, error) {
		return d.Request(remoteRequestElevateAsk, string(args))
	}, func(v string) (string, bool) {
		return ": " + v, remoteAskResults[v]
	})
	if status != http.StatusOK {
		return status, refusal
	}
	return http.StatusOK, struct {
		Result string `json:"result"`
	}{result}
}

// canFor is what a caller may use right now, as its status lists it: its class
// at its level, and asking for full rights only for a guest that has none,
// where the device gives them.
func (m *remoteManager) canFor(rq *remoteRequest) []string {
	m.mu.Lock()
	ask := rq.paired == remoteGuest && rq.elevated == nil && m.elevationAllowedLocked()
	m.mu.Unlock()
	out := []string{}
	for _, op := range m.can(rq.class, rq.full) {
		if op != remoteOpElevateAsk || ask {
			out = append(out, op)
		}
	}
	return out
}
