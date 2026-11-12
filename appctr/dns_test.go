package appctr

import (
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

// TestDNSQueryTypeName: every name must be one the daemon's dns-query endpoint
// takes (dnsMessageTypeForString), and a type it does not take must not be
// passed off as an A query.
func TestDNSQueryTypeName(t *testing.T) {
	cases := []struct {
		typ  dnsmessage.Type
		name string
		ok   bool
	}{
		{dnsmessage.TypeA, "A", true},
		{dnsmessage.TypeAAAA, "AAAA", true},
		{dnsmessage.TypeCNAME, "CNAME", true},
		{dnsmessage.TypeMX, "MX", true},
		{dnsmessage.TypeNS, "NS", true},
		{dnsmessage.TypePTR, "PTR", true},
		{dnsmessage.TypeSOA, "SOA", true},
		{dnsmessage.TypeSRV, "SRV", true},
		{dnsmessage.TypeTXT, "TXT", true},
		{dnsmessage.Type(65), "", false}, // HTTPS, which every browser asks
		{dnsmessage.Type(64), "", false}, // SVCB
		{dnsmessage.TypeALL, "", false},
		{dnsmessage.TypeOPT, "", false},
	}
	for _, c := range cases {
		name, ok := dnsQueryTypeName(c.typ)
		if name != c.name || ok != c.ok {
			t.Errorf("dnsQueryTypeName(%v) = %q, %v; want %q, %v", c.typ, name, ok, c.name, c.ok)
		}
	}
}

func TestMagicDNSQueryName(t *testing.T) {
	const suffix = "tail8a412.ts.net"
	cases := []struct{ domain, suffix, want string }{
		// The two short spellings.
		{"phone", suffix, "phone.tail8a412.ts.net"},
		{"phone.ts.net", suffix, "phone.tail8a412.ts.net"},
		// A full name, ours or anybody's, is left alone.
		{"phone.tail8a412.ts.net", suffix, "phone.tail8a412.ts.net"},
		{"app.tailother.ts.net", suffix, "app.tailother.ts.net"},
		{"a.b.tailother.ts.net", suffix, "a.b.tailother.ts.net"},
		{"shared.yak-bebop.ts.net", suffix, "shared.yak-bebop.ts.net"},
		{"example.com", suffix, "example.com"},
		{"ts.net", suffix, "ts.net"},
		// A tailnet named in words is three labels long itself.
		{"yak-bebop.ts.net", "yak-bebop.ts.net", "yak-bebop.ts.net"},
		{"phone.ts.net", "yak-bebop.ts.net", "phone.yak-bebop.ts.net"},
		// No ts.net tailnet (Headscale): there is no tailnet part to add.
		{"phone.ts.net", "hs.example.com", "phone.ts.net"},
		{"phone", "hs.example.com", "phone.hs.example.com"},
		// No tailnet yet.
		{"phone", "", "phone"},
		{"phone.ts.net", "", "phone.ts.net"},
	}
	for _, c := range cases {
		if got := magicDNSQueryName(c.domain, c.suffix); got != c.want {
			t.Errorf("magicDNSQueryName(%q, %q) = %q, want %q", c.domain, c.suffix, got, c.want)
		}
	}
}

func TestDNSAnswersQuestion(t *testing.T) {
	name := dnsmessage.MustNewName("example.com.")
	asked := dnsmessage.Question{Name: name, Type: dnsmessage.TypeTXT, Class: dnsmessage.ClassINET}
	same := dnsmessage.Message{Questions: []dnsmessage.Question{asked}}
	other := dnsmessage.Message{Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	if !dnsAnswersQuestion(same, asked) {
		t.Error("an answer to the question asked was refused")
	}
	if dnsAnswersQuestion(other, asked) {
		t.Error("an A answer passed for a TXT question")
	}
	if dnsAnswersQuestion(dnsmessage.Message{}, asked) {
		t.Error("an answer with no question passed")
	}
}
