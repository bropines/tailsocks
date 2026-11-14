package appctr

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/ipn"
)

// Off by default: the subscription is today's, engine updates included.
func TestEngineUpdatesOnDemandMask(t *testing.T) {
	t.Cleanup(func() { SetEngineUpdatesOnDemand(false) })
	if busWatchMaskNow() != busWatchMask || busWatchMask&engineWatchMask == 0 {
		t.Fatalf("default mask %d, want busWatchMask %d with engine updates", busWatchMaskNow(), busWatchMask)
	}
	SetEngineUpdatesOnDemand(true)
	m := busWatchMaskNow()
	if m&engineWatchMask != 0 || m|engineWatchMask != busWatchMask {
		t.Fatalf("on-demand mask %d, want busWatchMask without bit 0", m)
	}
	var opt ipn.NotifyWatchOpt
	if err := opt.UnmarshalText([]byte(strconv.Itoa(m))); err != nil {
		t.Fatal(err)
	}
	if err := ipn.ValidateNotifyWatchOpt(opt); err != nil {
		t.Fatalf("the daemon refuses the on-demand mask: %v", err)
	}
	if ipn.NotifyWatchOpt(engineWatchMask) != ipn.NotifyWatchEngineUpdates {
		t.Fatal("engineWatchMask is not NotifyWatchEngineUpdates")
	}
}

// With engine updates on demand, a read opens a watch that asks for nothing
// else, gets the daemon's numbers, and the watch closes itself once nobody
// reads.
func TestEngineWatchOpensOnReadAndClosesWhenIdle(t *testing.T) {
	oldIdle, oldWait := engineWatchIdle, engineFirstWait
	engineWatchIdle, engineFirstWait = 600*time.Millisecond, 2*time.Second
	SetEngineUpdatesOnDemand(true)
	t.Cleanup(func() {
		engineWatchIdle, engineFirstWait = oldIdle, oldWait
		SetEngineUpdatesOnDemand(false)
		stopEngineWatch()
		resetBusState()
	})
	resetBusState()

	var mu sync.Mutex
	var masks []string
	open := 0
	serveFakeBus(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mask := r.URL.Query().Get("mask")
		mu.Lock()
		masks = append(masks, mask)
		open++
		mu.Unlock()
		defer func() { mu.Lock(); open--; mu.Unlock() }()
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		// The daemon's poll: a status every so often, after a first wait.
		tick := time.NewTicker(100 * time.Millisecond)
		defer tick.Stop()
		for n := int64(1); ; n++ {
			select {
			case <-r.Context().Done():
				return
			case <-tick.C:
				b, _ := json.Marshal(map[string]any{"Engine": BusEngineStatus{RBytes: 1000 * n, WBytes: 10, NumLive: 2, LiveDERPs: 1}})
				fmt.Fprintf(w, "%s\n", b)
				w.(http.Flusher).Flush()
			}
		}
	}))

	got := GetEngineStatusJSON()
	var e BusEngineStatus
	if err := json.Unmarshal([]byte(got), &e); err != nil || e.NumLive != 2 || e.LiveDERPs != 1 || e.RBytes == 0 {
		t.Fatalf("first read: %q (%v), want the daemon's numbers", got, err)
	}
	mu.Lock()
	if len(masks) != 1 || masks[0] != strconv.Itoa(engineWatchMask) {
		t.Errorf("subscriptions %v, want one for engine updates alone", masks)
	}
	mu.Unlock()

	// Read like the card: the numbers move, over the one watch.
	time.Sleep(250 * time.Millisecond) // well inside engineWatchIdle
	second := GetEngineStatusJSON()
	if second == got {
		t.Errorf("the numbers did not move: %s", second)
	}
	mu.Lock()
	if len(masks) != 1 {
		t.Errorf("a read while the watch is open opened another: %v", masks)
	}
	mu.Unlock()

	// Nobody reads: the watch goes.
	deadline := time.Now().Add(2 * time.Second)
	for {
		mu.Lock()
		n := open
		mu.Unlock()
		if n == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the engine watch is still open with nobody reading")
		}
		time.Sleep(20 * time.Millisecond)
	}
	engineWatch.mu.Lock()
	defer engineWatch.mu.Unlock()
	if engineWatch.cancel != nil {
		t.Error("the closed watch is still recorded")
	}
}

// Off, a read is what it was: the snapshot, no subscription of its own.
func TestEngineStatusReadWithoutOnDemand(t *testing.T) {
	t.Cleanup(resetBusState)
	resetBusState()
	if got := GetEngineStatusJSON(); got != "{}" {
		t.Errorf("before any status: %q", got)
	}
	applyNotify(&BusNotify{Engine: &BusEngineStatus{NumLive: 3}})
	if got := GetEngineStatusJSON(); !strings.Contains(got, `"NumLive":3`) {
		t.Errorf("after a status: %q", got)
	}
	engineWatch.mu.Lock()
	defer engineWatch.mu.Unlock()
	if engineWatch.cancel != nil {
		t.Error("a watch was opened with on-demand off")
	}
}
