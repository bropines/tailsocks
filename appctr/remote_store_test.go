package appctr

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// remoteFakeClock is a wall clock and a boot-time clock that only move when a
// test moves them.
type remoteFakeClock struct {
	mu    sync.Mutex
	wall  time.Time
	boot  string
	boots int
	up    time.Duration
}

func newRemoteFakeClock() *remoteFakeClock {
	return &remoteFakeClock{wall: time.Date(2026, 10, 13, 20, 0, 0, 0, time.UTC), boot: "boot-0", up: time.Hour}
}

func (c *remoteFakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.wall
}

func (c *remoteFakeClock) Boot() (string, time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.boot, c.up
}

// advance lets time pass: both clocks move.
func (c *remoteFakeClock) advance(d time.Duration) {
	c.mu.Lock()
	c.wall = c.wall.Add(d)
	c.up += d
	c.mu.Unlock()
}

// jump moves the wall clock alone, as a time sync or a person does.
func (c *remoteFakeClock) jump(d time.Duration) {
	c.mu.Lock()
	c.wall = c.wall.Add(d)
	c.mu.Unlock()
}

// reboot starts a new boot after the device was off for a while.
func (c *remoteFakeClock) reboot(off time.Duration) {
	c.mu.Lock()
	c.boots++
	c.boot = fmt.Sprintf("boot-%d", c.boots)
	c.up = 20 * time.Second
	c.wall = c.wall.Add(off)
	c.mu.Unlock()
}

func remoteTestStore(t *testing.T, clock remoteClock) *remoteStore {
	t.Helper()
	return openRemoteStore(filepath.Join(t.TempDir(), "remote", "p.json"), clock)
}

// A deadline is a reading of the boot-time clock; the wall clock is asked once
// per boot, and can only take time away.
func TestRemoteStoreDeadlines(t *testing.T) {
	clock := newRemoteFakeClock()
	s := remoteTestStore(t, clock)
	guest := remotePairing{NodeID: "nGUEST", Class: "guest", TokenSHA256: remoteHashToken("g")}
	owner := remotePairing{NodeID: "nOWNER", Class: "owner", TokenSHA256: remoteHashToken("o")}
	s.arm(&guest, 24*time.Hour)
	s.arm(&owner, 0)
	s.put(guest)
	s.put(owner)
	left := func(s *remoteStore, id string) int64 { return s.left(s.find(id)) }

	if l := left(s, "nGUEST"); l != 86400 {
		t.Fatalf("a fresh day has %d s", l)
	}
	if l := left(s, "nOWNER"); l != -1 {
		t.Fatalf("no term reads as %d", l)
	}
	if g := s.find("nGUEST"); g.Created != clock.Now().Unix() || g.Expires != g.Created+86400 {
		t.Errorf("wall-clock dates: %+v", g)
	}
	// Within the boot nothing the wall clock does matters.
	clock.advance(6 * time.Hour)
	clock.jump(-1000 * time.Hour)
	if l := left(s, "nGUEST"); l != 18*3600 {
		t.Errorf("after a jump back: %d", l)
	}
	clock.jump(5000 * time.Hour)
	if l := left(s, "nGUEST"); l != 18*3600 {
		t.Errorf("after a jump ahead: %d", l)
	}
	if gone := s.expire(); len(gone) != 0 {
		t.Errorf("expired early: %+v", gone)
	}
	clock.jump(-4000 * time.Hour) // right again
	if err := s.save(); err != nil {
		t.Fatal(err)
	}

	// The same boot, read back by another process: nothing to convert.
	again := openRemoteStore(s.path, clock)
	if l := left(again, "nGUEST"); l != 18*3600 {
		t.Errorf("read back in the same boot: %d", l)
	}

	// Another boot, eight hours later by a clock that is right.
	clock.reboot(8 * time.Hour)
	again = openRemoteStore(s.path, clock)
	if l := left(again, "nGUEST"); l != 10*3600 {
		t.Errorf("after eight hours off: %d", l)
	}
	// Another boot with the clock a year behind: what the file said, no more.
	clock.reboot(-365 * 24 * time.Hour)
	again = openRemoteStore(s.path, clock)
	if l := left(again, "nGUEST"); l != 18*3600 {
		t.Errorf("with the clock behind: %d, want what was left at the last save", l)
	}
	// Another boot with the clock far ahead: over.
	clock.reboot(3 * 365 * 24 * time.Hour)
	again = openRemoteStore(s.path, clock)
	if gone := again.expire(); len(gone) != 1 || gone[0].NodeID != "nGUEST" {
		t.Errorf("with the clock ahead, expired: %+v", gone)
	}
	if l := left(again, "nOWNER"); l != -1 || len(again.data.Pairings) != 1 {
		t.Errorf("the pairing without a term: %d, list %+v", l, again.data.Pairings)
	}
}

