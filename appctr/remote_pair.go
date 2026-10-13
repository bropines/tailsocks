package appctr

// remote_pair.go — the pairing code and the pairing it buys.
//
// The code is a ticket, not a credential: six digits from the system's CSPRNG,
// shown on the managed device, good for remotePolicy.CodeTTL, for one pairing,
// and for CodeAttempts wrong guesses counted across every caller. It lives in
// memory only, and its deadline is on the boot-time clock. What a correct code
// creates — the entry in the store and the token — is separate and outlives it.
//
// One mutex decides a race: of two devices that send the right code at the
// same moment the first gets the pairing and the second is told the code was
// used. A spent code stays recognisable until its own deadline, also while the
// next code is on show: a phone that repeats a request whose answer was lost
// is told "used", and does not cost the new code one of its guesses.
//
// The guesses belong to the code, not to who makes them. Any node the tailnet
// policy lets reach the port can spend all of them and so keep a device from
// being paired for as long as it cares to; that denies pairing, it does not
// get round it, and the device shows whose guesses they were, as whois has it.

import (
	"crypto/subtle"
	"log/slog"
	"net/url"
	"time"
)

// remoteQRScheme starts the text of a pairing QR code. It is deliberately not
// a tailsocks:// link: those open screens and nothing else, and a web page
// must not be able to start a pairing by handing the system a link. Only the
// in-app scanner understands this.
const remoteQRScheme = "tsremote"

type remoteAttempt struct {
	Name   string `json:"name"`
	Login  string `json:"login,omitempty"`
	NodeID string `json:"nodeId"`
	Time   int64  `json:"time"`
}

// remoteWindow is a pairing code on show.
type remoteWindow struct {
	code     string
	qr       string
	deadline time.Duration // boot-clock reading at which it dies
	boot     string
	wrong    int
	last     *remoteAttempt
}

type remoteWindowView struct {
	Code string `json:"code"`
	QR   string `json:"qr"`
	// Left is the seconds the code has left; Expires the same moment as a
	// Unix time by this device's clock.
	Left    int64 `json:"left"`
	Expires int64 `json:"expires"`
	// AttemptsLeft is how many more wrong guesses the code survives, and
	// LastWrong who made the latest one — a name from the tailnet, not
	// anything the caller claimed.
	AttemptsLeft int            `json:"attemptsLeft"`
	LastWrong    *remoteAttempt `json:"lastWrong,omitempty"`
}

// remoteOutcome is how the last code ended.
type remoteOutcome struct {
	// Result is "paired", "expired", "locked" (too many wrong guesses) or
	// "cancelled".
	Result string `json:"result"`
	Time   int64  `json:"time"`
	// For "paired" the device that paired; for "locked" the one whose guess
	// was the last.
	Name   string `json:"name,omitempty"`
	Login  string `json:"login,omitempty"`
	NodeID string `json:"nodeId,omitempty"`
	Class  string `json:"class,omitempty"`
}

func (m *remoteManager) windowDeadLocked(w *remoteWindow) bool {
	boot, up := m.clock.Boot()
	return boot != w.boot || up >= w.deadline
}

func (m *remoteManager) windowLeftLocked(w *remoteWindow) int64 {
	_, up := m.clock.Boot()
	left := int64((w.deadline - up + time.Second - 1) / time.Second)
	if left < 0 {
		left = 0
	}
	return left
}

func (m *remoteManager) windowViewLocked() *remoteWindowView {
	w := m.window
	if w == nil {
		return nil
	}
	left := m.windowLeftLocked(w)
	return &remoteWindowView{
		Code:         w.code,
		QR:           w.qr,
		Left:         left,
		Expires:      m.clock.Now().Unix() + left,
		AttemptsLeft: m.policy.CodeAttempts - w.wrong,
		LastWrong:    w.last,
	}
}

// closeWindowLocked withdraws the pending code, if any, and remembers why.
// Caller holds m.mu.
func (m *remoteManager) closeWindowLocked(result string) {
	if m.window == nil {
		return
	}
	m.window = nil
	m.outcome = &remoteOutcome{Result: result, Time: m.clock.Now().Unix()}
	m.rev++
}

