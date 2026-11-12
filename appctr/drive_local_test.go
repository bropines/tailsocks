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

// TestDriveProxyHostAllowed: the proxy answers a client that was given its
// address, and not a browser a web page pointed at it under the page's name.
func TestDriveProxyHostAllowed(t *testing.T) {
	const self = "phone.tail8a412.ts.net."
	cases := []struct {
		host, self string
		want       bool
	}{
		{"127.0.0.1:33445", self, true},
		{"192.168.1.20:33445", self, true},
		{"100.64.0.1", self, true},
		{"[::1]:33445", self, true},
		{"[fd7a:115c:a1e0::1]:33445", self, true},
		{"localhost:33445", self, true},
		{"LocalHost", self, true},
		{"phone.tail8a412.ts.net:33445", self, true},
		{"Phone.Tail8a412.ts.net.:33445", self, true},
		{"phone:33445", self, true},
		{"rebind.attacker.example:33445", self, false},
		{"attacker.example", self, false},
		{"phone.attacker.example:33445", self, false},
		{"localhost.attacker.example:33445", self, false},
		{"phone.tail8a412.ts.net:33445", "", false},
		{"", self, false},
		{":33445", self, false},
	}
	for _, c := range cases {
		if got := driveProxyHostAllowed(c.host, c.self); got != c.want {
			t.Errorf("driveProxyHostAllowed(%q, %q) = %v, want %v", c.host, c.self, got, c.want)
		}
	}
}

func TestDriveServerPort(t *testing.T) {
	if got := driveServerPort("0123abcd|127.0.0.1:41234"); got != "41234" {
		t.Errorf("port = %q", got)
	}
	if got := driveServerPort("no port here"); got != "" {
		t.Errorf("port of an address without one = %q", got)
	}
}