// A device that pairs again replaces its entry; removal is by node.
func TestRemoteStoreEntries(t *testing.T) {
	s := remoteTestStore(t, newRemoteFakeClock())
	s.put(remotePairing{NodeID: "nA", Class: "owner", Name: "first", TokenSHA256: remoteHashToken("1")})
	s.put(remotePairing{NodeID: "nB", Class: "guest", Name: "other", TokenSHA256: remoteHashToken("2")})
	s.put(remotePairing{NodeID: "nA", Class: "owner", Name: "second", TokenSHA256: remoteHashToken("3")})
	if len(s.data.Pairings) != 2 || s.find("nA").Name != "second" || s.find("nA").TokenSHA256 != remoteHashToken("3") {
		t.Fatalf("after pairing again: %+v", s.data.Pairings)
	}
	if s.find("") != nil || s.find("nC") != nil {
		t.Error("found a pairing that is not there")
	}
	if s.remove("nC") || !s.remove("nA") || s.remove("nA") || len(s.data.Pairings) != 1 || s.data.Pairings[0].NodeID != "nB" {
		t.Errorf("after removing: %+v", s.data.Pairings)
	}
	// An empty list is written as a list.
	s.remove("nB")
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(s.path)
	if !strings.Contains(string(raw), `"pairings": []`) {
		t.Errorf("file: %s", raw)
	}
}

