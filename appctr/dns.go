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
var splitDNSCache sync.Map // Map[string][]string
var nodesCache sync.Map    // Map[string][]string
var magicDNSSuffix string
var busCancel context.CancelFunc
var busMu sync.Mutex

func FlushDNS() {
	dnsCache.Range(func(key, value interface{}) bool {
		dnsCache.Delete(key)
		return true
	})
	slog.Info("DNS cache flushed")
}

func startIPNBusListener(ctx context.Context) {
	slog.Info("Starting IPN Bus Listener (mask=1032)...")
	for {
		select {
		case <-ctx.Done():
			return
		default:
			err := listenToBus(ctx)
			if err != nil {
				slog.Error("Bus listener error, retrying in 2s", "err", err)
				time.Sleep(2 * time.Second)
			}
		}
	}
}

type busNode struct {
	Name      string
	Addresses []string
}

func listenToBus(ctx context.Context) error {
	pc := PC
	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
	}

	req, _ := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask=1032", nil)
	resp, err := client.Do(req)
	if err != nil { return err }
	defer resp.Body.Close()

	dec := json.NewDecoder(resp.Body)
	for {
		var msg struct {
			NetMap *struct {
				MagicDNSSuffix string
				SelfNode       *busNode
				Peers          []*busNode
				DNS            struct {
					Domains []string
					Routes  map[string][]struct {
						Addr string
					}
				}
			}
		}

		if err := dec.Decode(&msg); err != nil { return err }

		if msg.NetMap != nil {
			// 1. Суффикс
			if msg.NetMap.MagicDNSSuffix != "" {
				magicDNSSuffix = strings.ToLower(strings.Trim(msg.NetMap.MagicDNSSuffix, "."))
			} else if len(msg.NetMap.DNS.Domains) > 0 {
				magicDNSSuffix = strings.ToLower(strings.Trim(msg.NetMap.DNS.Domains[0], "."))
			}

			// 2. Кэшируем ноды
			nodesCount := 0
			if msg.NetMap.SelfNode != nil {
				if updateNodeInCache(msg.NetMap.SelfNode) { nodesCount++ }
			}
			for _, p := range msg.NetMap.Peers {
				if updateNodeInCache(p) { nodesCount++ }
			}

			// 3. Маршруты Split DNS
			routesCount := 0
			if msg.NetMap.DNS.Routes != nil {
				for domain, resolvers := range msg.NetMap.DNS.Routes {
					var ips []string
					for _, r := range resolvers {
						ips = append(ips, r.Addr)
					}
					if len(ips) > 0 {
						d := strings.ToLower(strings.Trim(domain, "."))
						splitDNSCache.Store(d, ips)
						routesCount++
					}
				}
			}
			
			if nodesCount > 0 || routesCount > 0 {
				slog.Info("Bus: Metadata synced", "nodes", nodesCount, "routes", routesCount, "suffix", magicDNSSuffix)
			}
		}
	}
}

func updateNodeInCache(n *busNode) bool {
	if n == nil || n.Name == "" { return false }
	
	fullName := strings.ToLower(strings.Trim(n.Name, "."))
	var ips []string
	for _, addr := range n.Addresses {
		ipStr := addr
		if idx := strings.Index(addr, "/"); idx != -1 {
			ipStr = addr[:idx]
		}
		ip := net.ParseIP(ipStr)
		if ip != nil {
			ips = append(ips, ip.String())
		}
	}
	
	if len(ips) > 0 {
		nodesCache.Store(fullName, ips)
		parts := strings.Split(fullName, ".")
		if len(parts) > 0 {
			nodesCache.Store(parts[0], ips)
		}
		return true
	}
	return false
}

