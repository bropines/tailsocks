package appctr

import (
	"context"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// serveFakeBus serves h on a unix socket and points the bridge at it as an
// external (root) daemon, the way the listener finds one.
func serveFakeBus(t *testing.T, h http.Handler) string {
	t.Helper()
	dir := t.TempDir()
	sock := filepath.Join(dir, "d.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: h}
	go srv.Serve(ln)
	stateMu.Lock()
	externalSocketPath = sock
	PC = newPathControl(filepath.Join(dir, "libtailscale.so"), sock, filepath.Join(dir, "state"))
	stateMu.Unlock()
	t.Cleanup(func() {
		srv.Close()
		stateMu.Lock()
		externalSocketPath = ""
		PC = pathControl{}
		stateMu.Unlock()
	})
	return sock
}

func busTestTransport() *http.Transport {
	return &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "unix", getPC().Socket())
	}}
}

// A refusal says why: 1.104's answer to an unsupported mask bit was decoded
// as JSON, failed as a syntax error, and the daemon's reason never reached
// the log.
func TestListenToBusReportsARefusalWithItsReason(t *testing.T) {
	serveFakeBus(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "the NotifyRateLimit IPN bus subscription bit is no longer supported", http.StatusBadRequest)
	}))
	connected, err := listenToBus(context.Background(), busTestTransport())
	if connected {
		t.Error("a refused subscription counted as connected")
	}
	if err == nil || !strings.Contains(err.Error(), "HTTP 400") || !strings.Contains(err.Error(), "no longer supported") {
		t.Fatalf("err = %v, want the status and the daemon's reason", err)
	}
	if busConnected.Load() {
		t.Error("busConnected set by a refused subscription")
	}
}

// A stream that was served and then dropped starts the backoff again: the
// delay used to grow with every disconnect of the process's life and never
// come down, so a late drop waited the full 30 s.
func TestBusListenerBackoffResetsAfterAServedStream(t *testing.T) {
	oldBase, oldMax := busRetryBase, busRetryMax
	busRetryBase, busRetryMax = 40*time.Millisecond, 5*time.Second
	t.Cleanup(func() { busRetryBase, busRetryMax = oldBase, oldMax })

	var mu sync.Mutex
	var at []time.Time
	serveFakeBus(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		at = append(at, time.Now())
		mu.Unlock()
		// The stream opens and ends at once: a daemon that served it and went.
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
	}))

	ctx, cancel := context.WithCancel(context.Background())
	busMu.Lock()
	busGen++
	gen := busGen
	busMu.Unlock()
	done := make(chan struct{})
	go func() { startIPNBusListener(ctx, gen); close(done) }()

	deadline := time.Now().Add(3 * time.Second)
	for {
		mu.Lock()
		n := len(at)
		mu.Unlock()
		if n >= 7 || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	<-done

	mu.Lock()
	defer mu.Unlock()
	if len(at) < 7 {
		t.Fatalf("%d attempts in 3 s; a doubling backoff from 40 ms would make about 6, a reset one dozens", len(at))
	}
	for i := 1; i < len(at); i++ {
		// Doubling would reach 40·2⁵ = 1.28 s by the sixth gap.
		if gap := at[i].Sub(at[i-1]); gap > 400*time.Millisecond {
			t.Errorf("gap %d = %v, want about the base delay", i, gap)
		}
	}
}

// While the listener holds a stream from the root daemon's socket, IsRunning
// takes it as proof of life instead of dialling the socket every half second;
// once the stream is gone the probe is back.
func TestSocketAliveTrustsTheOpenBusStream(t *testing.T) {
	sock := serveFakeBus(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	resetProbe := func() {
		probeMu.Lock()
		probePath, probeAt, probeOK = "", time.Time{}, false
		probeMu.Unlock()
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { listenToBus(ctx, busTestTransport()); close(done) }()
	deadline := time.Now().Add(2 * time.Second)
	for busStreamSocket.Load() == nil {
		if time.Now().After(deadline) {
			t.Fatal("the stream never opened")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got := *busStreamSocket.Load(); got != sock {
		t.Fatalf("stream socket = %q, want %q", got, sock)
	}

	// A probe would fail now: there is no socket file left to stat.
	if err := os.Remove(sock); err != nil {
		t.Fatal(err)
	}
	resetProbe()
	if !socketAlive(sock) {
		t.Error("an open stream from the socket did not count as alive")
	}
	if socketAlive(sock + ".other") {
		t.Error("the stream vouched for a socket it does not come from")
	}

	cancel()
	<-done
	if busStreamSocket.Load() != nil || busConnected.Load() {
		t.Fatal("the stream's end left it recorded")
	}
	resetProbe()
	if socketAlive(sock) {
		t.Error("without a stream the probe should decide, and it has no socket")
	}
}
