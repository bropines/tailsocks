package appctr

import (
	"io"
	"net"
	"net/http"
	"testing"
	"time"
)

// A stop ends a transfer in flight once the grace period is over, and frees
// the address at once: Shutdown alone left a busy connection streaming
// through the old transport, and held the proxy's lock for the 3 s it waited.
func TestStopDriveProxyCutsATransferInFlight(t *testing.T) {
	oldGrace := driveProxyGrace
	driveProxyGrace = 200 * time.Millisecond
	t.Cleanup(func() { driveProxyGrace = oldGrace })

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	started := make(chan struct{})
	release := make(chan struct{})
	defer close(release)
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("first piece"))
		w.(http.Flusher).Flush()
		close(started)
		select { // an upstream that stops sending
		case <-release:
		case <-r.Context().Done():
		}
	})}
	go srv.Serve(ln)
	driveProxyServerMu.Lock()
	driveProxyServer, driveProxyTransport = srv, &http.Transport{}
	driveProxyServerMu.Unlock()

	resp, err := http.Get("http://" + addr + "/big.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	<-started
	readDone := make(chan error, 1)
	go func() {
		_, err := io.ReadAll(resp.Body)
		readDone <- err
	}()

	// The lock is not held for the grace period.
	stopped := make(chan error, 1)
	begun := time.Now()
	go func() { stopped <- StopDriveProxy() }()
	time.Sleep(20 * time.Millisecond)
	locked := make(chan struct{})
	go func() {
		driveProxyServerMu.Lock()
		driveProxyServerMu.Unlock()
		close(locked)
	}()
	select {
	case <-locked:
	case <-time.After(100 * time.Millisecond):
		t.Error("StopDriveProxy holds the lock while it waits")
	}
	// The address is free before the transfer has ended.
	if again, err := net.Listen("tcp", addr); err != nil {
		t.Errorf("the address is still taken during the grace period: %v", err)
	} else {
		again.Close()
	}

	select {
	case err := <-stopped:
		if err == nil {
			t.Error("a stop that had to cut a transfer reported a clean shutdown")
		}
		if d := time.Since(begun); d > 2*time.Second {
			t.Errorf("stop took %v", d)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("StopDriveProxy did not return")
	}
	select {
	case err := <-readDone:
		if err == nil {
			t.Error("the transfer ended as if complete")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the transfer in flight was not cut")
	}
	driveProxyServerMu.Lock()
	defer driveProxyServerMu.Unlock()
	if driveProxyServer != nil || driveProxyTransport != nil {
		t.Error("the stopped proxy is still recorded")
	}
}
