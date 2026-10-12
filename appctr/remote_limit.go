package appctr

import (
	"sync"
	"time"
)

// remoteLimiter is a token bucket per key: rate tokens a second, burst at
// most. The remote-management endpoint keeps three — every request before the
// daemon is asked who sent it, requests per calling node, and pairing attempts
// per calling node. golang.org/x/time/rate would do the arithmetic but not the
// bounded per-key table, which is most of this file.
type remoteLimiter struct {
	rate  float64
	burst float64

	mu      sync.Mutex
	buckets map[string]*remoteBucket
}

type remoteBucket struct {
	tokens float64
	at     time.Time
}

// remoteLimiterKeys bounds the table. The keys are node ids from whois, so it
// can only grow as far as the tailnet is large; this is for the tailnet that
// is very large.
const remoteLimiterKeys = 512

func newRemoteLimiter(rate, burst float64) *remoteLimiter {
	return &remoteLimiter{rate: rate, burst: burst, buckets: map[string]*remoteBucket{}}
}

// allow takes one token from key's bucket and reports whether there was one.
func (l *remoteLimiter) allow(key string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[key]
	if b == nil {
		if len(l.buckets) >= remoteLimiterKeys {
			l.pruneLocked(now)
		}
		b = &remoteBucket{tokens: l.burst, at: now}
		l.buckets[key] = b
	}
	b.tokens += now.Sub(b.at).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// pruneLocked forgets the buckets that have refilled — a key nobody has used
// for a while is indistinguishable from a new one — and, if that is not
// enough, all of them: a moment of generosity beats unbounded memory.
func (l *remoteLimiter) pruneLocked(now time.Time) {
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.at).Seconds()*l.rate >= l.burst {
			delete(l.buckets, k)
		}
	}
	if len(l.buckets) >= remoteLimiterKeys {
		l.buckets = map[string]*remoteBucket{}
	}
}