func (m *remoteManager) startPairing() remoteCodeView {
	// The QR names this node, so the phone need not be told which peer to try.
	self, selfErr := m.selfIdentity()

	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.enabled || m.store == nil {
		return remoteCodeView{Error: "remote management is off"}
	}
	if selfErr != nil {
		return remoteCodeView{Error: "this device is not connected"}
	}
	code, err := m.newCode()
	if err != nil {
		slog.Error("Remote management: no random numbers for a pairing code", "err", err)
		return remoteCodeView{Error: "cannot make a code"}
	}
	q := url.Values{}
	q.Set("n", self.NodeID)
	q.Set("c", code)
	if self.Name != "" {
		q.Set("h", self.Name)
	}
	boot, up := m.clock.Boot()
	m.closeWindowLocked("cancelled")
	m.window = &remoteWindow{
		code:     code,
		qr:       remoteQRScheme + ":1?" + q.Encode(),
		deadline: up + m.policy.CodeTTL,
		boot:     boot,
	}
	if s := m.spent; s != nil && (s.code == code || m.windowDeadLocked(s)) {
		// One time in a million the new code is the one just spent; it is the
		// new code then.
		m.spent = nil
	}
	m.rev++
	// The code itself is on the screen and nowhere else.
	slog.Info("Remote management: a pairing code is on show", "seconds", int(m.policy.CodeTTL/time.Second))
	v := m.windowViewLocked()
	return remoteCodeView{OK: true, Code: v.Code, QR: v.QR, Expires: v.Expires, Left: v.Left}
}

// remotePaired is the answer to a POST /v1/pair that paired.
type remotePaired struct {
	// Token is returned this once and stored nowhere here.
	Token string `json:"token"`
	Class string `json:"class"`
	// Left is the pairing's lifetime in seconds, -1 for "until revoked";
	// Expires the same as a Unix time by this device's clock, 0 for never.
	Left    int64 `json:"left"`
	Expires int64 `json:"expires"`
	// NodeID and Name are this device's own, for the phone to file the token
	// under.
	NodeID string `json:"nodeId"`
	Name   string `json:"name"`
}

// remotePairRefusal is the answer to one that did not. Error is one of:
// closed (no code is on show), wrong, locked (that was the last guess the code
// allowed), used, not_allowed (the policy does not let this node pair), full,
// bad_request (not six digits), unavailable.
type remotePairRefusal struct {
	Error string `json:"error"`
	// AttemptsLeft comes with "wrong" and "locked".
	AttemptsLeft *int `json:"attemptsLeft,omitempty"`
}

