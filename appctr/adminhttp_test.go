package appctr

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func decodeProxied(t *testing.T, raw string) proxiedHTTPResponse {
	t.Helper()
	var r proxiedHTTPResponse
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		t.Fatalf("bridge answer is not JSON: %v: %s", err, raw)
	}
	return r
}

func TestFetchViaProxyDirect(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("X-Tailscale-Request-Id", "req-123")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(r.Method + " " + r.Header.Get("Authorization") + " " + string(body)))
	}))
	defer srv.Close()

	r := decodeProxied(t, FetchViaProxy("POST", srv.URL+"/x", `{"Authorization":"Bearer t"}`, "hello", "", 5))
	if r.Error != "" || r.Status != http.StatusAccepted {
		t.Fatalf("unexpected answer %+v", r)
	}
	if r.Body != "POST Bearer t hello" {
		t.Fatalf("body %q", r.Body)
	}
	if r.Headers["X-Tailscale-Request-Id"] != "req-123" {
		t.Fatalf("request id lost: %+v", r.Headers)
	}
}

func TestFetchViaProxyReusesOneTransportPerProxy(t *testing.T) {
	a, _ := adminTransport("")
	b, _ := adminTransport("")
	if a == nil || a != b {
		t.Fatalf("direct transport not reused")
	}
	p1, _ := adminTransport("socks5://127.0.0.1:1")
	p2, _ := adminTransport("socks5://127.0.0.1:1")
	if p1 == nil || p1 != p2 || p1 == a {
		t.Fatalf("proxy transport not reused per configuration")
	}
}

func TestFetchViaProxyKeepsCredentialsOutOfErrors(t *testing.T) {
	// A listener that accepts and hangs up: the SOCKS5 handshake fails mid-way.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
		}
	}()

	cases := []string{
		"socks5://alice:s3cr3t-pass@" + ln.Addr().String(),
		"http://alice:s3cr3t-pass@" + ln.Addr().String(),
		"socks5://alice:s3cr3t-pass@[::1", // unparsable
		"socks5://alice:s3cr3t%2Dpass@" + ln.Addr().String(),
	}
	for _, p := range cases {
		r := decodeProxied(t, FetchViaProxy("GET", "https://api.example.invalid/api/v2/x", "", "", p, 3))
		if r.Error == "" {
			t.Fatalf("%s: expected an error, got %+v", p, r)
		}
		for _, secret := range []string{"s3cr3t-pass", "s3cr3t%2Dpass", "alice"} {
			if strings.Contains(r.Error, secret) {
				t.Fatalf("%s: error leaks %q: %s", p, secret, r.Error)
			}
		}
	}
}

func TestRedactProxy(t *testing.T) {
	got := redactProxy("dial socks5://u%40x:p%3Ay@h:1 failed for u@x and p:y", "socks5://u%40x:p%3Ay@h:1")
	for _, s := range []string{"u%40x", "p%3Ay", "u@x", "p:y"} {
		if strings.Contains(got, s) {
			t.Fatalf("still contains %q: %s", s, got)
		}
	}
	if redactProxy("plain", "") != "plain" || redactProxy("plain", "socks5://h:1") != "plain" {
		t.Fatal("changed a message with nothing to redact")
	}
}
