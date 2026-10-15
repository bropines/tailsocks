package appctr

// remote_store.go — the pairings of remote management, on disk.
//
// One JSON file per profile, files/remote/<profile>.json, mode 0600 in a 0700
// directory, replaced atomically. It holds verifiers, not secrets: the SHA-256
// of each device's token, never the token. A file that cannot be read or
// parsed is set aside as <name>.corrupt and the list starts empty — a damaged
// store must not let anyone in.
//
// The file is what the next start of the app believes, so the two directions
// of a change are written differently. A grant is on disk or it is not given:
// when the atomic replace fails the pairing is taken back. A removal — a
// revocation, an expiry — must not come back, so when the replace fails the
// file is overwritten in place, and failing that deleted: either costs the
// atomicity or the other pairings, never the removal. When even that is
// impossible the caller is told ("not_saved"), the list in memory holds, the
// write is tried again at every opportunity, and a file that cannot be opened
// for writing is not believed when it is read. One case is left open by all
// of this: nothing could be written, the app's process ended before a retry
// got through, and by its next start the disk works again — then the old file
// is read as if the removal had never been asked for. "not_saved" is the
// warning that this can still happen.
//
// Expiry and the clock. A TV's wall clock is the least trustworthy thing on
// it: no battery behind it, wrong until the first time sync, free to jump. So
// a deadline is kept three ways and they are used in this order:
//
//   - Within one boot the deadline is a reading of the boot-time clock
//     (CLOCK_BOOTTIME: monotonic, and it keeps counting while the device
//     sleeps). No change of the wall clock, in either direction, moves it.
//   - Across a reboot that clock starts over, so the wall clock has to be
//     consulted once: the first time an entry is seen in a new boot, what it
//     has left is taken from its wall-clock deadline — but never more than it
//     had left when the file was last written. A clock that is behind
//     therefore cannot extend a pairing, and time spent switched off counts
//     whenever the clock is right. A clock that is ahead ends guest pairings
//     early, which is the safe direction. From then on the boot-time clock
//     decides again.
//   - "What it had left when the file was last written" is refreshed on every
//     save, and at least every remoteTouchEvery while any pairing is in use.
//
// What this does not survive: after a reboot with the clock behind, a guest
// gets back the time since the file was last written — the time the device
// was off, plus at most remoteTouchEvery if a pairing was in use, or however
// long they all sat idle — and never more than the pairing's own term. Doing
// that on purpose takes the device in one's hands, where one could just as
// well pair again. Pairings that never expire (owners, by default) are
// untouched by any of this.

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
)

const (
	remoteStoreVersion = 1
	remoteAuditMax     = 100
	// remoteTouchEvery is how often a store in use is rewritten just to
	// refresh "seconds left" and "last used".
	remoteTouchEvery = 10 * time.Minute
)

// remoteClock is the two clocks expiry needs. Boot identifies the current
// boot and says how long it has been running, sleep included.
type remoteClock interface {
	Now() time.Time
	Boot() (id string, up time.Duration)
}

// remotePairing is one device that may manage this one.
type remotePairing struct {
	NodeID string `json:"nodeId"` // the device's StableNodeID: the key
	UserID int64  `json:"userId"` // its user when it paired; a transferred node no longer matches
	Login  string `json:"login,omitempty"`
	Name   string `json:"name"`  // the device's name when it paired
	Class  string `json:"class"` // "owner" or "guest"
	// Created and Expires are Unix seconds on the wall clock, for people;
	// Expires is 0 for "until revoked".
	Created  int64 `json:"created"`
	Expires  int64 `json:"expires"`
	LastUsed int64 `json:"lastUsed,omitempty"`
	// TokenSHA256 is the hex SHA-256 of the token the device holds.
	TokenSHA256 string `json:"tokenSha256"`
	// The deadline as the boot-time clock has it — seconds since the boot
	// named by Boot — and the seconds left when the file was last written.
	Boot         string `json:"boot,omitempty"`
	BootDeadline int64  `json:"bootDeadline,omitempty"`
	Left         int64  `json:"left,omitempty"`
	// Elevation is a guest's full rights for a while, given at the device
	// (remote_elevate.go); nil when it has none.
	Elevation *remoteElevation `json:"elevation,omitempty"`
}

