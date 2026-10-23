package appctr

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeDriveSocket serves HTTP on drive.sock in a temporary data directory,
// the way the daemon does, and records what reached it.
func fakeDriveSocket(t *testing.T) chan *http.Request {
	t.Helper()
	dir := t.TempDir()
	stateMu.Lock()
	old := PC
	PC = newPathControl(filepath.Join(dir, "libtailscale.so"), filepath.Join(dir, "tailscaled.sock"), filepath.Join(dir, "state"))
	stateMu.Unlock()
	t.Cleanup(func() {
		stateMu.Lock()
		PC = old
		stateMu.Unlock()
	})
	ln, err := net.Listen("unix", filepath.Join(dir, DriveSocketName))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	seen := make(chan *http.Request, 8)
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- r
		w.WriteHeader(207)
		io.WriteString(w, "from the daemon")
	}))
	return seen
}

// rawProxyRequest sends one request to the endpoint the way HttpURLConnection
// does through an HTTP proxy: the absolute URI on the request line.
func rawProxyRequest(t *testing.T, addr, method, uri string, header map[string]string) (int, string) {
	t.Helper()
	c, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	var b strings.Builder
	b.WriteString(method + " " + uri + " HTTP/1.1\r\nHost: 100.100.100.100:8080\r\nProxy-Connection: keep-alive\r\n")
	for k, v := range header {
		b.WriteString(k + ": " + v + "\r\n")
	}
	b.WriteString("Content-Length: 0\r\n\r\n")
	if _, err := io.WriteString(c, b.String()); err != nil {
		t.Fatal(err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(body)
}

func TestDriveLocalEndpoint(t *testing.T) {
	seen := fakeDriveSocket(t)
	addr := DriveLocalAddr()
	if addr == "" {
		t.Fatal("no local endpoint")
	}
	if again := DriveLocalAddr(); again != addr {
		t.Fatalf("a second call started another endpoint: %s, then %s", addr, again)
	}
	token := DriveLocalToken()
	if len(token) < 32 {
		t.Fatalf("token %q is too short", token)
	}

	// Another app on the device, without the token, gets nothing.
	if code, _ := rawProxyRequest(t, addr, "PROPFIND", "http://100.100.100.100:8080/", nil); code != http.StatusForbidden {
		t.Fatalf("without the token: HTTP %d, want 403", code)
	}
	if code, _ := rawProxyRequest(t, addr, "PROPFIND", "http://100.100.100.100:8080/", map[string]string{DriveLocalHeader: "wrong"}); code != http.StatusForbidden {
		t.Fatalf("with a wrong token: HTTP %d, want 403", code)
	}
	select {
	case r := <-seen:
		t.Fatalf("a refused request reached the daemon: %s %s", r.Method, r.URL)
	default:
	}

	// The app's client: the request reaches the daemon's socket as one to
	// 100.100.100.100:8080, without the token, the Destination rewritten.
	code, body := rawProxyRequest(t, addr, "MOVE", "http://100.100.100.100:8080/tailnet/dev/share/a%20b.txt", map[string]string{
		DriveLocalHeader: token,
		"Destination":    "http://127.0.0.1:1/tailnet/dev/share/c.txt",
	})
	if code != 207 || body != "from the daemon" {
		t.Fatalf("with the token: HTTP %d %q", code, body)
	}
	r := <-seen
	if r.Method != "MOVE" || r.URL.EscapedPath() != "/tailnet/dev/share/a%20b.txt" || r.Host != "100.100.100.100:8080" {
		t.Errorf("the daemon got %s %s (Host %s)", r.Method, r.URL.EscapedPath(), r.Host)
	}
	if r.Header.Get(DriveLocalHeader) != "" || r.Header.Get("Proxy-Connection") != "" {
		t.Error("the token or Proxy-Connection went on to the daemon")
	}
	if got, want := r.Header.Get("Destination"), "http://100.100.100.100:8080/tailnet/dev/share/c.txt"; got != want {
		t.Errorf("Destination = %q, want %q", got, want)
	}
}

// With no socket and no SOCKS5 port there is nowhere to go: the client gets a
// gateway error, not a hang.
func TestDriveDialWithoutTheSocket(t *testing.T) {
	dir := t.TempDir()
	stateMu.Lock()
	old := PC
	PC = newPathControl("", filepath.Join(dir, "tailscaled.sock"), "")
	stateMu.Unlock()
	defer func() {
		stateMu.Lock()
		PC = old
		stateMu.Unlock()
	}()
	GConfig.update("", "", "", "")
	if _, err := os.Stat(filepath.Join(dir, DriveSocketName)); err == nil {
		t.Fatal("the socket exists")
	}
	if c, err := driveDial(t.Context(), "tcp", "100.100.100.100:8080"); err == nil {
		c.Close()
		t.Fatal("dialled a daemon that is not there")
	}
}

// With the daemon gone the endpoint says so in a way the app tells apart from
// a device's failure.
func TestDriveLocalWithoutTheDaemon(t *testing.T) {
	addr := DriveLocalAddr()
	dir := t.TempDir()
	stateMu.Lock()
	old := PC
	PC = newPathControl("", filepath.Join(dir, "tailscaled.sock"), "")
	stateMu.Unlock()
	defer func() {
		stateMu.Lock()
		PC = old
		stateMu.Unlock()
	}()
	GConfig.update("", "", "", "")
	// The connections to the last test's daemon are what a restarted
	// daemon's are: gone.
	driveLocal.transport.CloseIdleConnections()
	c, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	io.WriteString(c, "PROPFIND http://100.100.100.100:8080/ HTTP/1.1\r\nHost: 100.100.100.100:8080\r\n"+DriveLocalHeader+": "+DriveLocalToken()+"\r\nContent-Length: 0\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable || resp.Header.Get(DriveErrorHeader) != DriveErrorNoDaemon {
		t.Fatalf("HTTP %d, %s=%q", resp.StatusCode, DriveErrorHeader, resp.Header.Get(DriveErrorHeader))
	}
}
