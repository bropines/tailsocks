package appctr

// events.go — tells the app that the daemon's state changed, so that it reads
// it when it changed instead of asking every two seconds whether it did.
//
// The bus listener (bus.go) already holds the one long-lived watch-ipn-bus
// subscription and keeps the latest state in memory. After every notification
// it compares four small signatures of that state — backend state, health,
// the prefs the app shows, the peer set — with the ones it last published,
// and hands the kinds that differ to the dispatcher below. The dispatcher's
// own goroutine tells the app's DaemonEventListener: by kind and sequence
// number only, never with a payload. The app then reads what it needs through
// the getters it always used (GetBusState, GetHealthWarningsJSON, the status),
// so the bus snapshot stays the one source of truth and the upcall stays tiny.
//
// Delivery rules, each of which a test holds:
//   - Nothing calls into the app under a bridge lock or on the bus-reading
//     goroutine: publish only marks a kind pending and wakes the dispatcher.
//   - Latest wins per kind: at most one pending event per kind, however many
//     changes came in while the listener was busy.
//   - netmap is debounced: a burst of peer updates (a large tailnet's netmap,
//     a relay flap marking every peer offline at once) becomes one event, at
//     the latest daemonEventNetmapMaxWait after the first change of the burst.
//   - seq grows by one per delivered event, across kinds, so the app can
//     tell an old event from a new one.
//   - A listener that panics is recovered (logged once) and a slow one only
//     delays later events: the bus loop never waits for it.

