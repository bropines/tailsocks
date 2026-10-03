package appctr

// tailcat_server.go — this device as a tailcat server, the other half of
// tailcat.go: what `tailcat serve` does on a computer, for clients that hold
// its address. It can hand out ports on this device and be an exit node
// (clients go out through this device's network), and lets in only the
// client keys it is given unless told to let in anyone.
//
// Its identity is a key file the app keeps in private storage. The relay
// region is picked once, when the key is made, and kept in it — as
// `tailcat genkey --fixed-region` does — so the address stays the same from
// one start to the next.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

// TailcatServerID is the id the server's changes reach TailcatListener with;
// a connection's id is a UUID, so the two never meet.
const TailcatServerID = "server"

type tcServer struct {
	tcLog
	cancel context.CancelFunc
	wg     sync.WaitGroup
	wake   chan struct{} // a network change: retry a start that failed now

	mu      sync.Mutex
	s       *tailcat.Server // nil until it has started
	state   string          // starting, serving, error
	lastErr string
	active  map[net.Conn]struct{}
	served  int
}

var (
	tsMu  sync.Mutex
	tsSrv *tcServer
)

// TailcatServerCreateKey makes a server identity in keyFile, replacing any,
// and returns its tailcat address. It fetches the relay map and picks the
// nearest region, so it needs the network and takes a few seconds.
func TailcatServerCreateKey(keyFile string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	priv := tailcat.NewPrivateKey()
	dm, err := tailcat.FetchDERPMap(ctx, tailcat.ExpandForServer, tcServerDERPCache(keyFile))
	if err != nil {
		return "", fmt.Errorf("relay map: %w", err)
	}
	id, err := tailcat.PickBestRegion(ctx, dm)
	if err != nil {
		return "", fmt.Errorf("nearest relay: %w", err)
	}
	if id == 0 {
		return "", errors.New("no relay region answered")
	}
	priv.Public.RegionID = id
	b, err := json.MarshalIndent(priv, "", "\t")
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(keyFile), 0o700); err != nil {
		return "", err
	}
	if err := os.WriteFile(keyFile, b, 0o600); err != nil {
		return "", err
	}
	return tcServerAddr(priv), nil
}

// TailcatServerAddress is the tailcat address of the key in keyFile, or ""
// without one.
func TailcatServerAddress(keyFile string) string {
	priv, err := tcLoadServerKey(keyFile)
	if err != nil {
		return ""
	}
	return tcServerAddr(priv)
}

func tcLoadServerKey(keyFile string) (*tailcat.PrivateKey, error) {
	b, err := os.ReadFile(keyFile)
	if err != nil {
		return nil, err
	}
	var priv tailcat.PrivateKey
	if err := json.Unmarshal(b, &priv); err != nil {
		return nil, err
	}
	if priv.Private.IsZero() || priv.Public.RegionID <= 0 {
		return nil, errors.New("not a server key with a fixed region")
	}
	return &priv, nil
}

// tcServerAddr is the address a key's server answers on: the server's keys,
// the pre-shared key and the region, as the CLI builds it in serve mode.
func tcServerAddr(priv *tailcat.PrivateKey) string {
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: priv.Private.Public()},
		ServerDiscoPublic: tailcat.DiscoPublicForNode(priv.Private),
		PresharedKey:      priv.Public.PresharedKey,
		RegionID:          priv.Public.RegionID,
	}
	return string(ci.Addr())
}

func tcServerDERPCache(keyFile string) tcDirCache {
	return tcDirCache(filepath.Join(filepath.Dir(keyFile), "derpmaps"))
}

// TailcatCheckServerPorts returns "" when ports lists ports on this device
// (by commas, spaces or lines), or which one is not a port.
func TailcatCheckServerPorts(ports string) string {
	if _, err := tcParsePorts(ports); err != nil {
		return err.Error()
	}
	return ""
}

func tcParsePorts(ports string) ([]uint16, error) {
	var out []uint16
	for _, f := range tcFields(ports) {
		p, err := strconv.ParseUint(f, 10, 16)
		if err != nil || p == 0 {
			return nil, fmt.Errorf("%s: not a port", f)
		}
		if !slices.Contains(out, uint16(p)) {
			out = append(out, uint16(p))
		}
	}
	slices.Sort(out)
	return out, nil
}

// TailcatCheckClientKeys returns "" when keys lists client public keys
// ("nodekey:…", by commas, spaces or lines), or which one is not one.
func TailcatCheckClientKeys(keys string) string {
	if _, err := tcParseKeys(keys); err != nil {
		return err.Error()
	}
	return ""
}

