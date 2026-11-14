package appctr

import (
	"encoding/json"
	"strings"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

// The serve config reaches Kotlin with its ETag inside; an emptied config
// once came out as `{"etag":"…", }` and broke the Serve screen's parse.
func TestSpliceEtag(t *testing.T) {
	for _, c := range []struct{ body, want string }{
		{"", `{"etag":"e1"}`},
		{"null", `{"etag":"e1"}`},
		{"{}", `{"etag":"e1"}`},
		{"  {}\n", `{"etag":"e1"}`},
		{`{"TCP":{"443":{"HTTPS":true}}}`, `{"etag":"e1","TCP":{"443":{"HTTPS":true}}}`},
		{"\n\t{\"Web\":{}}\n", `{"etag":"e1","Web":{}}`},
	} {
		got := spliceEtag(c.body, "e1")
		if got != c.want {
			t.Errorf("spliceEtag(%q) = %q, want %q", c.body, got, c.want)
		}
		var v map[string]any
		if err := json.Unmarshal([]byte(got), &v); err != nil || v["etag"] != "e1" {
			t.Errorf("spliceEtag(%q) = %q is not JSON with the etag: %v", c.body, got, err)
		}
	}
	// An ETag with a quote in it stays JSON.
	if got := spliceEtag("{}", `W/"x"`); got != `{"etag":"W/\"x\""}` {
		t.Errorf("quoted etag: %q", got)
	}
}

// The control proxy fails closed: a URL tailscaled's proxy.FromEnvironment
// would ignore must be refused, not silently dropped.
func TestValidateProxyURL(t *testing.T) {
	for _, ok := range []string{
		"socks5://127.0.0.1:1080",
		"socks5h://proxy.lan:1080",
		"http://user:pass@10.0.0.1:3128",
		"HTTPS://proxy.example:443/",
		"socks5://[::1]:1080",
	} {
		if err := validateProxyURL(ok); err != nil {
			t.Errorf("validateProxyURL(%q) = %v, want ok", ok, err)
		}
	}
	for _, bad := range []string{
		"",
		"127.0.0.1:1080",              // no scheme
		"ftp://proxy:21",              // unsupported scheme
		"socks5://:1080",              // no host
		"http://proxy.lan",            // no port
		"http://u:p#ss@proxy.lan:80",  // an unescaped # ends the URL early
		"http://u:p?ss@proxy.lan:80",  // an unescaped ? likewise
		"http://proxy.lan:80/path",    // a path
		"socks5://proxy.lan:1080?x=y", // a query
		"http://[::1",                 // unparseable
	} {
		if err := validateProxyURL(bad); err == nil {
			t.Errorf("validateProxyURL(%q) accepted", bad)
		}
	}
}

// The log buffer is exportable: a proxy's password never reaches it.
func TestRedactProxyURL(t *testing.T) {
	for _, c := range []struct{ in, want string }{
		{"http://user:secret@proxy.lan:3128", "http://user:%2A%2A%2A@proxy.lan:3128"},
		{"socks5://user@proxy.lan:1080", "socks5://user@proxy.lan:1080"},
		{"socks5://proxy.lan:1080", "socks5://proxy.lan:1080"},
		{"http://[::1", "<unparseable proxy url>"},
	} {
		got := redactProxyURL(c.in)
		if got != c.want {
			t.Errorf("redactProxyURL(%q) = %q, want %q", c.in, got, c.want)
		}
		if strings.Contains(got, "secret") {
			t.Errorf("redactProxyURL(%q) leaks the password: %q", c.in, got)
		}
	}
}

func dnsQueryMsg(t *testing.T, name string, typ dnsmessage.Type) (dnsmessage.Message, dnsmessage.Question) {
	t.Helper()
	q := dnsmessage.Question{Name: mustName(t, name), Type: typ, Class: dnsmessage.ClassINET}
	return dnsmessage.Message{Header: dnsmessage.Header{ID: 42, RecursionDesired: true}, Questions: []dnsmessage.Question{q}}, q
}

// A tailnet name is answered from memory: A for IPv4 addresses, AAAA only for
// real IPv6 — an IPv4 peer asked for AAAA is NODATA, not a ::ffff: mapped
// address — and no usable address is NODATA too, never nil (which would send
// the name on to a public resolver).
func TestPackDNSResponse(t *testing.T) {
	ips := []string{"100.64.0.5", "fd7a:115c:a1e0::5", "not an ip"}

	msg, q := dnsQueryMsg(t, "nas.tail1.ts.net.", dnsmessage.TypeA)
	m := unpack(t, packDNSResponse(msg, q, ips, nil))
	if !m.Response || !m.Authoritative || m.ID != 42 || m.RCode != dnsmessage.RCodeSuccess || len(m.Answers) != 1 {
		t.Fatalf("A: %+v", m)
	}
	if a, ok := m.Answers[0].Body.(*dnsmessage.AResource); !ok || a.A != [4]byte{100, 64, 0, 5} || m.Answers[0].Header.TTL != 60 {
		t.Errorf("A answer: %+v", m.Answers[0])
	}

	msg, q = dnsQueryMsg(t, "nas.tail1.ts.net.", dnsmessage.TypeAAAA)
	m = unpack(t, packDNSResponse(msg, q, ips, nil))
	if len(m.Answers) != 1 {
		t.Fatalf("AAAA: %d answers, want the IPv6 address alone", len(m.Answers))
	}
	if a, ok := m.Answers[0].Body.(*dnsmessage.AAAAResource); !ok || a.AAAA[0] != 0xfd {
		t.Errorf("AAAA answer: %+v", m.Answers[0])
	}

	// An IPv4-only peer asked for AAAA: NODATA.
	m = unpack(t, packDNSResponse(msg, q, []string{"100.64.0.5"}, nil))
	if m.RCode != dnsmessage.RCodeSuccess || len(m.Answers) != 0 || !m.Response {
		t.Errorf("AAAA for an IPv4 peer: %+v, want NODATA", m)
	}

	// No address at all: still a well-formed answer.
	if b := packDNSResponse(msg, q, nil, nil); b == nil {
		t.Error("no addresses gave no response")
	}
}

func TestPackNoDataAndServFail(t *testing.T) {
	msg, _ := dnsQueryMsg(t, "gone.tail1.ts.net.", dnsmessage.TypeA)
	msg.Answers = []dnsmessage.Resource{{
		Header: dnsmessage.ResourceHeader{Name: msg.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET},
		Body:   &dnsmessage.AResource{A: [4]byte{1, 2, 3, 4}},
	}}

	m := unpack(t, packNoData(msg))
	if !m.Response || !m.Authoritative || m.RCode != dnsmessage.RCodeSuccess || len(m.Answers)+len(m.Authorities)+len(m.Additionals) != 0 || len(m.Questions) != 1 || m.ID != 42 {
		t.Errorf("NODATA: %+v", m)
	}

	m = unpack(t, packServFail(msg))
	if !m.Response || m.Authoritative || m.RCode != dnsmessage.RCodeServerFailure || len(m.Answers) != 0 || len(m.Questions) != 1 || m.ID != 42 {
		t.Errorf("SERVFAIL: %+v", m)
	}
}

// Every in-process SOCKS consumer dials the proxy's listen address: a
// wildcard one, bound so the LAN can reach it, is dialled over loopback.
func TestDialableAddr(t *testing.T) {
	for _, c := range []struct{ in, want string }{
		{"", ""},
		{"0.0.0.0:1055", "127.0.0.1:1055"},
		{":1055", "127.0.0.1:1055"},
		{"[::]:1055", "[::1]:1055"},
		{"127.0.0.1:1055", "127.0.0.1:1055"},
		{"192.168.1.20:1055", "192.168.1.20:1055"},
		{"[fd00::1]:1055", "[fd00::1]:1055"},
		{"localhost:1055", "localhost:1055"},
		{"not an address", "not an address"},
	} {
		if got := dialableAddr(c.in); got != c.want {
			t.Errorf("dialableAddr(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