import (
	"encoding/json"
	"hash/fnv"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// DaemonEventListener is the app's half of the change events. Kind is one of
// "state" (backend state, the login link, the bridge attaching to or losing a
// daemon), "health" (the set of health warnings), "prefs" (the prefs the app
// shows: exit node, routes, DNS, hostname…) and "netmap" (peers added or
// removed, or a peer's online, exit-node-option, address or name changed).
// Engine statistics are not events: they are read on demand
// (GetEngineStatusJSON).
//
// Called from the bridge's dispatcher goroutine, one call at a time, with no
// Go lock held: the listener may call back into the bridge. Return quickly —
// a slow listener delays the events after it, never the bus.
type DaemonEventListener interface {
	OnDaemonEvent(kind string, seq int64)
}

// SetDaemonEventListener installs the listener for change events; nil removes
// it. Only one is kept; a later call replaces the earlier one, and an event
// already being delivered still goes to the listener it was taken for.
func SetDaemonEventListener(l DaemonEventListener) {
	daemonEvents.setListener(l)
}

type eventKind int

const (
	eventState eventKind = iota
	eventHealth
	eventPrefs
	eventNetmap
	eventKinds
)

var eventKindNames = [eventKinds]string{"state", "health", "prefs", "netmap"}

func (k eventKind) String() string { return eventKindNames[k] }

// daemonEventNetmapDebounce is how long the dispatcher waits after the last
// netmap change before telling the app; daemonEventNetmapMaxWait bounds the
// wait from the first change, so a tailnet that never stops changing is still
// reported. A test shortens them per dispatcher (setNetmapTiming).
const (
	daemonEventNetmapDebounce = 500 * time.Millisecond
	daemonEventNetmapMaxWait  = 2 * time.Second
)

// eventDispatcher coalesces published kinds and delivers them to the listener
// from its own goroutine, started on first use and kept for the process.
type eventDispatcher struct {
	mu           sync.Mutex
	debounce     time.Duration
	maxWait      time.Duration
	listener     DaemonEventListener
	pending      [eventKinds]bool
	netmapFirst  time.Time // first netmap change of the pending burst
	netmapLast   time.Time // latest one
	seq          int64
	started      bool
	wake         chan struct{}
	panicLogged  atomic.Bool
	delivered    atomic.Int64 // upcalls made, for tests and the measurements
	onDeliverErr func(any)    // test hook; nil logs
}

func newEventDispatcher() *eventDispatcher {
	return &eventDispatcher{
		debounce: daemonEventNetmapDebounce,
		maxWait:  daemonEventNetmapMaxWait,
		wake:     make(chan struct{}, 1),
	}
}

func (d *eventDispatcher) setNetmapTiming(debounce, maxWait time.Duration) {
	d.mu.Lock()
	d.debounce, d.maxWait = debounce, maxWait
	d.mu.Unlock()
}

var daemonEvents = newEventDispatcher()

func (d *eventDispatcher) setListener(l DaemonEventListener) {
	d.mu.Lock()
	d.listener = l
	d.mu.Unlock()
}

// publish marks kinds as changed. It never blocks and never calls the
// listener, so it is safe from the bus goroutine and under any bridge lock
// (the lock order is always bridge lock → d.mu; the dispatcher holds d.mu
// only to read and clear its own fields).
func (d *eventDispatcher) publish(kinds ...eventKind) {
	if len(kinds) == 0 {
		return
	}
	now := time.Now()
	d.mu.Lock()
	for _, k := range kinds {
		if k == eventNetmap {
			if !d.pending[eventNetmap] {
				d.netmapFirst = now
			}
			d.netmapLast = now
		}
		d.pending[k] = true
	}
	if !d.started {
		d.started = true
		go d.run()
	}
	d.mu.Unlock()
	select {
	case d.wake <- struct{}{}:
	default:
	}
}

// due says which pending kinds are ready now and, when netmap is still being
// debounced, how long until it is. Caller holds d.mu.
func (d *eventDispatcher) dueLocked(now time.Time) (ready []eventKind, wait time.Duration) {
	for k := eventKind(0); k < eventKinds; k++ {
		if !d.pending[k] {
			continue
		}
		if k == eventNetmap {
			at := d.netmapLast.Add(d.debounce)
			if limit := d.netmapFirst.Add(d.maxWait); limit.Before(at) {
				at = limit
			}
			if now.Before(at) {
				wait = at.Sub(now)
				continue
			}
		}
		ready = append(ready, k)
	}
	return ready, wait
}

func (d *eventDispatcher) run() {
	var timer *time.Timer
	var timerC <-chan time.Time
	for {
		select {
		case <-d.wake:
		case <-timerC:
			timerC = nil
		}
		d.mu.Lock()
		ready, wait := d.dueLocked(time.Now())
		l := d.listener
		type call struct {
			kind eventKind
			seq  int64
		}
		calls := make([]call, 0, len(ready))
		for _, k := range ready {
			d.pending[k] = false
			if l == nil {
				// Nobody to tell: the change is dropped, not queued for a
				// listener that may never come (the app reads everything
				// afresh when it registers and when a screen starts).
				continue
			}
			d.seq++
			calls = append(calls, call{k, d.seq})
		}
		d.mu.Unlock()

		if wait > 0 {
			if timer == nil {
				timer = time.NewTimer(wait)
			} else {
				timer.Stop()
				timer.Reset(wait)
			}
			timerC = timer.C
		}
		for _, c := range calls {
			d.deliver(l, c.kind, c.seq)
		}
	}
}

// deliver makes one upcall; a panic in it is the listener's, and must not end
// the dispatcher or anything after it.
func (d *eventDispatcher) deliver(l DaemonEventListener, k eventKind, seq int64) {
	defer func() {
		if r := recover(); r != nil {
			if d.onDeliverErr != nil {
				d.onDeliverErr(r)
				return
			}
			if !d.panicLogged.Swap(true) {
				slog.Error("Daemon event listener panicked; later events are still delivered", "kind", k.String(), "panic", r)
			}
		}
	}()
	d.delivered.Add(1)
	l.OnDaemonEvent(k.String(), seq)
}

// ─── Change detection ───────────────────────────────────────────────────────

// eventSignatures is what the last published event of each kind said about
// the snapshot. Guarded by busStateMu, like the snapshot it describes.
type eventSignatures struct {
	state, health, prefs string
	netmapPeers          int
	netmapSum            uint64
}

var lastEventSigs eventSignatures

// busPeerFacts holds, for every node of the current netmap by tailcfg.NodeID,
// a hash of the facts the app shows: stable id, name, online, whether it
// offers an exit node, its first address. busNetmapSum is the sum of those
// hashes, kept as peers come and go, so the netmap signature is
// (len(busPeerFacts), busNetmapSum): order-free, O(1) to read and O(changed
// peers) to keep — a Mullvad-sized tailnet costs one map entry per peer and
// nothing per notification beyond the peers it names. Guarded by busStateMu;
// emptied with the snapshot and replaced by every InitialStatus.
//
// busPeerRest is each node's hash before the online bit is folded in, so a
// patch that only flips Online needs no copy of the node.
var (
	busPeerFacts = map[int64]uint64{}
	busPeerRest  = map[int64]uint64{}
	busNetmapSum uint64
)

// setPeerFacts records p's facts. Caller holds busStateMu.
func setPeerFacts(p *BusPeer) {
	if p == nil {
		return
	}
	id := peerNodeID(p)
	if id == 0 {
		return
	}
	rest := peerRestHash(p)
	h := foldOnline(rest, p.Online != nil && *p.Online)
	if old, ok := busPeerFacts[id]; ok {
		busNetmapSum -= old
	}
	busPeerFacts[id] = h
	busPeerRest[id] = rest
	busNetmapSum += h
}

// patchPeerOnline applies a PeerChangedPatch's Online to the recorded facts.
// The patch carries no other fact the signature holds; a peer the bridge has
// not seen yet is left to the PeersChanged that introduces it. Caller holds
// busStateMu.
func patchPeerOnline(raw json.RawMessage, online bool) {
	id, err := strconv.ParseInt(string(raw), 10, 64)
	if err != nil || id == 0 {
		return
	}
	old, ok := busPeerFacts[id]
	if !ok {
		return
	}
	h := foldOnline(busPeerRest[id], online)
	busNetmapSum += h - old
	busPeerFacts[id] = h
}

func removePeerFacts(id int64) {
	if old, ok := busPeerFacts[id]; ok {
		busNetmapSum -= old
		delete(busPeerFacts, id)
		delete(busPeerRest, id)
	}
}

func resetPeerFacts() {
	busPeerFacts = map[int64]uint64{}
	busPeerRest = map[int64]uint64{}
	busNetmapSum = 0
}

// peerRestHash hashes the facts of p the app shows, but Online, the same for
// both shapes the bus sends a node in: ipnstate.PeerStatus (InitialStatus: ID
// is the StableID, ExitNodeOption and TailscaleIPs) and tailcfg.Node
// (PeersChanged, SelfChange: StableID, AllowedIPs and Addresses as prefixes).
func peerRestHash(p *BusPeer) uint64 {
	h := fnv.New64a()
	stable := p.StableID
	if stable == "" {
		// A PeerStatus's ID is the StableID, as a JSON string.
		stable = strings.Trim(string(p.ID), `"`)
	}
	name := p.DNSName
	if name == "" {
		name = p.Name
	}
	addr := ""
	if len(p.Addresses) > 0 {
		addr = p.Addresses[0]
	} else if len(p.TailscaleIPs) > 0 {
		addr = p.TailscaleIPs[0]
	}
	if i := strings.IndexByte(addr, '/'); i >= 0 {
		addr = addr[:i]
	}
	exit := "0"
	if p.ExitNodeOption || containsExitRoutes(p.AllowedIPs) {
		exit = "1"
	}
	for _, s := range []string{stable, strings.TrimSuffix(name, "."), addr, exit} {
		h.Write([]byte(s))
		h.Write([]byte{0})
	}
	return h.Sum64()
}

func foldOnline(rest uint64, online bool) uint64 {
	h := fnv.New64a()
	var b [9]byte
	for i := 0; i < 8; i++ {
		b[i] = byte(rest >> (8 * i))
	}
	if online {
		b[8] = 1
	}
	h.Write(b[:])
	return h.Sum64()
}

// containsExitRoutes is tsaddr.ContainsExitRoutes on strings: a node offers
// itself as an exit node when it may route both default routes.
func containsExitRoutes(allowed []string) bool {
	var v4, v6 bool
	for _, p := range allowed {
		switch p {
		case "0.0.0.0/0":
			v4 = true
		case "::/0":
			v6 = true
		}
	}
	return v4 && v6
}

// currentEventSigsLocked reads the signatures off the snapshot. Caller holds
// busStateMu (read or write).
func currentEventSigsLocked() eventSignatures {
	s := eventSignatures{netmapPeers: len(busPeerFacts), netmapSum: busNetmapSum}

	// The bridge attaching to a daemon or losing it is a state change too:
	// the app's "is anything running" changes with it.
	connected := "0"
	if busConnected.Load() {
		connected = "1"
	}
	s.state = busState.BackendState + "\x00" + busState.AuthURL + "\x00" + connected

	if len(busState.Health) > 0 {
		parts := make([]string, 0, len(busState.Health))
		for _, w := range busState.Health {
			parts = append(parts, strings.Join([]string{
				w.Code, w.Severity, w.Title, w.Text,
				strconv.FormatBool(w.ImpactsConnectivity), strconv.FormatInt(w.BrokenSinceMs, 10),
			}, "\x00"))
		}
		sort.Strings(parts)
		s.health = strings.Join(parts, "\x01")
	}

	if busState.Prefs != nil {
		// Every field BusPrefs decodes is one the app shows or acts on.
		if b, err := json.Marshal(busState.Prefs); err == nil {
			s.prefs = string(b)
		}
	}
	return s
}

// takeEventChangesLocked compares the snapshot with what was last published,
// records the new signatures and returns the kinds that changed. Caller holds
// busStateMu for writing.
func takeEventChangesLocked() []eventKind {
	cur := currentEventSigsLocked()
	prev := lastEventSigs
	lastEventSigs = cur
	var kinds []eventKind
	if cur.state != prev.state {
		kinds = append(kinds, eventState)
	}
	if cur.health != prev.health {
		kinds = append(kinds, eventHealth)
	}
	if cur.prefs != prev.prefs {
		kinds = append(kinds, eventPrefs)
	}
	if cur.netmapPeers != prev.netmapPeers || cur.netmapSum != prev.netmapSum {
		kinds = append(kinds, eventNetmap)
	}
	return kinds
}

// publishBusChanges publishes whatever changed in the snapshot since the last
// events, for the paths that change it outside a notification: the stream
// opening or closing, a reset, a status read that filled the snapshot in.
func publishBusChanges() {
	busStateMu.Lock()
	kinds := takeEventChangesLocked()
	busStateMu.Unlock()
	daemonEvents.publish(kinds...)
}

// publishBridgeState tells the app that the bridge's own idea of whether a
// daemon is there changed (Stop, a detach), whatever the snapshot says: the
// snapshot may have been empty already, and IsRunning changes only after it.
func publishBridgeState() {
	daemonEvents.publish(eventState)
}
