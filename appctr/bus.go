package appctr

// bus.go — IPN Bus Listener: subscribes to the tailscaled LocalAPI event stream
// and populates the DNS caches used by dns.go.
// This file owns all daemon-connectivity concerns; dns.go owns only DNS resolution.

import (
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ─── Shared DNS state (written here, read in dns.go) ────────────────────────

var dnsCache sync.Map
var splitDNSCache sync.Map // domain → []string resolverIPs
var nodesCache sync.Map    // hostname → []string IPs
var magicDNSSuffix string

// ─── Bus lifecycle ───────────────────────────────────────────────────────────

var busCancel context.CancelFunc
var busMu sync.Mutex

// EnsureIPNBusListener starts the bus listener goroutine if not already running.
func EnsureIPNBusListener() {
	busMu.Lock()
	defer busMu.Unlock()
	if busCancel != nil {
		return
	}
	slog.Info("Starting IPN Bus Listener...")
	busCtx, cancel := context.WithCancel(context.Background())
	busCancel = cancel
	go startIPNBusListener(busCtx)
}

// StopIPNBusListener cancels the running bus listener.
func StopIPNBusListener() {
	busMu.Lock()
	defer busMu.Unlock()
	if busCancel != nil {
		slog.Info("Stopping IPN Bus Listener...")
		busCancel()
		busCancel = nil
	}
}

// ─── Internal types ──────────────────────────────────────────────────────────

type busNode struct {
	Name      string
	Addresses []string
}

// ─── Listener loop ───────────────────────────────────────────────────────────

func startIPNBusListener(ctx context.Context) {
	const maxRetries = 3
	const retryDelay = 2 * time.Second

	slog.Info("Starting IPN Bus Listener (mask=4095)...")
	for attempt := 1; ; attempt++ {
		select {
		case <-ctx.Done():
			return
		default:
			err := listenToBus(ctx)
			if err == nil {
				// Clean disconnect (ctx cancelled or stream ended) — reset counter.
				attempt = 0
				continue
			}

			errStr := err.Error()
			switch {
			case strings.Contains(errStr, "permission denied"):
				slog.Error("Bus listener: socket permission denied — SELinux or chmod issue. Not retrying.", "err", err)
				return
			case strings.Contains(errStr, "no such file") || strings.Contains(errStr, "no such socket"):
				slog.Error("Bus listener: socket missing — daemon not started.", "err", err)
				return
			case attempt >= maxRetries:
				slog.Error("Bus listener: too many failures, giving up.", "attempts", attempt, "err", err)
				return
			default:
				slog.Error("Bus listener error, retrying", "attempt", attempt, "of", maxRetries, "err", err)
				time.Sleep(retryDelay)
			}
		}
	}
}

// listenToBus opens a streaming connection to the LocalAPI IPN bus and feeds
// received NetMap updates into the DNS caches. Returns nil on clean shutdown,
// or the first fatal error encountered.
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

	req, _ := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask=4095", nil)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
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

		if err := dec.Decode(&msg); err != nil {
			return err
		}

		if msg.NetMap != nil {
			applyNetMapToDNSCache(msg.NetMap)
		}
	}
}

// applyNetMapToDNSCache updates all DNS caches from a received NetMap snapshot.
func applyNetMapToDNSCache(nm *struct {
	MagicDNSSuffix string
	SelfNode       *busNode
	Peers          []*busNode
	DNS            struct {
		Domains []string
		Routes  map[string][]struct {
			Addr string
		}
	}
}) {
	// 1. MagicDNS suffix.
	if nm.MagicDNSSuffix != "" {
		magicDNSSuffix = strings.ToLower(strings.Trim(nm.MagicDNSSuffix, "."))
	} else if len(nm.DNS.Domains) > 0 {
		magicDNSSuffix = strings.ToLower(strings.Trim(nm.DNS.Domains[0], "."))
	}

	// 2. Peer nodes.
	nodesCount := 0
	if nm.SelfNode != nil && updateNodeInCache(nm.SelfNode) {
		nodesCount++
	}
	for _, p := range nm.Peers {
		if updateNodeInCache(p) {
			nodesCount++
		}
	}

	// 3. Split DNS routes.
	routesCount := 0
	for domain, resolvers := range nm.DNS.Routes {
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

	if nodesCount > 0 || routesCount > 0 {
		slog.Info("Bus: DNS caches updated", "nodes", nodesCount, "routes", routesCount, "suffix", magicDNSSuffix)
	}
}

// updateNodeInCache inserts a node's addresses into nodesCache under both its
// full MagicDNS hostname and its short label.
func updateNodeInCache(n *busNode) bool {
	if n == nil || n.Name == "" {
		return false
	}

	fullName := strings.ToLower(strings.Trim(n.Name, "."))
	var ips []string
	for _, addr := range n.Addresses {
		ipStr := addr
		if idx := strings.Index(addr, "/"); idx != -1 {
			ipStr = addr[:idx]
		}
		if ip := net.ParseIP(ipStr); ip != nil {
			ips = append(ips, ip.String())
		}
	}

	if len(ips) > 0 {
		nodesCache.Store(fullName, ips)
		if parts := strings.Split(fullName, "."); len(parts) > 0 {
			nodesCache.Store(parts[0], ips)
		}
		return true
	}
	return false
}

// syncNetMapFromBus performs a one-shot poll of the IPN bus to pre-populate
// DNS caches on startup (used before the persistent listener is ready).
func syncNetMapFromBus() {
	pc := PC
	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
		Timeout: 2 * time.Second,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	req, _ := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask=4095", nil)
	resp, err := client.Do(req)
	if err != nil {
		return
	}
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
		if err := dec.Decode(&msg); err != nil {
			break
		}
		if msg.NetMap != nil {
			applyNetMapToDNSCache(msg.NetMap)
			break // one-shot: got first NetMap, done
		}
	}
}
