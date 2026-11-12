package appctr

import (
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

// With the rescue off an unsupported question for a public name is answered
// here (NODATA), not handed to a public resolver.
func TestStrictProxyKeepsUnknownTypesHome(t *testing.T) {
	dnsRescueOff.Store(true)
	defer dnsRescueOff.Store(false)
	var msg dnsmessage.Message
	msg.RecursionDesired = true
	msg.Questions = []dnsmessage.Question{{
		Name: dnsmessage.MustNewName("blocked.example."), Type: dnsmessage.TypeHTTPS, Class: dnsmessage.ClassINET,
	}}
	query, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	// An address nothing listens on: a fallback attempt would fail the test by
	// timing out rather than answering at once.
	res := processDNSQuery(query, []string{"127.0.0.1:1"}, "")
	var out dnsmessage.Message
	if err := out.Unpack(res); err != nil {
		t.Fatalf("no answer: %v", err)
	}
	if !out.Response || out.RCode != dnsmessage.RCodeSuccess || len(out.Answers) != 0 {
		t.Fatalf("want NODATA, got rcode %v with %d answers", out.RCode, len(out.Answers))
	}
}

func TestPackServFail(t *testing.T) {
	var msg dnsmessage.Message
	msg.ID = 7
	msg.Questions = []dnsmessage.Question{{
		Name: dnsmessage.MustNewName("example.com."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET,
	}}
	var out dnsmessage.Message
	if err := out.Unpack(packServFail(msg)); err != nil {
		t.Fatal(err)
	}
	if out.ID != 7 || !out.Response || out.RCode != dnsmessage.RCodeServerFailure {
		t.Fatalf("got id %d response %v rcode %v", out.ID, out.Response, out.RCode)
	}
}