// One bad entry costs that entry, not the list.
func TestRemoteStoreMalformedEntries(t *testing.T) {
	hash := remoteHashToken("a token")
	d := remoteStoreData{
		Version: remoteStoreVersion,
		Self:    "nSELF",
		Pairings: []remotePairing{
			{NodeID: "nGOOD", UserID: 1, Name: "good", Class: "owner", TokenSHA256: hash},
			{NodeID: "", UserID: 1, Name: "no id", Class: "owner", TokenSHA256: hash},
			{NodeID: "nADMIN", UserID: 1, Name: "made-up class", Class: "admin", TokenSHA256: hash},
			{NodeID: "nNOCLASS", UserID: 1, Name: "no class", TokenSHA256: hash},
			{NodeID: "nNOHASH", UserID: 1, Name: "no hash", Class: "owner"},
			{NodeID: "nSHORT", UserID: 1, Name: "short hash", Class: "owner", TokenSHA256: hash[:40]},
			{NodeID: "nNOTHEX", UserID: 1, Name: "not hex", Class: "owner", TokenSHA256: strings.Repeat("z", 64)},
			{NodeID: "nGOOD", UserID: 2, Name: "a second entry for the same node", Class: "owner", TokenSHA256: remoteHashToken("other")},
			{NodeID: "nGUEST", UserID: 2, Name: "guest\x1b[31m‮", Class: "guest", TokenSHA256: hash, Expires: 1},
		},
	}
	for i := 0; i < 3*remoteAuditMax; i++ {
		d.Audit = append(d.Audit, remoteEvent{Time: int64(i), Op: "setting"})
	}
	raw, _ := json.Marshal(d)
	path := filepath.Join(t.TempDir(), "remote", "p.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	s := openRemoteStore(path, newRemoteFakeClock())
	if len(s.data.Pairings) != 2 || s.data.Pairings[0].NodeID != "nGOOD" || s.data.Pairings[0].UserID != 1 || s.data.Pairings[1].NodeID != "nGUEST" {
		t.Fatalf("kept: %+v", s.data.Pairings)
	}
	if s.data.Pairings[1].Name != "guest[31m" {
		t.Errorf("a name with control characters was kept as %q", s.data.Pairings[1].Name)
	}
	// An entry with a deadline in the past and nothing left is gone at once.
	if gone := s.expire(); len(gone) != 1 || gone[0].NodeID != "nGUEST" {
		t.Errorf("expired: %+v", gone)
	}
	if len(s.data.Audit) != remoteAuditMax || s.data.Audit[0].Time != int64(2*remoteAuditMax) {
		t.Errorf("log: %d entries, first %+v", len(s.data.Audit), s.data.Audit[0])
	}
	if s.data.Self != "nSELF" {
		t.Errorf("self = %q", s.data.Self)
	}
}

// The log is short and stays short.
func TestRemoteStoreAuditCap(t *testing.T) {
	s := remoteTestStore(t, newRemoteFakeClock())
	for i := 0; i < 250; i++ {
		s.audit(remoteEvent{Time: int64(i), Op: "setting", Name: "phone\nwith a newline", Detail: strings.Repeat("d", 500)})
	}
	if len(s.data.Audit) != remoteAuditMax || s.data.Audit[0].Time != 150 {
		t.Fatalf("%d entries, first at %d", len(s.data.Audit), s.data.Audit[0].Time)
	}
	if e := s.data.Audit[0]; e.Name != "phonewith a newline" || len(e.Detail) != 160 {
		t.Errorf("entry not cleaned: %q, detail %d long", e.Name, len(e.Detail))
	}
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	if again := openRemoteStore(s.path, newRemoteFakeClock()); len(again.data.Audit) != remoteAuditMax {
		t.Errorf("read back %d entries", len(again.data.Audit))
	}
}

func TestRemoteToken(t *testing.T) {
	token, hash, err := remoteNewToken()
	if err != nil {
		t.Fatal(err)
	}
	if len(token) != 43 || !remoteIsTokenHash(hash) {
		t.Fatalf("token %q hash %q", token, hash)
	}
	other, _, _ := remoteNewToken()
	if token == other {
		t.Fatal("two tokens are the same")
	}
	if !remoteTokenMatches(token, hash) {
		t.Error("the token does not match its own hash")
	}
	for _, bad := range []string{"", other, token + "x", token[:42], hash, strings.Repeat("A", 4096)} {
		if remoteTokenMatches(bad, hash) {
			t.Errorf("%q matched", bad)
		}
	}
	for _, notHash := range []string{"", hash[:63], hash + "0", strings.Repeat("g", 64)} {
		if remoteIsTokenHash(notHash) {
			t.Errorf("%q passes for a hash", notHash)
		}
	}
}

func TestRemoteCode(t *testing.T) {
	seen := map[string]bool{}
	leadingZero := false
	var digits [10]int
	for i := 0; i < 5000; i++ {
		c, err := remoteNewCode()
		if err != nil {
			t.Fatal(err)
		}
		if !remoteIsCode(c) {
			t.Fatalf("code %q", c)
		}
		seen[c] = true
		leadingZero = leadingZero || c[0] == '0'
		for _, d := range c {
			digits[d-'0']++
		}
	}
	// 5000 draws from a million: a handful of repeats at most, every digit
	// about a tenth of the 30000 positions, and codes that start with 0.
	if len(seen) < 4900 {
		t.Errorf("only %d different codes in 5000", len(seen))
	}
	if !leadingZero {
		t.Error("no code starts with 0")
	}
	for d, n := range digits {
		if n < 2500 || n > 3500 {
			t.Errorf("digit %d appears %d times in 30000", d, n)
		}
	}
	for _, bad := range []string{"", "12345", "1234567", "12345a", "12 456", "-12345", "١٢٣٤٥٦"} {
		if remoteIsCode(bad) {
			t.Errorf("%q passes for a code", bad)
		}
	}
}

func TestRemoteClean(t *testing.T) {
	for in, want := range map[string]string{
		"  pixel  ":        "pixel",
		"line\nbreak\ttab": "linebreaktab",
		"esc\x1b[31mred":   "esc[31mred",
		"rtl‮override":     "rtloverride",
		"bad\xffbyte":      "badbyte",
		"zero​width":       "zerowidth",
		"Телевизор в зале": "Телевизор в зале",
	} {
		if got := remoteClean(in, 16); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
	if got := remoteClean(strings.Repeat("я", 9), 5); got != "яяяяя" {
		t.Errorf("cut to %q", got)
	}
}

// The real clocks: a boot that has an identity and an uptime that moves the
// right way.
func TestRemoteSystemClock(t *testing.T) {
	var c remoteSystemClock
	id, up := c.Boot()
	if id == "" || up <= 0 {
		t.Fatalf("boot %q, up %v", id, up)
	}
	time.Sleep(20 * time.Millisecond)
	again, later := c.Boot()
	if again != id || later <= up || later-up > 5*time.Second {
		t.Errorf("boot %q then %q, up %v then %v", id, again, up, later)
	}
	if d := time.Since(c.Now()); d < 0 || d > time.Second {
		t.Errorf("wall clock off by %v", d)
	}
	if bt, ok := remoteBootTime(); ok && (bt < up || bt-up > 5*time.Second) {
		t.Errorf("boot-time clock %v against uptime %v", bt, up)
	}
}
