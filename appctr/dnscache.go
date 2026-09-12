package appctr

// dnscache.go — a TTL-bound cache of DNS answers in front of processDNSQuery.
//
// Keyed by question (name, type, class). A positive answer lives for the
// smallest TTL in its answer section, a negative one (NXDOMAIN, NODATA) for a
// short fixed time, both clamped to [dnsCacheMinTTL, dnsCacheMaxTTL]. A hit is
// re-stamped with the client's transaction ID and question spelling, and its
// TTLs are counted down, so the client sees the honest remaining lifetime.
// Nothing here overrides upstream TTLs beyond the clamp: domains that rotate
// records quickly keep rotating.

import (
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	dnsCacheMinTTL   = 10 * time.Second
	dnsCacheMaxTTL   = time.Hour
	dnsCacheNegTTL   = 30 * time.Second
	dnsCacheMaxItems = 1000
)

// dnsCacheNow is time.Now, replaceable in tests.
var dnsCacheNow = time.Now

type dnsCacheKey struct {
	name  string
	qtype dnsmessage.Type
	class dnsmessage.Class
}

type dnsCacheEntry struct {
	msg     dnsmessage.Message // as received, TTLs untouched
	stored  time.Time
	expires time.Time
}

var dnsRespCache = struct {
	sync.Mutex
	m map[dnsCacheKey]*dnsCacheEntry
}{m: map[dnsCacheKey]*dnsCacheEntry{}}

// dnsCacheFlush drops every cached answer. Called with the other DNS caches
// (profile or daemon change) and when the split-DNS routes change.
func dnsCacheFlush() {
	dnsRespCache.Lock()
	dnsRespCache.m = map[dnsCacheKey]*dnsCacheEntry{}
	dnsRespCache.Unlock()
}

// dnsCacheLen is the number of cached answers, expired ones included.
func dnsCacheLen() int {
	dnsRespCache.Lock()
	defer dnsRespCache.Unlock()
	return len(dnsRespCache.m)
}

// dnsQueryKey parses a raw query far enough to key the cache. ok is false for
// anything but a plain single-question query.
func dnsQueryKey(query []byte) (hdr dnsmessage.Header, q dnsmessage.Question, key dnsCacheKey, ok bool) {
	var p dnsmessage.Parser
	hdr, err := p.Start(query)
	if err != nil || hdr.Response {
		return hdr, q, key, false
	}
	q, err = p.Question()
	if err != nil {
		return hdr, q, key, false
	}
	if _, err := p.Question(); err != dnsmessage.ErrSectionDone {
		return hdr, q, key, false
	}
	key = dnsCacheKey{
		name:  strings.ToLower(strings.TrimSuffix(q.Name.String(), ".")),
		qtype: q.Type,
		class: q.Class,
	}
	return hdr, q, key, true
}

// dnsCacheLookup answers query from the cache. ok is false on a miss.
func dnsCacheLookup(query []byte) (resp []byte, ok bool) {
	hdr, q, key, ok := dnsQueryKey(query)
	if !ok {
		return nil, false
	}
	now := dnsCacheNow()

	dnsRespCache.Lock()
	e := dnsRespCache.m[key]
	if e == nil {
		dnsRespCache.Unlock()
		return nil, false
	}
	if !now.Before(e.expires) {
		delete(dnsRespCache.m, key)
		dnsRespCache.Unlock()
		return nil, false
	}
	msg := cloneDNSMessage(e.msg)
	stored := e.stored
	dnsRespCache.Unlock()

	elapsed := uint32(now.Sub(stored) / time.Second)
	msg.Header.ID = hdr.ID
	msg.Header.RecursionDesired = hdr.RecursionDesired
	if len(msg.Questions) > 0 {
		// Keep the client's spelling: some clients randomise the case of the
		// question (0x20 encoding) and check it on the way back.
		msg.Questions[0].Name = q.Name
	}
	countDown(msg.Answers, elapsed)
	countDown(msg.Authorities, elapsed)
	countDown(msg.Additionals, elapsed)
	if !hasOPT(query) {
		msg.Additionals = withoutOPT(msg.Additionals)
	}
	packed, err := msg.Pack()
	if err != nil {
		return nil, false
	}
	return packed, true
}