// remoteElevation is how long a guest's full rights last, kept the way a
// pairing's deadline is (see the note at the top) and by the wall clock as
// well: Granted and Seconds say when they were given and for how long, so they
// end at Granted+Seconds, and a clock set back never makes them last longer
// than Seconds. Whichever of the two ends first ends them.
type remoteElevation struct {
	// Granted is Unix seconds on the wall clock; Seconds the length given.
	Granted int64 `json:"granted"`
	Seconds int64 `json:"seconds"`
	// Full: the full-control tier is part of them, whatever the device's own
	// switch says.
	Full bool `json:"full,omitempty"`
	// NoFiles: given while the device's network access to files was off, they
	// never include the files (remote_files.go). Absent in a store written
	// before the switch existed: such rights follow the switch.
	NoFiles      bool   `json:"noFiles,omitempty"`
	Boot         string `json:"boot,omitempty"`
	BootDeadline int64  `json:"bootDeadline,omitempty"`
	Left         int64  `json:"left,omitempty"`
}

// remoteEvent is one line of the action log.
type remoteEvent struct {
	Time   int64  `json:"time"`
	NodeID string `json:"nodeId,omitempty"`
	Name   string `json:"name,omitempty"`
	Class  string `json:"class,omitempty"`
	Op     string `json:"op"`
	Detail string `json:"detail,omitempty"`
	OK     bool   `json:"ok"`
}

type remoteStoreData struct {
	Version int `json:"version"`
	// Self is the StableNodeID of this node when the pairings were made. They
	// belong to that identity: a store that turns up under another node — a
	// new login, a device transfer — is emptied.
	Self     string          `json:"self,omitempty"`
	Pairings []remotePairing `json:"pairings"`
	Audit    []remoteEvent   `json:"audit,omitempty"`
	// Guard is the change that waits for its confirmation (remote_guard.go):
	// kept here so that an app that is restarted meanwhile still puts it back.
	Guard *remoteStoredGuard `json:"guard,omitempty"`
}

type remoteStore struct {
	path    string
	clock   remoteClock
	data    remoteStoreData
	savedAt time.Duration // boot-clock reading of the last save
	// unsaved: the last write did not put the list in memory on disk. owed: it
	// was a removal, and the file still has what was removed. triedAt spaces
	// the retries.
	unsaved bool
	owed    bool
	triedAt time.Duration
}

// remoteRetryEvery spaces the retries of a write that failed.
const remoteRetryEvery = 5 * time.Second

// openRemoteStore reads the store at path, or starts an empty one when there
// is none, it is damaged, or it cannot be written.
func openRemoteStore(path string, clock remoteClock) *remoteStore {
	s := &remoteStore{path: path, clock: clock, data: remoteStoreData{Version: remoteStoreVersion}}
	_, s.savedAt = clock.Boot()
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s
	}
	if err == nil {
		err = s.decode(raw)
	}
	if err != nil {
		slog.Error("Remote management: the pairings file is unusable, starting with none", "err", err)
		_ = os.Rename(path, path+".corrupt")
		s.data = remoteStoreData{Version: remoteStoreVersion}
		s.audit(remoteEvent{Time: clock.Now().Unix(), Op: "reset", Detail: "the pairings file was unreadable"})
		return s
	}
	// A file that cannot be written cannot have recorded the last revocation
	// or expiry either; what it says is not taken as current.
	if f, err := os.OpenFile(path, os.O_WRONLY, 0); err != nil {
		slog.Error("Remote management: the pairings file cannot be written, starting with none", "err", err)
		s.data = remoteStoreData{Version: remoteStoreVersion, Self: s.data.Self, Audit: s.data.Audit}
		s.audit(remoteEvent{Time: clock.Now().Unix(), Op: "reset", Detail: "the pairings file could not be written"})
		s.unsaved = true
		return s
	} else {
		_ = f.Close()
	}
	s.rebase()
	return s
}

