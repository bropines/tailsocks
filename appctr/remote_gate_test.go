package appctr

// Who gets a connection, and what being taken for a peer is worth.

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"testing"
	"time"
)

// rtAnswered reads from a connection for a moment and reports what became of
// it: "closed" by the other side, still "open" and silent, or the bytes of an
// answer.
func rtAnswered(c net.Conn, wait time.Duration) string {
	buf := make([]byte, 2048)
	c.SetReadDeadline(time.Now().Add(wait))
	n, err := c.Read(buf)
	switch {
	case n > 0:
		return string(buf[:n])
	case errors.Is(err, os.ErrDeadlineExceeded):
		return "open"
	}
	return "closed"
}

// gateCounts is how many connections the endpoint is holding, and for how
// many nodes.
func (r *remoteRig) gateCounts() (total, nodes int) {
	r.m.mu.Lock()
	l := r.m.listener
	r.m.mu.Unlock()
	if l == nil || l.gate == nil {
		return 0, 0
	}
	l.gate.mu.Lock()
	defer l.gate.mu.Unlock()
	return l.gate.total, len(l.gate.nodes)
}

// A process on the device reaches the port with no tailnet in between. It can
// open as many connections as it likes, say nothing or anything, and keep
// them: none of that keeps a paired phone out.
func TestRemoteLocalConnectionsHoldNoSlots(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	var held []net.Conn
	defer func() {
		for _, c := range held {
			c.Close()
		}
	}()
	for i := 0; i < 3*remoteMaxConns; i++ {
		c, err := r.dialAs(nil)
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
		switch i % 3 {
		case 0: // silent
		case 1: // a whole request, then silence on a connection kept open
			c.Write([]byte("GET /v1/hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"))
		case 2: // half a request
			c.Write([]byte("POST /v1/pair HTTP/1.1\r\nHost: 100.64.0.1\r\nContent-Length: 4000\r\n\r\n{"))
		}
	}
	// While they are all still open on the local side, the phone is served.
	began := time.Now()
	for i := 0; i < 5; i++ {
		if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
			t.Fatalf("the paired phone with %d local connections open: %d %s", len(held), a.status, a.raw)
		}
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("the phone waited %v", took)
	}
	// Every one of them was closed from this side, unanswered, long before a
	// header or idle timeout would have got to it, and none ever had a slot.
	for i, c := range held {
		if got := rtAnswered(c, time.Second); got != "closed" {
			t.Fatalf("local connection %d: %s", i, got)
		}
	}
	if total, nodes := r.gateCounts(); total != 0 || nodes != 0 {
		t.Errorf("the endpoint still holds %d connections for %d nodes", total, nodes)
	}
}

