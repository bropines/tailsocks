package appctr

import (
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// Engine updates on demand.
//
// Bit 0 of busWatchMask (NotifyWatchEngineUpdates) makes the daemon start, for
// that subscription, a 2-second ticker that walks the WireGuard peers, runs
// the state machine and broadcasts the engine status
// (ipn/ipnlocal/local.go pollRequestEngineStatus) — for as long as the bridge
// is connected, screen off included. The daemon does not need it for itself:
// magicsock and Reconfig request a status on their own, which is how a
// headless tailscaled reaches Running. Only the diagnostics card (and the
// report built from it) reads the result, GetEngineStatusJSON.
//
// With SetEngineUpdatesOnDemand(true) the listener subscribes without the bit,
// and GetEngineStatusJSON keeps a second, small subscription that asks only
// for engine updates while somebody reads them: it is opened by the first
// read and closes itself engineWatchIdle after the last one. The card reads
// every 2 s while it is shown, so it sees the same numbers; the first read
// after a pause waits up to engineFirstWait for the daemon's first status
// instead of answering with an old one. Off by default — today's subscription,
// bit 0 included — until the app turns it on.

// engineOnDemand is SetEngineUpdatesOnDemand's switch.
var engineOnDemand atomic.Bool

// SetEngineUpdatesOnDemand chooses how engine statuses are asked for: false
// (the default) subscribes to them for the bus listener's whole life, true
// only while GetEngineStatusJSON is being called. It takes effect when the
// listener next connects — every daemon start and attach — so the app sets it
// once, before it starts the service.
func SetEngineUpdatesOnDemand(on bool) {
	engineOnDemand.Store(on)
}

// busWatchMaskNow is the listener's subscription: busWatchMask, without
// NotifyWatchEngineUpdates while engine updates are on demand.
func busWatchMaskNow() int {
	if engineOnDemand.Load() {
		return busWatchMask &^ engineWatchMask
	}
	return busWatchMask
}

// engineWatchMask is ipn.NotifyWatchEngineUpdates, all the engine watch asks.
const engineWatchMask = 1 << 0

// engineWatchIdle is how long the engine watch stays open after the last
// GetEngineStatusJSON; engineFirstWait how long a read that opened it waits
// for the first status (the daemon's ticker fires after 2 s). Variables so a
// test can shorten them.
var (
	engineWatchIdle = 10 * time.Second
	engineFirstWait = 2500 * time.Millisecond
)

// busEngineAt is when busState.Engine was last set. Guarded by busStateMu.
var busEngineAt time.Time

var engineWatch struct {
	mu     sync.Mutex
	cancel context.CancelFunc // nil while no watch is open
	asked  time.Time          // the last read
}

// wantEngineUpdates records a read and opens the engine watch if none is
// open. It returns when the watch began if this call opened it, and the zero
// time otherwise: only the read that opens a watch waits for its first status
// — a later one taking the last status as it is, so a daemon that sends none
// cannot hold every read up.
func wantEngineUpdates() time.Time {
	engineWatch.mu.Lock()
	defer engineWatch.mu.Unlock()
	now := time.Now()
	engineWatch.asked = now
	if engineWatch.cancel != nil {
		return time.Time{}
	}
	ctx, cancel := context.WithCancel(context.Background())
	engineWatch.cancel = cancel
	go runEngineWatch(ctx, cancel)
	return now
}

// stopEngineWatch closes the engine watch, if one is open.
func stopEngineWatch() {
	engineWatch.mu.Lock()
	defer engineWatch.mu.Unlock()
	if engineWatch.cancel != nil {
		engineWatch.cancel()
		engineWatch.cancel = nil
	}
}

// awaitEngineStatus waits, up to max, for an engine status newer than since;
// a zero since does not wait.
func awaitEngineStatus(since time.Time, max time.Duration) {
	if since.IsZero() {
		return
	}
	deadline := time.Now().Add(max)
	for {
		busStateMu.RLock()
		fresh := !busEngineAt.Before(since)
		busStateMu.RUnlock()
		if fresh || !time.Now().Before(deadline) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// runEngineWatch holds a subscription to engine updates until nobody has read
// them for engineWatchIdle, the daemon goes, or stopEngineWatch. It applies
// what it receives itself, so it does not depend on the daemon handing every
// watcher every status.
func runEngineWatch(ctx context.Context, cancel context.CancelFunc) {
	defer func() {
		engineWatch.mu.Lock()
		// Only the watch that is still the current one is forgotten here.
		if engineWatch.cancel != nil && ctx.Err() == nil {
			engineWatch.cancel = nil
		}
		engineWatch.mu.Unlock()
		cancel()
	}()
	go func() {
		tick := time.NewTicker(engineWatchIdle / 4)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				engineWatch.mu.Lock()
				idle := time.Since(engineWatch.asked) >= engineWatchIdle
				if idle && engineWatch.cancel != nil {
					engineWatch.cancel()
					engineWatch.cancel = nil
				}
				engineWatch.mu.Unlock()
				if idle {
					return
				}
			}
		}
	}()

	tr := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "unix", getPC().Socket())
	}}
	defer tr.CloseIdleConnections()
	req, _ := http.NewRequestWithContext(ctx, "GET",
		"http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask="+strconv.Itoa(engineWatchMask), nil)
	resp, err := (&http.Client{Transport: tr}).Do(req)
	if err != nil {
		if ctx.Err() == nil {
			slog.Debug("Engine watch: no subscription", "err", err)
		}
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		slog.Warn("Engine watch: the daemon refuses it", "status", resp.StatusCode)
		return
	}
	dec := json.NewDecoder(resp.Body)
	for {
		var msg struct {
			Engine *BusEngineStatus `json:"Engine,omitempty"`
		}
		if err := dec.Decode(&msg); err != nil || ctx.Err() != nil {
			return
		}
		if msg.Engine != nil {
			e := *msg.Engine
			busStateMu.Lock()
			busState.Engine = &e
			busEngineAt = time.Now()
			busStateMu.Unlock()
		}
	}
}