func tcParseKeys(keys string) ([]key.NodePublic, error) {
	var out []key.NodePublic
	for _, f := range tcFields(keys) {
		var k key.NodePublic
		if err := k.UnmarshalText([]byte(f)); err != nil {
			return nil, fmt.Errorf("%.20s…: not a client key", f)
		}
		out = append(out, k)
	}
	return out, nil
}

func tcFields(s string) []string {
	return strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == '\t' || r == '\r' })
}

// TailcatServerStart serves the key in keyFile: ports on 127.0.0.1 of this
// device, and, with exitNode, any address clients ask for, through this
// device's network. allowed lists the client keys let in; with allowAll
// anyone holding the address is, and with neither nobody is. It returns once
// the settings are checked; the server comes up, or keeps trying, by itself.
func TailcatServerStart(keyFile, ports string, exitNode bool, allowed string, allowAll bool) error {
	tsMu.Lock()
	running := tsSrv != nil
	tsMu.Unlock()
	if running {
		return errors.New("already running")
	}
	priv, err := tcLoadServerKey(keyFile)
	if err != nil {
		return fmt.Errorf("server key: %w", err)
	}
	portList, err := tcParsePorts(ports)
	if err != nil {
		return err
	}
	if len(portList) == 0 && !exitNode {
		return errors.New("nothing to serve: no ports and not an exit node")
	}
	keys, err := tcParseKeys(allowed)
	if err != nil {
		return err
	}

	srv := &tcServer{tcLog: tcLog{name: "server"}, wake: make(chan struct{}, 1), state: "starting", active: map[net.Conn]struct{}{}}
	ctx, cancel := context.WithCancel(context.Background())
	srv.cancel = cancel
	tsMu.Lock()
	tsSrv = srv
	tsMu.Unlock()
	slog.Info("Tailcat: server starting", "ports", fmt.Sprint(portList), "exitNode", exitNode, "allowed", len(keys), "allowAll", allowAll)

	srv.wg.Add(1)
	go func() {
		defer srv.wg.Done()
		defer tcRecover("server")
		for {
			err := srv.start(ctx, keyFile, priv, portList, exitNode, keys, allowAll)
			if ctx.Err() != nil {
				return
			}
			if err == nil {
				return
			}
			srv.warnf("the server did not start: %v", err)
			why := err.Error()
			srv.set(func(s *tcServer) { s.state, s.lastErr = "error", why })
			t := time.NewTimer(tcRetryEvery)
			select {
			case <-ctx.Done():
				t.Stop()
				return
			case <-srv.wake:
				t.Stop()
			case <-t.C:
			}
		}
	}()
	srv.notify()
	return nil
}

func (srv *tcServer) start(ctx context.Context, keyFile string, priv *tailcat.PrivateKey, ports []uint16, exitNode bool, keys []key.NodePublic, allowAll bool) error {
	// The relay's nodes, for the region the key names: what `serve` does
	// with a saved key (Expand, then the region it found).
	ci := priv.Public
	ectx, cancel := context.WithTimeout(ctx, 20*time.Second)
	err := ci.Expand(ectx, tailcat.ExpandForServer, tcServerDERPCache(keyFile))
	cancel()
	if err != nil {
		return fmt.Errorf("relay map: %w", err)
	}
	if len(ci.Region) == 0 {
		return errors.New("the relay region is gone from the relay map; make a new address")
	}
	reg := ci.Region[0]

	s := &tailcat.Server{
		Key:          priv.Private,
		PresharedKey: priv.Public.PresharedKey,
		Logf:         srv.engineLogf,
		Region:       reg,
	}
	if !allowAll {
		if len(keys) == 0 {
			// The zero key: allow-listing nobody, as `--allow=none` does.
			s.AddAllowedClient(key.NodePublic{})
		}
		for _, k := range keys {
			s.AddAllowedClient(k)
		}
	}
	served := func(port uint16) bool { return slices.Contains(ports, port) }
	if !exitNode {
		s.ServedTCPPorts = tcPortRanges(ports)
	}
	s.OnTCP = func(port uint16) func(net.Conn) {
		// An exit node reaches this device's own ports too, as the CLI's does.
		if !served(port) && !exitNode {
			return nil
		}
		return srv.forwardTCP(net.JoinHostPort("127.0.0.1", strconv.Itoa(int(port))))
	}
	if exitNode {
		s.OnTCPForward = func(dst netip.AddrPort) func(net.Conn) { return srv.forwardTCP(dst.String()) }
		// Without this a client's DNS and QUIC go nowhere: the tunnel is up,
		// TCP works, every UDP flow is silently dropped.
		s.OnUDPForward = func(dst netip.AddrPort) func(tailcat.ConnPacketConn) {
			return func(c tailcat.ConnPacketConn) {
				defer tcRecover("server udp")
				local, err := net.DialUDP("udp", nil, net.UDPAddrFromAddrPort(dst))
				if err != nil {
					srv.warnf("udp %v: %v", dst, err)
					c.Close()
					return
				}
				tailcat.ProxyPacketConns(c, local)
			}
		}
	}
	if err := s.Start(); err != nil {
		return err
	}
	if ctx.Err() != nil {
		s.Close()
		return nil
	}
	srv.logf("serving at %s, ports %v, exit node %v, %s", reg.RegionCode, ports, exitNode, tcAllowedText(keys, allowAll))
	srv.set(func(x *tcServer) { x.s, x.state, x.lastErr = s, "serving", "" })
	return nil
}

