package appctr

// dns.go — Pure DNS resolution: MagicDNS cache lookup, split-DNS forwarding,
// SOCKS5/DoH fallbacks, and the local UDP DNS proxy.
// IPN Bus state (caches, listener) lives in bus.go.

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/proxy"
)

func startDNSProxy(ctx context.Context, listenAddr string, fallbacks []string, dohUrl string) error {
	pc, err := net.ListenPacket("udp", listenAddr)
	if err != nil {
		return fmt.Errorf("dns proxy listen failed: %w", err)
	}
	defer pc.Close()
	slog.Info("DNS proxy listening", "addr", listenAddr)

	EnsureIPNBusListener()

	go func() {
		<-ctx.Done()
		pc.Close()
	}()

	buf := make([]byte, 65535)
	for {
		n, clientAddr, err := pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		query := make([]byte, n)
		copy(query, buf[:n])
		go func(q []byte, cAddr net.Addr) {
			resp := processDNSQuery(q, fallbacks, dohUrl)
			if resp != nil {
				_, _ = pc.WriteTo(resp, cAddr)
			}
		}(query, clientAddr)
	}
}

func getSplitDNSServers(domain string) []string {
	var match []string
	splitDNSCache.Range(func(key, value interface{}) bool {
		route := key.(string)
		if domain == route || strings.HasSuffix(domain, "."+route) {
			match = value.([]string)
			return false
		}
		return true
	})
	return match
}

// socksDialContext returns a context-aware dial through the SOCKS5 proxy at
// socks. x/net's SOCKS5 dialer implements proxy.ContextDialer, which lets an
// http.Client deadline or a cancelled request abort the SOCKS handshake too;
// the plain Dial is only the fallback for a dialer that does not.
func socksDialContext(socks, user, pass string) (func(ctx context.Context, network, addr string) (net.Conn, error), error) {
	var auth *proxy.Auth
	if user != "" || pass != "" {
		auth = &proxy.Auth{User: user, Password: pass}
	}
	dialer, err := proxy.SOCKS5("tcp", socks, auth, proxy.Direct)
	if err != nil {
		return nil, err
	}
	if cd, ok := dialer.(proxy.ContextDialer); ok {
		return cd.DialContext, nil
	}
	return func(_ context.Context, network, addr string) (net.Conn, error) {
		return dialer.Dial(network, addr)
	}, nil
}

func forwardDNSviaSOCKS5(query []byte, socksAddr, user, pass, dnsServer string) ([]byte, error) {
	var auth *proxy.Auth
	if user != "" || pass != "" {
		auth = &proxy.Auth{User: user, Password: pass}
	}
	dialer, err := proxy.SOCKS5("tcp", socksAddr, auth, proxy.Direct)
	if err != nil {
		return nil, err
	}
	conn, err := dialer.Dial("tcp", dnsServer)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	length := uint16(len(query))
	buf := make([]byte, 2+len(query))
	buf[0] = byte(length >> 8)
	buf[1] = byte(length)
	copy(buf[2:], query)
	if _, err := conn.Write(buf); err != nil {
		return nil, err
	}

	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		return nil, err
	}
	respLen := int(lenBuf[0])<<8 | int(lenBuf[1])
	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(conn, respBuf); err != nil {
		return nil, err
	}
	return respBuf, nil
}

