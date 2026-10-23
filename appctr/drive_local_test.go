package appctr

import (
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// fakeDriveSocket serves handler on drive.sock in a temporary data directory,
// the way the daemon serves Taildrive, for the rest of the test.
func fakeDriveSocket(t *testing.T, handler http.Handler) {
	t.Helper()
	dir := t.TempDir()
	stateMu.Lock()
	old := PC
	PC = newPathControl(filepath.Join(dir, "libtailscale.so"), filepath.Join(dir, "tailscaled.sock"), filepath.Join(dir, "state"))
	stateMu.Unlock()
	ln, err := net.Listen("unix", filepath.Join(dir, DriveSocketName))
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: handler}
	go srv.Serve(ln)
	t.Cleanup(func() {
		srv.Close()
		// The pool's connections to this daemon go with it, as a restarted
		// daemon's do.
		driveClient.Transport.(*http.Transport).CloseIdleConnections()
		stateMu.Lock()
		PC = old
		stateMu.Unlock()
	})
}

// noDaemon points the bridge at a data directory with no socket and no
// SOCKS5 port.
func noDaemon(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	stateMu.Lock()
	old := PC
	PC = newPathControl("", filepath.Join(dir, "tailscaled.sock"), "")
	stateMu.Unlock()
	GConfig.update("", "", "", "")
	driveClient.Transport.(*http.Transport).CloseIdleConnections()
	t.Cleanup(func() {
		stateMu.Lock()
		PC = old
		stateMu.Unlock()
	})
}

// With no socket and no SOCKS5 port there is nowhere to go.
func TestDriveDialWithoutTheSocket(t *testing.T) {
	noDaemon(t)
	if _, err := os.Stat(getPC().DataDir(DriveSocketName)); err == nil {
		t.Fatal("the socket exists")
	}
	if c, err := driveDial(t.Context(), "tcp", "100.100.100.100:8080"); err == nil {
		c.Close()
		t.Fatal("dialled a daemon that is not there")
	}
}