func tcAllowedText(keys []key.NodePublic, allowAll bool) string {
	switch {
	case allowAll:
		return "any client"
	case len(keys) == 0:
		return "no client allowed"
	default:
		return fmt.Sprintf("%d client keys allowed", len(keys))
	}
}

func tcPortRanges(sorted []uint16) (ret []filter.PortRange) {
	for _, p := range sorted {
		if n := len(ret); n > 0 && ret[n-1].Last+1 == p {
			ret[n-1].Last = p
			continue
		}
		ret = append(ret, filter.PortRange{First: p, Last: p})
	}
	return ret
}

// forwardTCP hands a client's connection to target on this device or, for
// an exit node, beyond it.
func (srv *tcServer) forwardTCP(target string) func(net.Conn) {
	return func(c net.Conn) {
		defer tcRecover("server tcp")
		srv.mu.Lock()
		srv.active[c] = struct{}{}
		srv.served++
		srv.mu.Unlock()
		srv.notify()
		defer func() {
			srv.mu.Lock()
			delete(srv.active, c)
			srv.mu.Unlock()
			c.Close()
			srv.notify()
		}()
		local, err := net.DialTimeout("tcp", target, 15*time.Second)
		if err != nil {
			srv.warnf("%s: %v", target, err)
			return
		}
		tailcat.ProxyConns(c, local)
	}
}

func (srv *tcServer) notify() {
	tcMu.Lock()
	l := tcListener
	tcMu.Unlock()
	if l != nil {
		l.OnTailcatChanged(TailcatServerID)
	}
}

func (srv *tcServer) set(f func(s *tcServer)) {
	srv.mu.Lock()
	f(srv)
	srv.mu.Unlock()
	srv.notify()
}

// TailcatServerStop stops the server and its clients' connections, and
// returns once they are gone.
func TailcatServerStop() {
	tsMu.Lock()
	srv := tsSrv
	tsSrv = nil
	tsMu.Unlock()
	if srv == nil {
		return
	}
	srv.cancel()
	srv.wg.Wait()
	srv.mu.Lock()
	s := srv.s
	for c := range srv.active {
		c.Close()
	}
	srv.mu.Unlock()
	if s != nil {
		s.Close()
	}
	slog.Info("Tailcat: server stopped")
	srv.notify()
}

// TailcatServerStatusJSON describes the server, or is "{}" when it is not
// running: {"state","error","active","served","clients"}, clients being the
// client keys that reached it in the last few minutes.
func TailcatServerStatusJSON() string {
	tsMu.Lock()
	srv := tsSrv
	tsMu.Unlock()
	if srv == nil {
		return "{}"
	}
	srv.mu.Lock()
	out := struct {
		State   string `json:"state"`
		Error   string `json:"error,omitempty"`
		Active  int    `json:"active"`
		Served  int    `json:"served"`
		Clients int    `json:"clients"`
	}{srv.state, srv.lastErr, len(srv.active), srv.served, 0}
	s := srv.s
	srv.mu.Unlock()
	if s != nil {
		if st := s.Status(); st != nil {
			for _, p := range st.Peer {
				if p.Active || time.Since(p.LastHandshake) < 3*time.Minute {
					out.Clients++
				}
			}
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// TailcatServerLog is the server's output, newest last.
func TailcatServerLog() string {
	tsMu.Lock()
	srv := tsSrv
	tsMu.Unlock()
	if srv == nil {
		return ""
	}
	return srv.text()
}

// wakeServer has a server that could not start try again now; part of
// tailcatNetworkChanged.
func wakeServer() {
	tsMu.Lock()
	srv := tsSrv
	tsMu.Unlock()
	if srv != nil {
		select {
		case srv.wake <- struct{}{}:
		default:
		}
	}
}