// decode parses raw and keeps only entries that are whole. Any doubt about the
// file as a whole is an error; a single malformed entry is dropped.
func (s *remoteStore) decode(raw []byte) error {
	var d remoteStoreData
	if err := json.Unmarshal(raw, &d); err != nil {
		return err
	}
	if d.Version != remoteStoreVersion {
		return fmt.Errorf("version %d, want %d", d.Version, remoteStoreVersion)
	}
	seen := map[string]bool{}
	kept := d.Pairings[:0]
	for _, p := range d.Pairings {
		if p.NodeID == "" || seen[p.NodeID] || remoteClassOf(p.Class) == remoteNobody || !remoteIsTokenHash(p.TokenSHA256) {
			slog.Warn("Remote management: dropping a malformed pairing", "node", remoteClean(p.NodeID, 64))
			continue
		}
		seen[p.NodeID] = true
		if e := p.Elevation; e != nil && (remoteClassOf(p.Class) != remoteGuest || e.Seconds <= 0 || e.Seconds > int64(remoteElevateMax/time.Second)) {
			// Full rights are a guest's, for a day at most: anything else is
			// not what this device gave.
			p.Elevation = nil
		}
		p.Name = remoteClean(p.Name, 64)
		p.Login = remoteClean(p.Login, 128)
		kept = append(kept, p)
	}
	d.Pairings = kept
	if len(d.Audit) > remoteAuditMax {
		d.Audit = d.Audit[len(d.Audit)-remoteAuditMax:]
	}
	// A refusal used to be logged as a sentence; the app has words for the
	// reason's name only.
	for i := range d.Audit {
		if d.Audit[i].Op == "denied" {
			d.Audit[i].Detail = remoteDenialOfSentence(d.Audit[i].Detail)
		}
	}
	s.data = d
	return nil
}

// rebase moves the deadlines of entries from another boot onto this boot's
// clock; see the note at the top.
func (s *remoteStore) rebase() {
	boot, up := s.clock.Boot()
	now := s.clock.Now().Unix()
	upSec := int64(up / time.Second)
	for i := range s.data.Pairings {
		p := &s.data.Pairings[i]
		if e := p.Elevation; e != nil && e.Boot != boot {
			left := remoteElevationWallLeft(e, now)
			if left > e.Left {
				left = e.Left
			}
			if left < 0 {
				left = 0
			}
			e.Boot, e.BootDeadline, e.Left = boot, upSec+left, left
		}
		if p.Expires == 0 || p.Boot == boot {
			continue
		}
		left := p.Expires - now
		if left > p.Left {
			left = p.Left
		}
		if left < 0 {
			left = 0
		}
		p.Boot = boot
		p.BootDeadline = upSec + left
		p.Left = left
	}
}

// remoteElevationWallLeft is what e has left by the wall clock alone: never
// more than its length, whatever the clock says.
func remoteElevationWallLeft(e *remoteElevation, now int64) int64 {
	left := e.Granted + e.Seconds - now
	if left > e.Seconds {
		left = e.Seconds
	}
	return left
}

// elevate gives p full rights for seconds from now, in place of any it had.
func (s *remoteStore) elevate(p *remotePairing, seconds int64, full, noFiles bool) {
	boot, up := s.clock.Boot()
	p.Elevation = &remoteElevation{
		Granted: s.clock.Now().Unix(), Seconds: seconds, Full: full, NoFiles: noFiles,
		Boot: boot, BootDeadline: int64(up/time.Second) + seconds, Left: seconds,
	}
}

// elevationLeft is the seconds e has left — the earlier of the boot-time
// deadline and the wall clock's — and 0 when it is over or there is none.
func (s *remoteStore) elevationLeft(e *remoteElevation) int64 {
	if e == nil {
		return 0
	}
	boot, up := s.clock.Boot()
	if e.Boot != boot {
		s.rebase()
	}
	left := e.BootDeadline - int64(up/time.Second)
	if wall := remoteElevationWallLeft(e, s.clock.Now().Unix()); wall < left {
		left = wall
	}
	if left < 0 {
		left = 0
	}
	return left
}