func processDNSQuery(query []byte, fallbacks []string, dohUrl string) []byte {
	var msg dnsmessage.Message
	if err := msg.Unpack(query); err != nil || len(msg.Questions) == 0 {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	q := msg.Questions[0]
	domain := strings.ToLower(strings.Trim(q.Name.String(), "."))

	suffix := getMagicDNSSuffix()

	// Auto-append MagicDNS suffix for convenience in lookup tools
	if suffix != "" {
		if !strings.Contains(domain, ".") {
			// Short name, e.g. "personal-pinus-poco" -> "personal-pinus-poco.tail8a412.ts.net"
			domain = domain + "." + suffix
		} else if strings.HasSuffix(domain, ".ts.net") && !strings.HasSuffix(domain, "."+suffix) {
			// Ends with ".ts.net" but missing tailnet ID, e.g. "personal-pinus-poco.ts.net" -> "personal-pinus-poco.tail8a412.ts.net"
			parts := strings.Split(domain, ".")
			if len(parts) >= 2 && parts[len(parts)-2] == "ts" && parts[len(parts)-1] == "net" {
				host := strings.Join(parts[:len(parts)-2], ".")
				domain = host + "." + suffix
			}
		}
	}

	if strings.HasSuffix(domain, ".arpa") {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	socks, user, pass, _ := GConfig.get()

	// A tailnet name must match the suffix on a label boundary, otherwise
	// "evil-tail8a412.ts.net" would be treated as MagicDNS.
	isMagicDNS := suffix != "" && (domain == suffix || strings.HasSuffix(domain, "."+suffix))
	isShortName := !strings.Contains(domain, ".")

	// 1. Look up in the nodes cache (MagicDNS). A cache hit is authoritative:
	// return its response even when the record type does not match (NODATA)
	// rather than nil, so an AAAA lookup for an IPv4-only peer does not fall
	// through to a public resolver and leak the internal name.
	if isMagicDNS || isShortName {
		if ips, ok := nodesCache.Load(domain); ok {
			return packDNSResponse(msg, q, ips.([]string), query)
		}
	}

	// 2. Split DNS (SOCKS5 TCP with direct A-record fallback for host IP mappings).
	if !isMagicDNS {
		splitServers := getSplitDNSServers(domain)
		if len(splitServers) > 0 {
			if socks != "" {
				for _, server := range splitServers {
					// A resolver address may already be host:port, a bare IP, or
					// a DoH URL. Only append :53 to a bare host; skip URL forms so
					// they are not mangled into "https://...:53".
					if strings.Contains(server, "://") {
						continue
					}
					target := server
					if _, _, err := net.SplitHostPort(server); err != nil {
						target = net.JoinHostPort(server, "53")
					}
					resp, err := forwardDNSviaSOCKS5(query, socks, user, pass, target)
					if err == nil && len(resp) >= 2 {
						resp[0] = query[0]
						resp[1] = query[1]
						return resp
					}
				}
			}
			// Fallback: If split servers are IP addresses (e.g. Tailnet peer IP mapped to domain in Admin Console),
			// and no DNS server responded on port 53, return the IPs directly as A/AAAA records for this domain.
			var validIPs []string
			for _, server := range splitServers {
				if ip := net.ParseIP(server); ip != nil {
					validIPs = append(validIPs, server)
				}
			}
			if len(validIPs) > 0 {
				slog.Info("DNS proxy: returning direct split IP A-record", "domain", domain, "ips", validIPs)
				return packDNSResponse(msg, q, validIPs, query)
			}
		}
	}

	// 3. Local API DNS Query (asks Tailscaled resolver for MagicDNS, Split DNS, Search Domains, and custom records)
	typeStr := "A"
	if q.Type == dnsmessage.TypeAAAA {
		typeStr = "AAAA"
	}
	path := fmt.Sprintf("/localapi/v0/dns-query?name=%s&type=%s", url.QueryEscape(domain), typeStr)
	data, err := doLocalRequest("GET", path, nil)
	if err == nil {
		var dnsResp struct{ Bytes []byte }
		if json.Unmarshal(data, &dnsResp) == nil && len(dnsResp.Bytes) >= 4 {
			rcode := dnsResp.Bytes[3] & 0x0F
			var parsedMsg dnsmessage.Message
			wellFormed := parsedMsg.Unpack(dnsResp.Bytes) == nil
			if rcode == 0 && wellFormed && len(parsedMsg.Answers) > 0 {
				dnsResp.Bytes[0] = query[0]
				dnsResp.Bytes[1] = query[1]
				return dnsResp.Bytes
			}
			// For a tailnet name, trust the daemon's verdict — including NODATA
			// and NXDOMAIN. Falling through would resend the internal name to a
			// public resolver, leaking it and overriding the correct answer.
			if isMagicDNS && wellFormed {
				dnsResp.Bytes[0] = query[0]
				dnsResp.Bytes[1] = query[1]
				return dnsResp.Bytes
			}
		}
	}

	// A tailnet name that reached here has no answer; do not leak it publicly.
	if isMagicDNS {
		return packNoData(msg)
	}

	// 4. Fallback (public names only)
	return tryFallbackDNS(query, fallbacks, dohUrl)
}

func packDNSResponse(msg dnsmessage.Message, q dnsmessage.Question, ips []string, query []byte) []byte {
	msg.Response = true
	msg.Authoritative = true
	msg.RecursionAvailable = true
	for _, ipStr := range ips {
		ip := net.ParseIP(ipStr)
		if ip == nil {
			continue
		}
		if ip4 := ip.To4(); ip4 != nil && q.Type == dnsmessage.TypeA {
			var a [4]byte
			copy(a[:], ip4)
			msg.Answers = append(msg.Answers, dnsmessage.Resource{
				Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
				Body:   &dnsmessage.AResource{A: a},
			})
		} else if q.Type == dnsmessage.TypeAAAA && ip.To4() == nil {
			// Only real IPv6. net.ParseIP("100.64.0.1").To16() is non-nil, so
			// without the To4()==nil guard an IPv4 peer answered AAAA as a bogus
			// ::ffff: mapped address.
			if ip6 := ip.To16(); ip6 != nil {
				var aaaa [16]byte
				copy(aaaa[:], ip6)
				msg.Answers = append(msg.Answers, dnsmessage.Resource{
					Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET, TTL: 60},
					Body:   &dnsmessage.AAAAResource{AAAA: aaaa},
				})
			}
		}
	}
	// Always return a well-formed response. Zero answers become NODATA rather
	// than nil, so the caller does not fall through to a public resolver.
	if packed, err := msg.Pack(); err == nil {
		return packed
	}
	return nil
}

// packNoData returns an authoritative NOERROR response with no answers.
func packNoData(msg dnsmessage.Message) []byte {
	msg.Response = true
	msg.Authoritative = true
	msg.RecursionAvailable = true
	msg.Answers = nil
	msg.Authorities = nil
	msg.Additionals = nil
	if packed, err := msg.Pack(); err == nil {
		return packed
	}
	return nil
}

func tryFallbackDNS(query []byte, fallbacks []string, dohUrl string) []byte {
	socks, user, pass, _ := GConfig.get()

	// First try to route fallbacks via SOCKS5 TCP
	if socks != "" {
		for _, server := range fallbacks {
			resp, err := forwardDNSviaSOCKS5(query, socks, user, pass, server)
			if err == nil {
				return resp
			}
		}
	}

	// Fallback to direct UDP if SOCKS5 fails or is not configured
	for _, server := range fallbacks {
		resp, err := forwardDNSviaUDP(query, server)
		if err == nil {
			return resp
		}
	}

	// Fallback to DoH (which will go via SOCKS5 if socks is set)
	if dohUrl != "none" && dohUrl != "" {
		resp, err := forwardDNSviaDoH(query, dohUrl)
		if err == nil {
			return resp
		}
	}
	return nil
}

func forwardDNSviaUDP(query []byte, server string) ([]byte, error) {
	conn, err := net.DialTimeout("udp", server, 3*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	// Verify the reply's transaction ID matches the query's first two bytes.
	// A spoofed or stale UDP datagram with the wrong ID must be treated as a
	// failure so it is not returned as the answer.
	if n < 2 || len(query) < 2 || buf[0] != query[0] || buf[1] != query[1] {
		return nil, fmt.Errorf("dns udp: transaction ID mismatch")
	}
	return buf[:n], nil
}

// dohClient is the cached DoH http.Client, keyed by the SOCKS configuration it
// dials through. Building a transport per query parked every DoH connection in
// a throwaway idle pool with no IdleConnTimeout — one fd, one TLS session and a
// readLoop goroutine leaked per fallback query, precisely when connectivity
// was already bad enough to reach the DoH step. One transport serves every DoH
// URL: the pool inside it is keyed by host.
var (
	dohClientMu  sync.Mutex
	dohClientKey string
	dohClient    *http.Client
)

func dohHTTPClient(socks, user, pass string) *http.Client {
	key := socks + "\x00" + user + "\x00" + pass
	dohClientMu.Lock()
	defer dohClientMu.Unlock()
	if dohClient != nil && dohClientKey == key {
		return dohClient
	}
	if dohClient != nil {
		// The SOCKS settings changed; the old pool points at the old proxy.
		dohClient.CloseIdleConnections()
	}

	tr := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
		ForceAttemptHTTP2:   true, // a custom DialContext otherwise disables h2 for direct DoH
		MaxIdleConns:        4,
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     60 * time.Second,
		TLSHandshakeTimeout: 5 * time.Second,
	}
	if socks != "" {
		// Route DoH through the SOCKS proxy (i.e. the tailnet / exit node) when
		// one is configured; a dialer error leaves the direct transport, which is
		// what the per-query code did as well.
		if dial, err := socksDialContext(socks, user, pass); err == nil {
			tr.Proxy = nil
			tr.DialContext = dial
		}
	}
	dohClient = &http.Client{Timeout: 5 * time.Second, Transport: tr}
	dohClientKey = key
	return dohClient
}

// closeDoHClient drops the cached DoH pool. Its connections tunnel through the
// daemon's SOCKS port, so they must not outlive the daemon.
func closeDoHClient() {
	dohClientMu.Lock()
	defer dohClientMu.Unlock()
	if dohClient != nil {
		dohClient.CloseIdleConnections()
		dohClient = nil
		dohClientKey = ""
	}
}

func forwardDNSviaDoH(query []byte, dohUrl string) ([]byte, error) {
	encoded := base64.RawURLEncoding.EncodeToString(query)
	socks, user, pass, _ := GConfig.get()
	client := dohHTTPClient(socks, user, pass)

	url := dohUrl
	if strings.Contains(url, "?") {
		url += "&dns=" + encoded
	} else {
		url += "?dns=" + encoded
	}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("doh status: %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}
