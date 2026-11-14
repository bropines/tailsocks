package appctr

import (
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func mustName(t *testing.T, s string) dnsmessage.Name {
	n, err := dnsmessage.NewName(s)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func testQuery(t *testing.T, id uint16, name string, typ dnsmessage.Type) []byte {
	m := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: mustName(t, name), Type: typ, Class: dnsmessage.ClassINET}},
	}
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func testAnswer(t *testing.T, query []byte, ttl uint32, rcode dnsmessage.RCode, withAnswer bool) []byte {
	var q dnsmessage.Message
	if err := q.Unpack(query); err != nil {
		t.Fatal(err)
	}
	m := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: q.Header.ID, Response: true, RCode: rcode, RecursionAvailable: true},
		Questions: q.Questions,
	}
	if withAnswer {
		m.Answers = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
			Body:   &dnsmessage.AResource{A: [4]byte{100, 64, 0, 1}},
		}}
	}
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func unpack(t *testing.T, b []byte) dnsmessage.Message {
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		t.Fatal(err)
	}
	return m
}

func withClock(t *testing.T, start time.Time) func(d time.Duration) {
	now := start
	dnsCacheNow = func() time.Time { return now }
	dnsCacheFlush()
	t.Cleanup(func() { dnsCacheNow = time.Now; dnsCacheFlush() })
	return func(d time.Duration) { now = now.Add(d) }
}

func TestDNSCacheHitRestampsIDAndCountsDown(t *testing.T) {
	advance := withClock(t, time.Unix(1_700_000_000, 0))
	q1 := testQuery(t, 0x1111, "svc.example.com.", dnsmessage.TypeA)
	dnsCacheStore(q1, testAnswer(t, q1, 300, dnsmessage.RCodeSuccess, true))

	advance(40 * time.Second)
	q2 := testQuery(t, 0x2222, "SVC.Example.COM.", dnsmessage.TypeA) // other ID, other case
	resp, ok := dnsCacheLookup(q2)
	if !ok {
		t.Fatal("expected a hit")
	}
	m := unpack(t, resp)
	if m.Header.ID != 0x2222 {
		t.Errorf("ID = %#x, want the client's 0x2222", m.Header.ID)
	}
	if got := m.Questions[0].Name.String(); got != "SVC.Example.COM." {
		t.Errorf("question spelling = %q, want the client's", got)
	}
	if len(m.Answers) != 1 || m.Answers[0].Header.TTL != 260 {
		t.Fatalf("answers = %+v, want one A with TTL 260", m.Answers)
	}
}

func TestDNSCacheExpiresAtTheAnswersTTL(t *testing.T) {
	advance := withClock(t, time.Unix(1_700_000_000, 0))
	q := testQuery(t, 1, "a.example.com.", dnsmessage.TypeA)
	dnsCacheStore(q, testAnswer(t, q, 120, dnsmessage.RCodeSuccess, true))
	advance(119 * time.Second)
	if _, ok := dnsCacheLookup(q); !ok {
		t.Fatal("expected a hit one second before expiry")
	}
	advance(2 * time.Second)
	if _, ok := dnsCacheLookup(q); ok {
		t.Fatal("expected a miss after the TTL")
	}
	if dnsCacheLen() != 0 {
		t.Errorf("expired entry not dropped, len = %d", dnsCacheLen())
	}
}

func TestDNSCacheClampsTTL(t *testing.T) {
	advance := withClock(t, time.Unix(1_700_000_000, 0))
	short := testQuery(t, 1, "short.example.com.", dnsmessage.TypeA)
	dnsCacheStore(short, testAnswer(t, short, 1, dnsmessage.RCodeSuccess, true))
	long := testQuery(t, 2, "long.example.com.", dnsmessage.TypeA)
	dnsCacheStore(long, testAnswer(t, long, 86400, dnsmessage.RCodeSuccess, true))

	advance(dnsCacheMinTTL - time.Second)
	if _, ok := dnsCacheLookup(short); !ok {
		t.Error("a 1 s TTL should be kept for the floor")
	}
	advance(dnsCacheMaxTTL)
	if _, ok := dnsCacheLookup(long); ok {
		t.Error("a day-long TTL should be capped at the ceiling")
	}
}