// arm gives p a lifetime of ttl from now; 0 is until revoked.
func (s *remoteStore) arm(p *remotePairing, ttl time.Duration) {
	now := s.clock.Now()
	p.Created = now.Unix()
	p.Expires, p.Boot, p.BootDeadline, p.Left = 0, "", 0, 0
	if ttl <= 0 {
		return
	}
	boot, up := s.clock.Boot()
	p.Expires = now.Add(ttl).Unix()
	p.Boot = boot
	p.BootDeadline = int64((up + ttl) / time.Second)
	p.Left = int64(ttl / time.Second)
}

// left is the seconds p has left: -1 for "until revoked", 0 when it is over.
func (s *remoteStore) left(p *remotePairing) int64 {
	if p.Expires == 0 {
		return -1
	}
	boot, up := s.clock.Boot()
	if p.Boot != boot {
		// Only an entry added behind rebase's back; treat it as rebase would.
		s.rebase()
	}
	left := p.BootDeadline - int64(up/time.Second)
	if left < 0 {
		left = 0
	}
	return left
}

// expire removes the pairings whose time is up and returns them.
func (s *remoteStore) expire() []remotePairing {
	var gone []remotePairing
	kept := s.data.Pairings[:0]
	for i := range s.data.Pairings {
		p := s.data.Pairings[i]
		if s.left(&s.data.Pairings[i]) == 0 {
			gone = append(gone, p)
			continue
		}
		kept = append(kept, s.data.Pairings[i])
	}
	s.data.Pairings = kept
	return gone
}

func (s *remoteStore) find(nodeID string) *remotePairing {
	if nodeID == "" {
		return nil
	}
	for i := range s.data.Pairings {
		if s.data.Pairings[i].NodeID == nodeID {
			return &s.data.Pairings[i]
		}
	}
	return nil
}

// put adds p, replacing the entry of the same node: a device that pairs again
// gets a new token and the old one stops working.
func (s *remoteStore) put(p remotePairing) {
	if old := s.find(p.NodeID); old != nil {
		*old = p
		return
	}
	s.data.Pairings = append(s.data.Pairings, p)
}

func (s *remoteStore) remove(nodeID string) bool {
	for i := range s.data.Pairings {
		if s.data.Pairings[i].NodeID == nodeID {
			s.data.Pairings = append(s.data.Pairings[:i], s.data.Pairings[i+1:]...)
			return true
		}
	}
	return false
}

func (s *remoteStore) audit(ev remoteEvent) {
	ev.Name = remoteClean(ev.Name, 64)
	ev.Detail = remoteClean(ev.Detail, 160)
	s.data.Audit = append(s.data.Audit, ev)
	if len(s.data.Audit) > remoteAuditMax {
		s.data.Audit = s.data.Audit[len(s.data.Audit)-remoteAuditMax:]
	}
}

