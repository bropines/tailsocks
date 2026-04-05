package appctr

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

var dnsCache sync.Map
var splitDNSCache sync.Map
var splitDNSLastUpdate time.Time
var splitDNSMutex sync.Mutex

func startDNSProxy(ctx context.Context, listenAddr string, socksAddr string, fallbacks []string, dohUrl string) error {
	pc, err := net.ListenPacket("udp", listenAddr)
	if err != nil {
		return fmt.Errorf("dns proxy listen failed: %w", err)
	}
	defer pc.Close()
	slog.Info("DNS proxy listening", "addr", listenAddr)

	go func() {
		<-ctx.Done()
		slog.Info("DNS proxy context cancelled, shutting down")
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
			resp := processDNSQuery(q, fallbacks, socksAddr, dohUrl)
			if resp != nil {
				if _, err := pc.WriteTo(resp, cAddr); err != nil {
					slog.Debug("DNS write back error", "err", err)
				}
			}
		}(query, clientAddr)
	}
}

func getSplitDNSServers(domain string) []string {
	splitDNSMutex.Lock()
	defer splitDNSMutex.Unlock()

	if time.Since(splitDNSLastUpdate) > 60*time.Second {
		out := RunTailscaleCmd("dns status --json")
		var status struct {
			SplitDNSRoutes map[string][]struct{ Addr string }
		}
		if err := json.Unmarshal([]byte(out), &status); err == nil {
			splitDNSCache.Range(func(key, value interface{}) bool {
				splitDNSCache.Delete(key)
				return true
			})
			for d, addrs := range status.SplitDNSRoutes {
				d = strings.TrimSuffix(d, ".")
				var ips []string
				for _, a := range addrs {
					ips = append(ips, a.Addr)
				}
				splitDNSCache.Store(d, ips)
			}
			splitDNSLastUpdate = time.Now()
		}
	}

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

func forwardDNSviaSOCKS5(query []byte, socksAddr string, dnsServer string) ([]byte, error) {
	dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
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

func processDNSQuery(query []byte, fallbacks []string, socksAddr string, dohUrl string) []byte {
	var msg dnsmessage.Message
	if err := msg.Unpack(query); err != nil || len(msg.Questions) == 0 {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	q := msg.Questions[0]
	domain := strings.TrimSuffix(q.Name.String(), ".")

	if strings.HasSuffix(domain, ".arpa") {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	if q.Type == dnsmessage.TypeA || q.Type == dnsmessage.TypeAAAA {
		var ips []string

		if cached, ok := dnsCache.Load(domain); ok {
			ips = cached.([]string)
		} else {
			// 1. Локальные ноды тейлскейла
			out := RunTailscaleCmd("ip " + domain)
			ips = extractIPs(out)

			if len(ips) == 0 {
				shortName := strings.Split(domain, ".")[0]
				out = RunTailscaleCmd("ip " + shortName)
				ips = extractIPs(out)
			}

			// 2. Split DNS через SOCKS5 TCP
			if len(ips) == 0 {
				splitServers := getSplitDNSServers(domain)
				if len(splitServers) > 0 {
					slog.Info("Split DNS via SOCKS5 TCP triggered", "domain", domain, "servers", splitServers)
					for _, server := range splitServers {
						target := net.JoinHostPort(server, "53")
						resp, err := forwardDNSviaSOCKS5(query, socksAddr, target)
						if err == nil {
							return resp
						}
						slog.Error("SOCKS5 TCP DNS failed", "server", server, "err", err)
					}
				}
			}

			// 3. CLI fallback
			if len(ips) == 0 {
				out = RunTailscaleCmd("dns query " + domain)
				ips = extractIPs(out)
			}

			if len(ips) > 0 {
				dnsCache.Store(domain, ips)
				go func(d string) {
					time.Sleep(60 * time.Second)
					dnsCache.Delete(d)
				}(domain)
			}
		}

		if len(ips) > 0 {
			msg.Response = true
			msg.Authoritative = true
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
		}
	}

	return tryFallbackDNS(query, fallbacks, dohUrl)
}

func extractIPs(out string) []string {
	var ips []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if net.ParseIP(line) != nil {
			ips = append(ips, line)
		}
	}
	return ips
}

func tryFallbackDNS(query []byte, fallbacks []string, dohUrl string) []byte {
	for _, server := range fallbacks {
		resp, err := forwardDNSviaUDP(query, server)
		if err == nil {
			return resp
		}
		slog.Debug("Fallback DNS failed", "server", server, "err", err)
	}

	if dohUrl != "none" {
		resp, err := forwardDNSviaDoH(query, dohUrl)
		if err == nil {
			return resp
		}
		slog.Debug("DoH fallback failed", "err", err)
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
	client := &http.Client{Timeout: 5 * time.Second}

	req, err := http.NewRequest("GET", dohUrl+"?dns="+encoded, nil)
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