// A tailnet node gets a few connections, not all of them: one that opens
// dozens and sits on them holds its own share, and the rest are served.
func TestRemoteConnectionsPerNode(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	var held []net.Conn
	defer func() {
		for _, c := range held {
			c.Close()
		}
	}()
	for i := 0; i < remoteMaxConns+8; i++ {
		c, err := r.dialAs(rtFriend)
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
	}
	open := 0
	for _, c := range held {
		if rtAnswered(c, 150*time.Millisecond) == "open" {
			open++
		}
	}
	if open != remoteMaxConnsPerNode {
		t.Errorf("%d of one node's %d connections were kept, want %d", open, len(held), remoteMaxConnsPerNode)
	}
	if total, nodes := r.gateCounts(); total != remoteMaxConnsPerNode || nodes != 1 {
		t.Errorf("the endpoint holds %d connections for %d nodes", total, nodes)
	}
	for i := 0; i < 5; i++ {
		if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
			t.Fatalf("the paired phone while another node sits on its connections: %d %s", a.status, a.raw)
		}
	}
	// Its share is its own to use up: one more connection is closed on it…
	if !r.shut(rtFriend, "GET", "/v1/hello", "", nil) {
		t.Error("a node over its share was served")
	}
	// …and its share comes back when it lets go.
	for _, c := range held {
		c.Close()
	}
	held = nil
	deadline := time.Now().Add(3 * time.Second)
	for {
		if a, err := r.try(rtFriend, "GET", "/v1/hello", "", nil, nil); err == nil && a.status == 200 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("a node that closed its connections is still shut out")
		}
		time.Sleep(10 * time.Millisecond)
	}
	for {
		total, nodes := r.gateCounts()
		if total == 0 && nodes == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("with everything closed the endpoint counts %d connections for %d nodes", total, nodes)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// rtSharePort dials addr from the given loopback source port, which another
// connection of this process may already be using for another destination; 0
// lets the kernel choose.
func rtSharePort(port int, addr string) (net.Conn, error) {
	d := net.Dialer{
		Timeout: 2 * time.Second,
		Control: func(_, _ string, c syscall.RawConn) error {
			var serr error
			if err := c.Control(func(fd uintptr) {
				serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
			}); err != nil {
				return err
			}
			return serr
		},
	}
	if port != 0 {
		d.LocalAddr = &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
	}
	return d.DialContext(context.Background(), "tcp4", addr)
}

// The daemon's answer to "who is this" is keyed by a loopback address and
// port, not by where that socket is connected to. A process on this device
// can therefore share a source port with a connection the daemon is forwarding
// for a peer to some other loopback port, and be taken for that peer. This
// builds exactly that — the paired phone "forwarded" to another local service,
// and a local process on the same source port connected to the endpoint — and
// checks what being the phone, and nothing more, gets it: the greeting that
// everybody gets.
func TestRemoteIdentityAloneOpensNothing(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	r.pair(rtFriend)
	dir := t.TempDir()
	r.m.taildropDir = func() string { return dir }
	photo := filepath.Join(dir, "photo.jpg")
	if err := os.WriteFile(photo, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}

	// Some other service on loopback, and the paired phone's connection to it
	// as netstack forwards it.
	other, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	go func() {
		for {
			c, err := other.Accept()
			if err != nil {
				return
			}
			go io.Copy(io.Discard, c)
		}
	}()
	forwarded, err := rtSharePort(0, other.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer forwarded.Close()
	mapped := r.d.register(forwarded.LocalAddr(), rtPhone.addr())
	defer r.d.unregister(mapped)

	// The local process, on that same source port, at the endpoint. Nothing
	// is registered for its connection; the daemon's map answers for the port.
	local, err := rtSharePort(int(mapped.Port()), r.addr)
	if err != nil {
		t.Skipf("this kernel would not share a source port between two destinations: %v", err)
	}
	if got := local.LocalAddr().(*net.TCPAddr).Port; got != int(mapped.Port()) {
		t.Fatalf("the local process is on port %d, the forwarded connection on %d", got, mapped.Port())
	}
	// One connection for everything it asks: the mapping is per connection.
	dialed := false
	client := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{
		MaxConnsPerHost: 1,
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			if dialed {
				return nil, errors.New("the borrowed connection was closed on the local process")
			}
			dialed = true
			return local, nil
		},
	}}
	defer client.CloseIdleConnections()
	ask := func(method, path, auth, body string) (int, string) {
		t.Helper()
		var rd io.Reader
		if body != "" {
			rd = strings.NewReader(body)
		}
		req, _ := http.NewRequest(method, "http://"+rtSelf.ip+":28115"+path, rd)
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		if auth != "" {
			req.Header.Set("Authorization", "Bearer "+auth)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatalf("%s %s on the borrowed port: %v", method, path, err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, strings.TrimSpace(string(raw))
	}

	// It is taken for the phone: it gets an answer where a local process
	// otherwise gets a closed connection.
	status, hello := ask("GET", "/v1/hello", "", "")
	if status != 200 {
		t.Fatalf("hello on a borrowed port: %d %s", status, hello)
	}
	// And the answer is the one every caller gets: these fields, none of them
	// about the caller, the same bytes another user's phone is sent.
	var fields map[string]any
	if err := json.Unmarshal([]byte(hello), &fields); err != nil {
		t.Fatal(err)
	}
	var keys []string
	for k := range fields {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	if got := strings.Join(keys, ","); got != "api,app,deviceClass,name,nodeId,pairing" {
		t.Errorf("hello says: %s", got)
	}
	if a := r.call(rtFriend, "GET", "/v1/hello", "", nil); strings.TrimSpace(a.raw) != hello {
		t.Errorf("hello differs by caller:\n as the phone: %s\n as a guest:   %s", hello, a.raw)
	}

	// Everything else wants the token that was issued to the phone, which a
	// process that merely sits on its port does not have.
	calls := len(r.app.called())
	for _, rt := range remoteRoutes {
		if rt.op == "" {
			continue
		}
		body := ""
		if rt.method == "POST" {
			body = `{"key":"accept_dns","value":"false","action":"reconnect","id":"","name":"photo.jpg"}`
		}
		for _, auth := range []string{"", strings.Repeat("A", 43), remoteHashToken(token)} {
			if status, raw := ask(rt.method, rt.path, auth, body); status != 401 {
				t.Errorf("%s %s as the phone, without its token: %d %s", rt.method, rt.path, status, raw)
			}
		}
	}
	// Pairing wants the code from the screen, for the phone as for anyone.
	for _, body := range []string{`{"code":""}`, `{}`, ``} {
		if status, raw := ask("POST", "/v1/pair", "", body); status != 400 {
			t.Errorf("pair with %q and no code: %d %s", body, status, raw)
		}
	}
	code := r.code()
	if status, raw := ask("POST", "/v1/pair", "", `{"code":"`+wrongCode(code)+`"}`); status != 403 || !strings.Contains(raw, `"wrong"`) {
		t.Errorf("pair with a guess: %d %s", status, raw)
	}
	// Nothing happened to anyone.
	if got := len(r.app.called()); got != calls {
		t.Errorf("the app was called: %q", r.app.called()[calls:])
	}
	if _, err := os.Stat(photo); err != nil {
		t.Error("a file is gone")
	}
	if list := r.m.paired(); len(list) != 2 {
		t.Errorf("pairings: %+v", list)
	}
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("the phone's own token afterwards: %d %s", a.status, a.raw)
	}

	// Once the forwarded connection is gone the same process, on the same
	// connection, is nobody again: asked about with every request.
	r.d.unregister(mapped)
	if status, raw := ask("GET", "/v1/hello", "", ""); status != 403 {
		t.Errorf("hello with the mapping gone: %d %s", status, raw)
	}
}
