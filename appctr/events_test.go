package appctr

import (
	"context"
	"net/http"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// recordingListener records every event it is told about.
type recordingListener struct {
	mu     sync.Mutex
	events []string
	seqs   []int64
	during func(kind string)
}

func (r *recordingListener) OnDaemonEvent(kind string, seq int64) {
	if r.during != nil {
		r.during(kind)
	}
	r.mu.Lock()
	r.events = append(r.events, kind)
	r.seqs = append(r.seqs, seq)
	r.mu.Unlock()
}

func (r *recordingListener) got() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.events)
}

func (r *recordingListener) count(kind string) int {
	n := 0
	for _, k := range r.got() {
		if k == kind {
			n++
		}
	}
	return n
}

func waitEvent(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func testDispatcher(debounce, maxWait time.Duration) *eventDispatcher {
	d := newEventDispatcher()
	d.setNetmapTiming(debounce, maxWait)
	return d
}

// Latest wins per kind: changes that come in while the listener is busy end
// up as one event per kind, not one per change.
func TestDaemonEventsCoalescePerKind(t *testing.T) {
	d := testDispatcher(time.Millisecond, time.Millisecond)
	release := make(chan struct{})
	var first atomic.Bool
	l := &recordingListener{during: func(string) {
		if !first.Swap(true) {
			<-release
		}
	}}
	d.setListener(l)

	d.publish(eventState)
	waitEvent(t, "the first event to be in delivery", first.Load)
	for i := 0; i < 50; i++ {
		d.publish(eventState)
		d.publish(eventHealth)
	}
	close(release)
	waitEvent(t, "the coalesced events", func() bool { return len(l.got()) >= 3 })
	time.Sleep(50 * time.Millisecond)
	if got := l.got(); !slices.Equal(got, []string{"state", "state", "health"}) {
		t.Fatalf("events = %v, want the first state and then one state and one health", got)
	}
}

// A burst of netmap changes is one event; one that never stops is still told
// every maxWait.
func TestDaemonEventsDebounceNetmap(t *testing.T) {
	d := testDispatcher(40*time.Millisecond, time.Second)
	l := &recordingListener{}
	d.setListener(l)
	for i := 0; i < 10; i++ {
		d.publish(eventNetmap)
		time.Sleep(5 * time.Millisecond)
	}
	if n := l.count("netmap"); n != 0 {
		t.Fatalf("netmap told %d times during the burst", n)
	}
	waitEvent(t, "the netmap event", func() bool { return l.count("netmap") == 1 })
	time.Sleep(100 * time.Millisecond)
	if n := l.count("netmap"); n != 1 {
		t.Fatalf("a burst of 10 netmap changes made %d events, want 1", n)
	}

	// Never quiet for the debounce: maxWait still lets events through.
	d2 := testDispatcher(40*time.Millisecond, 60*time.Millisecond)
	l2 := &recordingListener{}
	d2.setListener(l2)
	stop := time.Now().Add(300 * time.Millisecond)
	for time.Now().Before(stop) {
		d2.publish(eventNetmap)
		time.Sleep(10 * time.Millisecond)
	}
	if n := l2.count("netmap"); n < 2 {
		t.Fatalf("a constant stream of netmap changes was told %d times in 300 ms, want several", n)
	}
}

// The other kinds are not held back by a netmap being debounced.
func TestDaemonEventsStateIsNotDebounced(t *testing.T) {
	d := testDispatcher(time.Hour, time.Hour)
	l := &recordingListener{}
	d.setListener(l)
	d.publish(eventNetmap, eventState)
	waitEvent(t, "the state event", func() bool { return l.count("state") == 1 })
	if n := l.count("netmap"); n != 0 {
		t.Fatalf("netmap delivered before its debounce")
	}
}

func TestDaemonEventsSeqIncreases(t *testing.T) {
	d := testDispatcher(time.Millisecond, time.Millisecond)
	l := &recordingListener{}
	d.setListener(l)
	for _, k := range []eventKind{eventState, eventHealth, eventPrefs, eventNetmap, eventState} {
		d.publish(k)
		time.Sleep(10 * time.Millisecond)
	}
	waitEvent(t, "five events", func() bool { return len(l.got()) == 5 })
	l.mu.Lock()
	defer l.mu.Unlock()
	for i := 1; i < len(l.seqs); i++ {
		if l.seqs[i] <= l.seqs[i-1] {
			t.Fatalf("seqs %v do not increase", l.seqs)
		}
	}
}

// A listener that panics is recovered; later events are still delivered.
func TestDaemonEventsSurviveAPanickingListener(t *testing.T) {
	d := testDispatcher(time.Millisecond, time.Millisecond)
	var recovered atomic.Int32
	d.onDeliverErr = func(any) { recovered.Add(1) }
	var calls atomic.Int32
	l := &recordingListener{during: func(string) {
		if calls.Add(1) == 1 {
			panic("listener bug")
		}
	}}
	d.setListener(l)
	d.publish(eventState)
	waitEvent(t, "the panic to be recovered", func() bool { return recovered.Load() == 1 })
	d.publish(eventHealth)
	waitEvent(t, "the next event", func() bool { return l.count("health") == 1 })
}

// Replaced mid-stream: later events go to the new listener; cleared: to nobody,
// and nothing is queued for a listener that comes later.
func TestDaemonEventsListenerReplacedAndCleared(t *testing.T) {
	d := testDispatcher(time.Millisecond, time.Millisecond)
	a, b := &recordingListener{}, &recordingListener{}
	d.setListener(a)
	d.publish(eventState)
	waitEvent(t, "a's event", func() bool { return len(a.got()) == 1 })
	d.setListener(b)
	d.publish(eventPrefs)
	waitEvent(t, "b's event", func() bool { return len(b.got()) == 1 })
	d.setListener(nil)
	d.publish(eventHealth)
	time.Sleep(30 * time.Millisecond)
	d.setListener(a)
	time.Sleep(30 * time.Millisecond)
	if got := a.got(); !slices.Equal(got, []string{"state"}) {
		t.Errorf("a got %v", got)
	}
	if got := b.got(); !slices.Equal(got, []string{"prefs"}) {
		t.Errorf("b got %v", got)
	}
}

// useGlobalListener points the bridge's own dispatcher at l, with a short
// netmap debounce, for the tests that go through applyNotify.
func useGlobalListener(t *testing.T, l DaemonEventListener) {
	t.Helper()
	daemonEvents.setNetmapTiming(5*time.Millisecond, 20*time.Millisecond)
	resetBusState()
	time.Sleep(30 * time.Millisecond) // let the reset's own events drain before l is installed
	SetDaemonEventListener(l)
	t.Cleanup(func() {
		SetDaemonEventListener(nil)
		resetBusState()
		FlushDNS()
		daemonEvents.setNetmapTiming(daemonEventNetmapDebounce, daemonEventNetmapMaxWait)
	})
}

// No upcall under a bridge lock: a listener that reads the bridge's state from
// inside the call — what the app does — must not deadlock the bus.
func TestDaemonEventsListenerMayCallBackIntoTheBridge(t *testing.T) {
	var reads atomic.Int32
	l := &recordingListener{during: func(string) {
		_ = GetBusState()
		_ = GetHealthWarningsJSON()
		reads.Add(1)
	}}
	useGlobalListener(t, l)

	done := make(chan struct{})
	go func() {
		applyNotify(busNotify(t, `{"State":6,"Prefs":{"ExitNodeID":"nX"},
			"Health":{"Warnings":{"a":{"WarnableCode":"a","Severity":"high","Text":"x"}}}}`))
		applyNotify(busNotify(t, `{"State":2}`))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("applyNotify blocked on the listener")
	}
	waitEvent(t, "the listener's reads", func() bool { return reads.Load() >= 3 })
}

// Each kind fires on its own change and on nothing else; a repeat of the same
// state, health, prefs or peers is no event at all.
func TestDaemonEventChangeDetection(t *testing.T) {
	t.Cleanup(func() { resetBusState(); FlushDNS() })
	resetBusState()
	kinds := func(raw string) []string {
		t.Helper()
		var out []string
		for _, k := range applyNotifyLocked(busNotify(t, raw)) {
			out = append(out, k.String())
		}
		return out
	}
	expect := func(raw string, want ...string) {
		t.Helper()
		if got := kinds(raw); !slices.Equal(got, want) {
			t.Errorf("%s\n  kinds = %v, want %v", raw, got, want)
		}
	}

	expect(`{"State":6}`, "state")
	expect(`{"State":6}`)
	expect(`{"BrowseToURL":"https://login.example/a"}`, "state")
	expect(`{"LoginFinished":{}}`, "state")

	health := `{"Health":{"Warnings":{"w1":{"WarnableCode":"w1","Severity":"medium","Text":"t","BrokenSince":"2026-11-14T10:00:00Z"},"w2":{"WarnableCode":"w2","Text":"u"}}}}`
	expect(health, "health")
	expect(health) // the daemon republishes health constantly
	expect(`{"Health":{"Warnings":{"w2":{"WarnableCode":"w2","Text":"u"}}}}`, "health")
	expect(`{"Health":{}}`, "health")

	expect(`{"Prefs":{"ExitNodeID":"nA","CorpDNS":true}}`, "prefs")
	expect(`{"Prefs":{"ExitNodeID":"nA","CorpDNS":true}}`)
	expect(`{"Prefs":{"ExitNodeID":"","CorpDNS":true}}`, "prefs")

	expect(`{"InitialStatus":{"Self":{"ID":"nSelf","NodeID":1,"DNSName":"me.ts.net.","TailscaleIPs":["100.64.0.1"],"Online":true},
		"Peer":{"k2":{"ID":"nTwo","NodeID":2,"DNSName":"two.ts.net.","TailscaleIPs":["100.64.0.2"],"Online":true},
		        "k3":{"ID":"nThree","NodeID":3,"DNSName":"exit.ts.net.","TailscaleIPs":["100.64.0.3"],"ExitNodeOption":true}}}}`, "netmap")
	// The same node again as a tailcfg.Node — other shape, same facts: no event.
	expect(`{"PeersChanged":[{"ID":2,"StableID":"nTwo","Name":"two.ts.net.","Addresses":["100.64.0.2/32"],"Online":true,"AllowedIPs":["100.64.0.2/32"]}]}`)
	expect(`{"PeersChanged":[{"ID":3,"StableID":"nThree","Name":"exit.ts.net.","Addresses":["100.64.0.3/32"],"AllowedIPs":["100.64.0.3/32","0.0.0.0/0","::/0"]}]}`)
	// Patches that change nothing the app shows: no event.
	expect(`{"PeerChangedPatch":[{"NodeID":2,"DERPHome":3,"LastSeen":"2026-11-14T10:00:00Z"}]}`)
	expect(`{"PeerChangedPatch":[{"NodeID":2,"Online":true}]}`)
	// A peer going offline, and back.
	expect(`{"PeerChangedPatch":[{"NodeID":2,"Online":false}]}`, "netmap")
	expect(`{"PeerChangedPatch":[{"NodeID":2,"Online":true}]}`, "netmap")
	// Stops offering an exit node.
	expect(`{"PeersChanged":[{"ID":3,"StableID":"nThree","Name":"exit.ts.net.","Addresses":["100.64.0.3/32"],"AllowedIPs":["100.64.0.3/32"]}]}`, "netmap")
	// A new peer, a removed one, an unknown one removed.
	expect(`{"PeersChanged":[{"ID":4,"StableID":"nFour","Name":"four.ts.net.","Addresses":["100.64.0.4/32"]}]}`, "netmap")
	expect(`{"PeersRemoved":[4]}`, "netmap")
	expect(`{"PeersRemoved":[99]}`)
	// Engine statistics are no event.
	expect(`{"Engine":{"RBytes":10,"NumLive":2}}`)

	// The bridge losing the stream is a state change; the reset empties
	// everything, which is a change of every kind that had something.
	busConnected.Store(true)
	busStateMu.Lock()
	k := takeEventChangesLocked()
	busStateMu.Unlock()
	busConnected.Store(false)
	if len(k) != 1 || k[0] != eventState {
		t.Errorf("attaching: kinds = %v, want state", k)
	}
	busStateMu.Lock()
	k = takeEventChangesLocked()
	busStateMu.Unlock()
	if len(k) != 1 || k[0] != eventState {
		t.Errorf("detaching: kinds = %v, want state", k)
	}
}

// The netmap signature is kept, not recomputed: a large tailnet's peers each
// cost one entry, and a patch that names one peer touches only that one.
func TestDaemonEventNetmapSignatureIsOrderFree(t *testing.T) {
	t.Cleanup(func() { resetBusState(); FlushDNS() })
	resetBusState()
	applyNotifyLocked(busNotify(t, `{"PeersChanged":[
		{"ID":10,"StableID":"a","Name":"a.ts.net.","Addresses":["100.64.1.1/32"]},
		{"ID":11,"StableID":"b","Name":"b.ts.net.","Addresses":["100.64.1.2/32"]}]}`))
	busStateMu.RLock()
	sumAB := busNetmapSum
	busStateMu.RUnlock()
	resetBusState()
	applyNotifyLocked(busNotify(t, `{"PeersChanged":[{"ID":11,"StableID":"b","Name":"b.ts.net.","Addresses":["100.64.1.2/32"]}]}`))
	applyNotifyLocked(busNotify(t, `{"PeersChanged":[{"ID":10,"StableID":"a","Name":"a.ts.net.","Addresses":["100.64.1.1/32"]}]}`))
	busStateMu.RLock()
	sumBA, n := busNetmapSum, len(busPeerFacts)
	busStateMu.RUnlock()
	if sumAB != sumBA || n != 2 {
		t.Fatalf("signature depends on the order peers arrived in: %x vs %x (%d peers)", sumAB, sumBA, n)
	}
}

// The measurement behind the change: a connected, idle daemon for a minute.
// What the bus sends then — health republished, the state repeated, peers'
// relays and last-seen times patched — is no event at all, so the app does no
// work; only the first connect tells it anything. (Before the events, the main
// screen asked the bridge 30 times a minute and read the full status 6 times.)
func TestDaemonEventsConnectedAndIdleForAMinute(t *testing.T) {
	l := &recordingListener{}
	useGlobalListener(t, l)

	applyNotify(busNotify(t, `{"State":6,"Prefs":{"ExitNodeID":"","CorpDNS":true,"WantRunning":true},
		"Health":{"Warnings":{}},
		"InitialStatus":{"Self":{"ID":"nSelf","NodeID":1,"DNSName":"me.ts.net.","TailscaleIPs":["100.64.0.1"]},
		"Peer":{"k2":{"ID":"nTwo","NodeID":2,"DNSName":"two.ts.net.","TailscaleIPs":["100.64.0.2"],"Online":true}}}}`))
	waitEvent(t, "the connect's events", func() bool { return len(l.got()) == 3 })
	time.Sleep(50 * time.Millisecond)
	connect := len(l.got())

	// 60 s of an idle daemon, compressed: what it sends every couple of seconds.
	for i := 0; i < 30; i++ {
		applyNotify(busNotify(t, `{"State":6}`))
		applyNotify(busNotify(t, `{"Health":{"Warnings":{}}}`))
		applyNotify(busNotify(t, `{"PeerChangedPatch":[{"NodeID":2,"DERPHome":2,"LastSeen":"2026-11-14T10:00:00Z"}]}`))
		applyNotify(busNotify(t, `{"Engine":{"RBytes":100,"WBytes":100}}`))
	}
	time.Sleep(100 * time.Millisecond)
	idle := len(l.got()) - connect
	t.Logf("connect: %d upcalls (%v); idle minute: %d upcalls", connect, l.got()[:connect], idle)
	if idle != 0 {
		t.Fatalf("an idle minute made %d upcalls (%v), want 0", idle, l.got()[connect:])
	}
}

// The stream from the daemon opening, and its end — a daemon killed, the root
// daemon's socket gone — are each a state event, through the same listener as
// every other event: serveFakeBus attaches the bridge the way Root Mode does,
// to an external socket.
func TestDaemonEventsStreamOpenAndCloseAreStateEvents(t *testing.T) {
	l := &recordingListener{}
	useGlobalListener(t, l)
	release := make(chan struct{})
	serveFakeBus(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-release
	}))
	done := make(chan struct{})
	go func() {
		_, _ = listenToBus(context.Background(), busTestTransport())
		close(done)
	}()
	waitEvent(t, "the attach event", func() bool { return l.count("state") == 1 })
	close(release)
	<-done
	waitEvent(t, "the detach event", func() bool { return l.count("state") == 2 })
}