// dnsCacheStore remembers resp as the answer to query when it is cacheable:
// a complete, untruncated NOERROR or NXDOMAIN response to that same question.
func dnsCacheStore(query, resp []byte) {
	_, q, key, ok := dnsQueryKey(query)
	if !ok {
		return
	}
	var msg dnsmessage.Message
	if err := msg.Unpack(resp); err != nil {
		return
	}
	if !msg.Header.Response || msg.Header.Truncated || len(msg.Questions) != 1 {
		return
	}
	rq := msg.Questions[0]
	if rq.Type != q.Type || rq.Class != q.Class || !strings.EqualFold(rq.Name.String(), q.Name.String()) {
		return
	}

	var ttl time.Duration
	switch msg.Header.RCode {
	case dnsmessage.RCodeSuccess:
		if len(msg.Answers) == 0 {
			ttl = negativeTTL(msg)
		} else {
			ttl = minTTL(msg.Answers)
		}
	case dnsmessage.RCodeNameError:
		ttl = negativeTTL(msg)
	default:
		return // SERVFAIL, REFUSED, …: an error, not an answer
	}
	if ttl < dnsCacheMinTTL {
		ttl = dnsCacheMinTTL
	}
	if ttl > dnsCacheMaxTTL {
		ttl = dnsCacheMaxTTL
	}

	now := dnsCacheNow()
	dnsRespCache.Lock()
	defer dnsRespCache.Unlock()
	if len(dnsRespCache.m) >= dnsCacheMaxItems {
		evictLocked(now)
	}
	dnsRespCache.m[key] = &dnsCacheEntry{msg: msg, stored: now, expires: now.Add(ttl)}
}

// minTTL is the smallest TTL in rs, ignoring OPT pseudo-records whose TTL
// field carries flags.
func minTTL(rs []dnsmessage.Resource) time.Duration {
	min := uint32(0)
	found := false
	for _, r := range rs {
		if r.Header.Type == dnsmessage.TypeOPT {
			continue
		}
		if !found || r.Header.TTL < min {
			min = r.Header.TTL
			found = true
		}
	}
	if !found {
		return 0
	}
	return time.Duration(min) * time.Second
}

// negativeTTL follows RFC 2308: the SOA in the authority section bounds how
// long a negative answer may be kept, capped by dnsCacheNegTTL.
func negativeTTL(msg dnsmessage.Message) time.Duration {
	ttl := dnsCacheNegTTL
	for _, r := range msg.Authorities {
		soa, ok := r.Body.(*dnsmessage.SOAResource)
		if !ok {
			continue
		}
		bound := r.Header.TTL
		if soa.MinTTL < bound {
			bound = soa.MinTTL
		}
		if d := time.Duration(bound) * time.Second; d < ttl {
			ttl = d
		}
	}
	return ttl
}

// evictLocked drops expired entries, then — if still full — the one that
// expires soonest. The map is small (dnsCacheMaxItems), a scan is fine.
func evictLocked(now time.Time) {
	for k, e := range dnsRespCache.m {
		if !now.Before(e.expires) {
			delete(dnsRespCache.m, k)
		}
	}
	if len(dnsRespCache.m) < dnsCacheMaxItems {
		return
	}
	var victim dnsCacheKey
	var soonest time.Time
	for k, e := range dnsRespCache.m {
		if soonest.IsZero() || e.expires.Before(soonest) {
			victim, soonest = k, e.expires
		}
	}
	delete(dnsRespCache.m, victim)
}

func countDown(rs []dnsmessage.Resource, elapsed uint32) {
	for i := range rs {
		if rs[i].Header.Type == dnsmessage.TypeOPT {
			continue
		}
		if rs[i].Header.TTL > elapsed {
			rs[i].Header.TTL -= elapsed
		} else {
			rs[i].Header.TTL = 0
		}
	}
}

// hasOPT reports whether the raw query carries an EDNS0 OPT record.
func hasOPT(query []byte) bool {
	var p dnsmessage.Parser
	if _, err := p.Start(query); err != nil {
		return false
	}
	if err := p.SkipAllQuestions(); err != nil {
		return false
	}
	if err := p.SkipAllAnswers(); err != nil {
		return false
	}
	if err := p.SkipAllAuthorities(); err != nil {
		return false
	}
	for {
		h, err := p.AdditionalHeader()
		if err != nil {
			return false
		}
		if h.Type == dnsmessage.TypeOPT {
			return true
		}
		if err := p.SkipAdditional(); err != nil {
			return false
		}
	}
}

func withoutOPT(rs []dnsmessage.Resource) []dnsmessage.Resource {
	out := rs[:0:0]
	for _, r := range rs {
		if r.Header.Type != dnsmessage.TypeOPT {
			out = append(out, r)
		}
	}
	return out
}

// cloneDNSMessage copies the sections so a hit can be edited without touching
// the cached original. Resource bodies are shared: they are never modified.
func cloneDNSMessage(m dnsmessage.Message) dnsmessage.Message {
	c := m
	c.Questions = append([]dnsmessage.Question(nil), m.Questions...)
	c.Answers = append([]dnsmessage.Resource(nil), m.Answers...)
	c.Authorities = append([]dnsmessage.Resource(nil), m.Authorities...)
	c.Additionals = append([]dnsmessage.Resource(nil), m.Additionals...)
	return c
}