func TestDNSCacheNegativeAndErrors(t *testing.T) {
	advance := withClock(t, time.Unix(1_700_000_000, 0))
	nx := testQuery(t, 1, "nx.example.com.", dnsmessage.TypeA)
	dnsCacheStore(nx, testAnswer(t, nx, 0, dnsmessage.RCodeNameError, false))
	nodata := testQuery(t, 2, "nodata.example.com.", dnsmessage.TypeAAAA)
	dnsCacheStore(nodata, testAnswer(t, nodata, 0, dnsmessage.RCodeSuccess, false))
	fail := testQuery(t, 3, "fail.example.com.", dnsmessage.TypeA)
	dnsCacheStore(fail, testAnswer(t, fail, 0, dnsmessage.RCodeServerFailure, false))

	if _, ok := dnsCacheLookup(fail); ok {
		t.Error("SERVFAIL must not be cached")
	}
	if resp, ok := dnsCacheLookup(nx); !ok || unpack(t, resp).Header.RCode != dnsmessage.RCodeNameError {
		t.Error("NXDOMAIN should be served from the cache")
	}
	if _, ok := dnsCacheLookup(nodata); !ok {
		t.Error("NODATA should be served from the cache")
	}
	advance(dnsCacheNegTTL + time.Second)
	if _, ok := dnsCacheLookup(nx); ok {
		t.Error("a negative answer should expire after dnsCacheNegTTL")
	}
}

func TestDNSCacheIgnoresMismatchedAndTruncated(t *testing.T) {
	withClock(t, time.Unix(1_700_000_000, 0))
	q := testQuery(t, 1, "a.example.com.", dnsmessage.TypeA)
	other := testQuery(t, 1, "b.example.com.", dnsmessage.TypeA)
	dnsCacheStore(q, testAnswer(t, other, 60, dnsmessage.RCodeSuccess, true))
	if _, ok := dnsCacheLookup(q); ok {
		t.Error("an answer for another question must not be stored")
	}
	tc := unpack(t, testAnswer(t, q, 60, dnsmessage.RCodeSuccess, true))
	tc.Header.Truncated = true
	b, _ := tc.Pack()
	dnsCacheStore(q, b)
	if _, ok := dnsCacheLookup(q); ok {
		t.Error("a truncated answer must not be stored")
	}
}

func TestDNSCacheEvictsWhenFull(t *testing.T) {
	withClock(t, time.Unix(1_700_000_000, 0))
	for i := 0; i < dnsCacheMaxItems+5; i++ {
		q := testQuery(t, uint16(i), "n"+string(rune('a'+i%26))+string(rune('a'+(i/26)%26))+string(rune('a'+(i/676)%26))+".example.com.", dnsmessage.TypeA)
		dnsCacheStore(q, testAnswer(t, q, 300, dnsmessage.RCodeSuccess, true))
	}
	if n := dnsCacheLen(); n > dnsCacheMaxItems {
		t.Errorf("cache grew to %d, cap is %d", n, dnsCacheMaxItems)
	}
}

// The one parse the proxy now does per query carries what the cache and the
// OPT check need.
func TestParseDNSQuery(t *testing.T) {
	plain := testQuery(t, 7, "NAS.tail1.ts.net.", dnsmessage.TypeA)
	d := parseDNSQuery(plain)
	if !d.ok || d.opt || d.hdr.ID != 7 || d.key.name != "nas.tail1.ts.net" || d.q.Name.String() != "NAS.tail1.ts.net." {
		t.Errorf("plain query: %+v", d)
	}

	m := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 8, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: mustName(t, "example.com."), Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET}},
	}
	var opt dnsmessage.ResourceHeader
	if err := opt.SetEDNS0(1232, dnsmessage.RCodeSuccess, false); err != nil {
		t.Fatal(err)
	}
	m.Additionals = []dnsmessage.Resource{{Header: opt, Body: &dnsmessage.OPTResource{}}}
	withOPT, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if d := parseDNSQuery(withOPT); !d.ok || !d.opt || d.key.qtype != dnsmessage.TypeAAAA {
		t.Errorf("query with OPT: %+v", d)
	}

	if d := parseDNSQuery(testAnswer(t, plain, 60, dnsmessage.RCodeSuccess, true)); d.ok {
		t.Error("a response was taken for a query")
	}
	if d := parseDNSQuery([]byte{1, 2, 3}); d.ok {
		t.Error("garbage was taken for a query")
	}
}

func BenchmarkDNSCacheHit(b *testing.B) {
	t := &testing.T{}
	q := testQuery(t, 1, "nas.tail1.ts.net.", dnsmessage.TypeA)
	dnsCacheStore(q, testAnswer(t, q, 300, dnsmessage.RCodeSuccess, true))
	b.ReportAllocs()
	for b.Loop() {
		d := parseDNSQuery(q)
		if _, ok := dnsCacheLookupParsed(d); !ok {
			b.Fatal("miss")
		}
	}
}