// save writes the list in memory to disk and reports whether the caller's
// change is there to stay.
//
// The normal way is a temporary file beside the store, synced and renamed over
// it, so a crash leaves the old list or the new one. When that fails a grant
// (mustLand false) has simply not been written, and the caller takes it back.
// A removal (mustLand true) is then forced: the file is overwritten in place,
// and if it cannot even be opened, deleted. Both lose something — atomicity,
// or every pairing at the next start — but not the removal; nil then means
// "what was removed will not come back", and unsaved says the rest has still
// to be written.
func (s *remoteStore) save(mustLand bool) error {
	_, up := s.clock.Boot()
	s.triedAt = up
	for i := range s.data.Pairings {
		p := &s.data.Pairings[i]
		if p.Expires != 0 {
			p.Left = s.left(p)
		}
		if e := p.Elevation; e != nil {
			e.Left = s.elevationLeft(e)
		}
	}
	if s.data.Pairings == nil {
		s.data.Pairings = []remotePairing{}
	}
	s.data.Version = remoteStoreVersion
	raw, err := json.MarshalIndent(s.data, "", "\t")
	if err != nil {
		s.unsaved = true
		return err
	}
	if err = s.replace(raw); err == nil {
		s.savedAt, s.unsaved, s.owed = up, false, false
		return nil
	}
	s.unsaved = true
	if !mustLand && !s.owed {
		return err
	}
	f, openErr := os.OpenFile(s.path, os.O_WRONLY|os.O_TRUNC, 0o600)
	switch {
	case openErr == nil:
		// The old list is gone from the file whatever happens next; a write
		// that stops half-way leaves a file the next start will not parse.
		_, werr := f.Write(raw)
		if serr := f.Sync(); werr == nil {
			werr = serr
		}
		if cerr := f.Close(); werr == nil {
			werr = cerr
		}
		if werr == nil {
			s.savedAt, s.unsaved = up, false
		}
		s.owed = false
		return nil
	case errors.Is(openErr, os.ErrNotExist):
		s.owed = false
		return nil // nothing on disk to come back
	}
	if rmErr := os.Remove(s.path); rmErr == nil || errors.Is(rmErr, os.ErrNotExist) {
		s.owed = false
		return nil
	}
	// Nothing could be done to the file. The removal is owed: every later
	// write is forced the same way until one lands.
	s.owed = true
	return err
}

// replace puts raw in the store's place atomically.
func (s *remoteStore) replace(raw []byte) error {
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, filepath.Base(s.path)+".*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(raw); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp.Name(), s.path); err != nil {
		return err
	}
	// The rename itself has to reach the disk: a revocation that a power cut
	// takes back would let the revoked device in again. Best effort — not
	// every filesystem lets a directory be synced.
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}

// flush retries a write that failed, no more often than remoteRetryEvery.
func (s *remoteStore) flush() {
	if !s.unsaved {
		return
	}
	if _, up := s.clock.Boot(); up-s.triedAt < remoteRetryEvery && up >= s.triedAt {
		return
	}
	if err := s.save(false); err != nil {
		slog.Warn("Remote management: the pairings are still not on disk", "err", err)
	}
}

// stale reports whether the file is older than remoteTouchEvery.
func (s *remoteStore) stale() bool {
	_, up := s.clock.Boot()
	return up-s.savedAt >= remoteTouchEvery
}

// ─── Tokens and codes ───────────────────────────────────────────────────────

// remoteNewToken returns a fresh token — 32 bytes from the system's CSPRNG,
// base64url — and the hash the store keeps.
func remoteNewToken() (token, hash string, err error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(b[:])
	return token, remoteHashToken(token), nil
}

func remoteHashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func remoteIsTokenHash(s string) bool {
	if len(s) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

// remoteTokenMatches compares a presented token with a stored hash without
// letting the time taken say how much of it matched.
func remoteTokenMatches(token, hash string) bool {
	if token == "" || len(token) > 128 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(remoteHashToken(token)), []byte(hash)) == 1
}

// remoteNewCode returns six decimal digits, uniform over 000000–999999.
func remoteNewCode() (string, error) {
	// 2^32 is not a multiple of 10^6, so the values above the last whole
	// multiple are thrown away rather than folded in.
	const span = 1_000_000
	const limit = (1 << 32) / span * span
	for {
		var b [4]byte
		if _, err := rand.Read(b[:]); err != nil {
			return "", err
		}
		v := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
		if uint64(v) < limit {
			return fmt.Sprintf("%06d", v%span), nil
		}
	}
}

// remoteIsCode reports whether s has the shape of a pairing code.
func remoteIsCode(s string) bool {
	if len(s) != 6 {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// remoteClean makes a string from the network fit to store, log and show: no
// control or formatting characters, at most max runes.
func remoteClean(s string, max int) string {
	var b strings.Builder
	n := 0
	for _, r := range s {
		if n >= max {
			break
		}
		if r == unicode.ReplacementChar || unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			continue
		}
		b.WriteRune(r)
		n++
	}
	return strings.TrimSpace(b.String())
}
