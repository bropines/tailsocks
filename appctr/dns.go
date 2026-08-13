package appctr

// dns.go — Pure DNS resolution: MagicDNS cache lookup, split-DNS forwarding,
// SOCKS5/DoH fallbacks, and the TUN DNS proxy.
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

	// Auto-append MagicDNS suffix for convenience in lookup tools
	if magicDNSSuffix != "" {
		if !strings.Contains(domain, ".") {
			// Short name, e.g. "personal-pinus-poco" -> "personal-pinus-poco.tail8a412.ts.net"
			domain = domain + "." + magicDNSSuffix
		} else if strings.HasSuffix(domain, ".ts.net") && !strings.HasSuffix(domain, "."+magicDNSSuffix) {
			// Ends with ".ts.net" but missing tailnet ID, e.g. "personal-pinus-poco.ts.net" -> "personal-pinus-poco.tail8a412.ts.net"
			parts := strings.Split(domain, ".")
			if len(parts) >= 2 && parts[len(parts)-2] == "ts" && parts[len(parts)-1] == "net" {
				host := strings.Join(parts[:len(parts)-2], ".")
				domain = host + "." + magicDNSSuffix
			}
		}
	}

	if strings.HasSuffix(domain, ".arpa") {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	socks, user, pass, _ := GConfig.get()

	isMagicDNS := magicDNSSuffix != "" && strings.HasSuffix(domain, magicDNSSuffix)
	isShortName := !strings.Contains(domain, ".")

	// 1. Look up in the nodes cache (MagicDNS).
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
					target := net.JoinHostPort(server, "53")
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
	path := fmt.Sprintf("/localapi/v0/dns-query?name=%s&type=%s", domain, typeStr)
	data, err := doLocalRequest("GET", path, nil)
	if err == nil {
		var dnsResp struct{ Bytes []byte }
		if json.Unmarshal(data, &dnsResp) == nil && len(dnsResp.Bytes) >= 4 {
			rcode := dnsResp.Bytes[3] & 0x0F
			var parsedMsg dnsmessage.Message
			if rcode == 0 && parsedMsg.Unpack(dnsResp.Bytes) == nil && len(parsedMsg.Answers) > 0 {
				dnsResp.Bytes[0] = query[0]
				dnsResp.Bytes[1] = query[1]
				return dnsResp.Bytes
			}
		}
	}

	// 4. Fallback
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
		} else if ip6 := ip.To16(); ip6 != nil && q.Type == dnsmessage.TypeAAAA {
			var aaaa [16]byte
			copy(aaaa[:], ip6)
			msg.Answers = append(msg.Answers, dnsmessage.Resource{
				Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET, TTL: 60},
				Body:   &dnsmessage.AAAAResource{AAAA: aaaa},
			})
		}
	}
	if len(msg.Answers) > 0 {
		if packed, err := msg.Pack(); err == nil {
			return packed
		}
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
	return buf[:n], nil
}

func forwardDNSviaDoH(query []byte, dohUrl string) ([]byte, error) {
	encoded := base64.RawURLEncoding.EncodeToString(query)
	socks, user, pass, _ := GConfig.get()

	var transport *http.Transport
	if socks != "" {
		var auth *proxy.Auth
		if user != "" || pass != "" {
			auth = &proxy.Auth{User: user, Password: pass}
		}
		dialer, err := proxy.SOCKS5("tcp", socks, auth, proxy.Direct)
		if err == nil {
			transport = &http.Transport{
				DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					return dialer.Dial(network, addr)
				},
			}
		}
	}

	client := &http.Client{Timeout: 5 * time.Second}
	if transport != nil {
		client.Transport = transport
	}

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

var tunDNSCancel context.CancelFunc
var tunDNSMu sync.Mutex

func StartTunDNS(listenAddr string) error {
	tunDNSMu.Lock()
	defer tunDNSMu.Unlock()
	if tunDNSCancel != nil {
		return nil
	}

	opt := lastOptions
	fallbacks := []string{"8.8.8.8:53", "1.1.1.1:53"}
	if opt != nil && opt.DnsFallbacks != "" {
		fallbacks = strings.Split(opt.DnsFallbacks, ",")
	}
	doh := ""
	if opt != nil {
		doh = opt.DohFallback
	}

	ctx, cancel := context.WithCancel(context.Background())
	tunDNSCancel = cancel

	go func() {
		slog.Info("Starting TUN DNS proxy", "addr", listenAddr)
		if err := startDNSProxy(ctx, listenAddr, fallbacks, doh); err != nil {
			slog.Error("TUN DNS proxy error", "err", err)
			tunDNSMu.Lock()
			if tunDNSCancel != nil {
				tunDNSCancel()
				tunDNSCancel = nil
			}
			tunDNSMu.Unlock()
		}
	}()

	return nil
}

func StopTunDNS() {
	tunDNSMu.Lock()
	defer tunDNSMu.Unlock()
	if tunDNSCancel != nil {
		tunDNSCancel()
		tunDNSCancel = nil
		slog.Info("TUN DNS proxy stopped")
	}
}