// pair handles one pairing request from a caller whois has already resolved,
// and returns the HTTP status and body.
func (m *remoteManager) pair(c remoteCaller, self remoteSelf, code string) (int, any) {
	class := m.classOf(c, self)
	refuse := func(status int, why string) (int, any) {
		return status, remotePairRefusal{Error: why}
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.enabled || m.store == nil {
		return refuse(503, "unavailable")
	}
	m.expireLocked()
	m.adoptSelfLocked(self)
	if class == remoteNobody {
		// Before the code is even looked at: a node the policy shuts out
		// learns nothing about it and costs it nothing.
		return refuse(403, "not_allowed")
	}

	// Every pairing needs the code; see remotePolicy for why there is no way
	// round it for this account's own devices.
	w := m.window
	spent := m.spent != nil && !m.windowDeadLocked(m.spent) && remoteCodeEqual(code, m.spent.code)
	switch {
	case !remoteIsCode(code):
		// Not a guess at all, so not counted as one.
		return refuse(400, "bad_request")
	case spent:
		return refuse(409, "used")
	case w == nil:
		return refuse(403, "closed")
	case !remoteCodeEqual(code, w.code):
		w.wrong++
		left := m.policy.CodeAttempts - w.wrong
		if left < 0 {
			left = 0
		}
		w.last = &remoteAttempt{Name: c.Name, Login: c.Login, NodeID: c.NodeID, Time: m.clock.Now().Unix()}
		m.rev++
		ev := remoteEvent{NodeID: c.NodeID, Name: c.Name, Class: class.String(), Op: "pair_failed", Detail: "wrong code"}
		res := remotePairRefusal{Error: "wrong", AttemptsLeft: &left}
		if left == 0 {
			m.window = nil
			m.outcome = &remoteOutcome{Result: "locked", Time: w.last.Time, Name: c.Name, Login: c.Login, NodeID: c.NodeID}
			ev.Op, ev.Detail = "pair_locked", "too many wrong codes"
			res.Error = "locked"
			slog.Warn("Remote management: the pairing code is dead, too many wrong guesses", "last_from", c.Name, "node", c.NodeID)
		} else {
			slog.Warn("Remote management: wrong pairing code", "from", c.Name, "node", c.NodeID, "left", left)
		}
		m.auditLocked(ev)
		m.saveLocked()
		return 403, res
	}

	var before *remotePairing
	if old := m.store.find(c.NodeID); old != nil {
		cp := *old
		before = &cp
	} else if len(m.store.data.Pairings) >= m.policy.MaxPairings {
		// The code stays good: revoking a device on the screen makes room.
		return refuse(409, "full")
	}
	token, hash, err := remoteNewToken()
	if err != nil {
		slog.Error("Remote management: no random numbers for a token", "err", err)
		return refuse(500, "unavailable")
	}
	p := remotePairing{
		NodeID: c.NodeID, UserID: c.UserID, Login: c.Login, Name: c.Name,
		Class: class.String(), TokenSHA256: hash,
	}
	ttl := m.policy.GuestTTL
	if class == remoteOwner {
		ttl = m.policy.OwnerTTL
	}
	m.store.arm(&p, ttl)
	m.store.put(p)
	wasUnsaved := m.store.unsaved
	if !m.saveLockedWith(remoteEvent{NodeID: c.NodeID, Name: c.Name, Class: p.Class, Op: "paired", OK: true}) {
		// Nothing was written, so nothing was granted: put the list back and
		// leave the code usable.
		if before != nil {
			m.store.put(*before)
		} else {
			m.store.remove(c.NodeID)
		}
		m.store.unsaved = wasUnsaved
		return refuse(500, "unavailable")
	}
	m.spent, m.window = w, nil
	m.outcome = &remoteOutcome{Result: "paired", Time: p.Created, Name: c.Name, Login: c.Login, NodeID: c.NodeID, Class: p.Class}
	m.rev++
	slog.Info("Remote management: paired", "device", c.Name, "node", c.NodeID, "class", p.Class)

	res := remotePaired{Token: token, Class: p.Class, Left: m.store.left(m.store.find(c.NodeID)), NodeID: self.NodeID, Name: self.Name}
	if res.Left >= 0 {
		res.Expires = m.clock.Now().Unix() + res.Left
	}
	return 200, res
}

// saveLockedWith records ev and saves; when the save fails the event is taken
// back too, so the log does not claim what did not happen.
func (m *remoteManager) saveLockedWith(ev remoteEvent) bool {
	m.auditLocked(ev)
	if m.saveLocked() {
		return true
	}
	if n := len(m.store.data.Audit); n > 0 {
		m.store.data.Audit = m.store.data.Audit[:n-1]
	}
	return false
}

// adoptSelfLocked ties the store to this node's identity, and empties a store
// that belongs to another: a new login in the same profile, or the app's data
// copied to another device.
func (m *remoteManager) adoptSelfLocked(self remoteSelf) {
	if m.store == nil || self.NodeID == "" || m.store.data.Self == self.NodeID {
		return
	}
	if m.store.data.Self != "" && len(m.store.data.Pairings) > 0 {
		n := len(m.store.data.Pairings)
		m.store.data.Pairings = nil
		m.auditLocked(remoteEvent{Op: "reset", Detail: "this device joined as another node; its pairings were removed"})
		slog.Warn("Remote management: this node's identity changed, pairings removed", "count", n)
	}
	m.store.data.Self = self.NodeID
	m.saveRemovalLocked()
}

// remoteCodeEqual compares two pairing codes in constant time.
func remoteCodeEqual(a, b string) bool {
	return len(a) == len(b) && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// remoteParsedQR is what RemoteParseQR returns.
type remoteParsedQR struct {
	OK bool `json:"ok"`
	// NodeID is the StableNodeID of the device that showed the code; Name is
	// what it calls itself, for the confirmation line only.
	NodeID string `json:"nodeId,omitempty"`
	Code   string `json:"code,omitempty"`
	Name   string `json:"name,omitempty"`
}

// RemoteParseQR reads the text of a pairing QR code (RemoteStartPairing's
// "qr") on the phone and returns {"ok":true,"nodeId","code","name"}, or
// {"ok":false} for anything else. The node id is what RemotePair takes; it
// must be one of this phone's own peers for the pairing to go anywhere.
func RemoteParseQR(text string) string {
	return remoteReply(remoteParseQR(text))
}

func remoteParseQR(text string) remoteParsedQR {
	const prefix = remoteQRScheme + ":1?"
	if len(text) > 512 || len(text) <= len(prefix) || text[:len(prefix)] != prefix {
		return remoteParsedQR{}
	}
	q, err := url.ParseQuery(text[len(prefix):])
	if err != nil {
		return remoteParsedQR{}
	}
	node, code := q.Get("n"), q.Get("c")
	if !remoteIsNodeID(node) || !remoteIsCode(code) {
		return remoteParsedQR{}
	}
	return remoteParsedQR{OK: true, NodeID: node, Code: code, Name: remoteClean(q.Get("h"), 64)}
}

// remoteIsNodeID reports whether s could be a StableNodeID: short, and nothing
// that would mean something in a URL, a path or a log line.
func remoteIsNodeID(s string) bool {
	if s == "" || len(s) > 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