func startDNSProxy(ctx context.Context, listenAddr string, fallbacks []string, dohUrl string) error {
	pc, err := net.ListenPacket("udp", listenAddr)
	if err != nil {
		return fmt.Errorf("dns proxy listen failed: %w", err)
	}
	defer pc.Close()
	slog.Info("DNS proxy listening", "addr", listenAddr)

	busMu.Lock()
	if busCancel != nil { busCancel() }
	busCtx, cancel := context.WithCancel(context.Background())
	busCancel = cancel
	busMu.Unlock()
	go startIPNBusListener(busCtx)

	go func() {
		<-ctx.Done()
		pc.Close()
		busMu.Lock()
		if busCancel != nil { busCancel(); busCancel = nil }
		busMu.Unlock()
	}()

	buf := make([]byte, 65535)
	for {
		n, clientAddr, err := pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil { return nil }
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
	if err != nil { return nil, err }
	conn, err := dialer.Dial("tcp", dnsServer)
	if err != nil { return nil, err }
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	length := uint16(len(query))
	buf := make([]byte, 2+len(query))
	buf[0] = byte(length >> 8); buf[1] = byte(length)
	copy(buf[2:], query)
	if _, err := conn.Write(buf); err != nil { return nil, err }

	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, lenBuf); err != nil { return nil, err }
	respLen := int(lenBuf[0])<<8 | int(lenBuf[1])
	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(conn, respBuf); err != nil { return nil, err }
	return respBuf, nil
}

func processDNSQuery(query []byte, fallbacks []string, dohUrl string) []byte {
	var msg dnsmessage.Message
	if err := msg.Unpack(query); err != nil || len(msg.Questions) == 0 {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	q := msg.Questions[0]
	domain := strings.ToLower(strings.Trim(q.Name.String(), "."))
	
	if strings.HasSuffix(domain, ".arpa") {
		return tryFallbackDNS(query, fallbacks, dohUrl)
	}

	socks, user, pass, _ := GConfig.get()
	
	isMagicDNS := magicDNSSuffix != "" && strings.HasSuffix(domain, magicDNSSuffix)
	isShortName := !strings.Contains(domain, ".")

	// 1. Поиск в кэше нод (MagicDNS)
	if isMagicDNS || isShortName {
		if ips, ok := nodesCache.Load(domain); ok {
			return packDNSResponse(msg, q, ips.([]string), query)
		}
	}

	// 2. Split DNS (SOCKS5 TCP)
	if !isMagicDNS {
		splitServers := getSplitDNSServers(domain)
		if len(splitServers) > 0 && socks != "" {
			for _, server := range splitServers {
				target := net.JoinHostPort(server, "53")
				resp, err := forwardDNSviaSOCKS5(query, socks, user, pass, target)
				if err == nil && len(resp) >= 2 {
					resp[0] = query[0]; resp[1] = query[1]
					return resp
				}
			}
		}
	}

	// 3. Local API DNS Query
	typeStr := "A"
	if q.Type == dnsmessage.TypeAAAA { typeStr = "AAAA" }
	path := fmt.Sprintf("/localapi/v0/dns-query?name=%s&type=%s", domain, typeStr)
	data, err := doLocalRequest("GET", path, nil)
	if err == nil {
		var dnsResp struct { Bytes []byte }
		if json.Unmarshal(data, &dnsResp) == nil && len(dnsResp.Bytes) >= 4 {
			rcode := dnsResp.Bytes[3] & 0x0F
			if rcode == 0 || isMagicDNS {
				dnsResp.Bytes[0] = query[0]; dnsResp.Bytes[1] = query[1]
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
		if ip == nil { continue }
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
	for _, server := range fallbacks {
		resp, err := forwardDNSviaUDP(query, server)
		if err == nil { return resp }
	}
	if dohUrl != "none" && dohUrl != "" {
		resp, err := forwardDNSviaDoH(query, dohUrl)
		if err == nil { return resp }
	}
	return nil
}

func forwardDNSviaUDP(query []byte, server string) ([]byte, error) {
	conn, err := net.DialTimeout("udp", server, 3*time.Second)
	if err != nil { return nil, err }
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(query); err != nil { return nil, err }
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil { return nil, err }
	return buf[:n], nil
}

func forwardDNSviaDoH(query []byte, dohUrl string) ([]byte, error) {
	encoded := base64.RawURLEncoding.EncodeToString(query)
	client := &http.Client{Timeout: 5 * time.Second}
	url := dohUrl
	if strings.Contains(url, "?") { url += "&dns=" + encoded } else { url += "?dns=" + encoded }
	req, err := http.NewRequest("GET", url, nil)
	if err != nil { return nil, err }
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil { return nil, err }
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK { return nil, fmt.Errorf("doh status: %d", resp.StatusCode) }
	return io.ReadAll(resp.Body)
}
