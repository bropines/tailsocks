package appctr

// tailcat.go — tailcat (github.com/tailscale/tailcat) inside the bridge.
//
// tailcat is Tailscale's data plane without its control plane: a server
// started with `tailcat serve` hands out a tc… address, and a client holding
// it gets a WireGuard tunnel to that server over DERP, upgraded to a direct
// path when NAT traversal works. No Tailscale account, no VpnService.
//
// Each connection here forwards local TCP ports on 127.0.0.1 to ports on one
// tailcat server, the way `tailcat forward` does, and may run a SOCKS5 proxy
// that dials through it, the way `tailcat socks` does. It runs in the app process
// — not in the daemon, and independent of it — with its own WireGuard engine
// and network monitor. That monitor finds the interfaces through the getter
// netmon_android.go registers, and is woken on a network change through
// netmon.WakePollingMonitors (patch 21), since nothing else can reach it.
//
// A connection's output is kept per connection for its card, and goes to the
// Logs screen (category TAILCAT) and logcat as well.

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
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"
	"tailscale.com/net/socks5"
	"tailscale.com/types/key"
)

// TailcatListener is told when a connection's state changes; it reads the
// details with TailcatStatusJSON. Called on a Go thread.
type TailcatListener interface {
	OnTailcatChanged(id string)
}

// tcBind is where forwarded ports listen: this device only.
const tcBind = "127.0.0.1"

// tcMaxLog is how many lines of a connection's output are kept.
const tcMaxLog = 500

// tcPathProbeEvery is how often a running connection asks how its packets go.
// A disco ping also drives NAT traversal, so the first few come quickly.
const tcPathProbeEvery = time.Minute

// tcRetryEvery is how often a server that did not answer is asked again.
const tcRetryEvery = 30 * time.Second

// tcDialTimeout bounds one dial through the tunnel; two WireGuard handshake
// retransmits fit in it.
const tcDialTimeout = 12 * time.Second

type tcMapping struct {
	listen string         // 127.0.0.1:<port>
	port   uint16         // a port on the server, or
	target netip.AddrPort // an address behind a server that is an exit node
}

type tcConn struct {
	tcLog
	id     string
	cl     *tailcat.Client
	lns    []net.Listener
	cancel context.CancelFunc
	wg     sync.WaitGroup
	wake   chan struct{} // a network change: probe the path now

	mu        sync.Mutex
	state     string // starting, forwarding, error
	listening []string
	socks     string // the SOCKS5 proxy's address, or ""
	lastErr   string
	path      string // "direct" or a relay's region code; "" before the first probe
	latencyMs int
	active    map[net.Conn]struct{}
	served    int
}

var (
	tcMu       sync.Mutex
	tcConns    = map[string]*tcConn{}
	tcListener TailcatListener
	tcCacheDir string
)

// SetTailcatListener registers the Kotlin callback; nil clears it.
func SetTailcatListener(l TailcatListener) {
	tcMu.Lock()
	tcListener = l
	tcMu.Unlock()
}

// SetTailcatCacheDir is where fetched DERP maps are kept between runs.
func SetTailcatCacheDir(dir string) {
	tcMu.Lock()
	tcCacheDir = dir
	tcMu.Unlock()
}

// TailcatGenerateClientKey returns a new client identity as "privkey:…", the
// form TailcatStart takes. Its public half, TailcatPublicKey, is what a
// server lists in `tailcat serve --allow` to let only this device in.
func TailcatGenerateClientKey() string {
	b, _ := key.NewNode().MarshalText()
	return string(b)
}

// TailcatPublicKey is the "nodekey:…" of a private client key, or "" when the
// key cannot be read.
func TailcatPublicKey(private string) string {
	var k key.NodePrivate
	if err := k.UnmarshalText([]byte(strings.TrimSpace(private))); err != nil {
		return ""
	}
	return k.Public().String()
}

// TailcatCheckAddress returns "" for a usable tc… address, or why it is not.
func TailcatCheckAddress(addr string) string {
	if _, err := tailcat.ParseAddr(tailcat.Addr(strings.TrimSpace(addr))); err != nil {
		return err.Error()
	}
	return ""
}

// TailcatCheckMappings returns "" when every port mapping is one tailcat
// accepts, or which one is not; the editor shows it before anything starts.
func TailcatCheckMappings(mappings string) string {
	if _, err := tcParseMappings(mappings); err != nil {
		return err.Error()
	}
	return ""
}

// tcParseMappings reads port mappings the way `tailcat forward` does
// (parseForwardSpec in its cmd/tailcat/forward.go), separated by commas,
// spaces or new lines:
//
//	8080                    127.0.0.1:8080 -> port 8080 on the server
//	18080:8080              127.0.0.1:18080 -> port 8080 on the server
//	0:8080                  a free local port the system picks
//	3001:192.168.1.5:3001   through a server that is an exit node
//	5555:[fd7a::1]:5555     the same, IPv6
func tcParseMappings(specs string) ([]tcMapping, error) {
	var out []tcMapping
	for _, spec := range strings.FieldsFunc(specs, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == '\t' }) {
		local, target, hasColon := strings.Cut(spec, ":")
		if !hasColon {
			target = local
		}
		var lp uint64
		if !hasColon || local != "0" {
			p, err := strconv.ParseUint(local, 10, 16)
			if err != nil || p == 0 {
				return nil, fmt.Errorf("%s: the local port is not a port", spec)
			}
			lp = p
		}
		m := tcMapping{listen: net.JoinHostPort(tcBind, strconv.FormatUint(lp, 10))}
		if p, err := strconv.ParseUint(target, 10, 16); err == nil && p != 0 {
			m.port = uint16(p)
		} else if ap, err := netip.ParseAddrPort(target); err == nil && ap.Port() != 0 {
			m.target = ap
		} else {
			return nil, fmt.Errorf("%s: the remote side is neither a port nor ip:port", spec)
		}
		out = append(out, m)
	}
	if len(out) == 0 {
		return nil, errors.New("no port mappings")
	}
	return out, nil
}

// tcLog is the output of a connection or of the server: kept for its card,
// and put in the Logs screen's buffer (category TAILCAT) and in logcat under
// its name.
type tcLog struct {
	name  string
	logMu sync.Mutex
	lines []string
}

func (l *tcLog) logf(format string, args ...any) { l.logAt("INFO", format, args...) }

// warnf is logf for what went wrong: a server that does not answer, a dial
// that failed.
func (l *tcLog) warnf(format string, args ...any) { l.logAt("WARN", format, args...) }

func (l *tcLog) logAt(level, format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	l.logMu.Lock()
	l.lines = append(l.lines, time.Now().Format("15:04:05 ")+msg)
	if len(l.lines) > tcMaxLog {
		l.lines = l.lines[len(l.lines)-tcMaxLog:]
	}
	l.logMu.Unlock()
	LogAndroid(level, "TAILCAT", l.name+": "+msg)
	fmt.Fprintf(os.Stdout, "tailcat %s: %s\n", l.name, msg)
}

func (l *tcLog) text() string {
	l.logMu.Lock()
	defer l.logMu.Unlock()
	return strings.Join(l.lines, "\n")
}

// tcQuiet matches what tailcat's engine says that a card has no use for;
// what stays is the link state, the relay, the server's answer, path and
// endpoint changes, and errors.
var tcQuiet = regexp.MustCompile(strings.Join([]string{
	`^(\S+: )?\[v\d\] `,                                // verbose, which the CLI hides without --verbose
	`RTMGRP failed, falling back to polling`,           // Android: no netlink groups
	`failed to force-set UDP (read|write) buffer size`, // Android: no CAP_NET_ADMIN
	`^NetworkMap: `,                                    // the whole netmap, node keys included
	`^(Creating|Bringing|Clearing|Starting network monitor|Engine created|disco pub key|dns: |wgengine: Reconfig)`,
	`^magicsock: (disco key|SetPrivateKey|new contact|adding connection|\d+ active derp conns)`,
	`^ping\(|^wgengine: got TSMP pong`, // the path probes themselves
}, "|"))

// engineLogf is tailcat's own log, minus tcQuiet.
func (l *tcLog) engineLogf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if tcQuiet.MatchString(msg) {
		return
	}
	l.logf("%s", strings.TrimRight(msg, "\n"))
}

func (c *tcConn) notify() {
	tcMu.Lock()
	l := tcListener
	tcMu.Unlock()
	if l != nil {
		l.OnTailcatChanged(c.id)
	}
}

func (c *tcConn) set(f func(c *tcConn)) {
	c.mu.Lock()
	f(c)
	c.mu.Unlock()
	c.notify()
}

// TailcatStart listens on every mapping — all of them or none — and forwards
// each accepted connection to the tailcat server at addr. privateKey is a
// "privkey:…" client key, or "" for a throwaway identity. A socksPort other
// than 0 also runs a SOCKS5 proxy there (see serveSOCKS), behind socksUser and
// socksPass when they are set; mappings may then be empty. name is how the
// shared log names the connection.
func TailcatStart(id, name, addr, privateKey, mappings string, socksPort int, socksUser, socksPass string) error {
	tcMu.Lock()
	_, running := tcConns[id]
	cacheDir := tcCacheDir
	tcMu.Unlock()
	if running {
		return fmt.Errorf("already running")
	}
	if msg := TailcatCheckAddress(addr); msg != "" {
		return errors.New(msg)
	}
	var ms []tcMapping
	if strings.TrimSpace(mappings) != "" || socksPort == 0 {
		var err error
		if ms, err = tcParseMappings(mappings); err != nil {
			return err
		}
	}
	if socksPort < 0 || socksPort > 65535 {
		return fmt.Errorf("%d: the SOCKS5 port is not a port", socksPort)
	}
	var k key.NodePrivate
	if p := strings.TrimSpace(privateKey); p != "" {
		if err := k.UnmarshalText([]byte(p)); err != nil {
			return fmt.Errorf("client key: %w", err)
		}
	}

	c := &tcConn{tcLog: tcLog{name: name}, id: id, active: map[net.Conn]struct{}{}, wake: make(chan struct{}, 1), state: "starting"}
	listen := func(addr string) (net.Listener, error) {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			for _, l := range c.lns {
				l.Close()
			}
			return nil, fmt.Errorf("listen on %s: %w", addr, err)
		}
		c.lns = append(c.lns, ln)
		return ln, nil
	}
	for _, m := range ms {
		ln, err := listen(m.listen)
		if err != nil {
			return err
		}
		c.listening = append(c.listening, ln.Addr().String())
	}
	var socksLn net.Listener
	if socksPort != 0 {
		var err error
		if socksLn, err = listen(net.JoinHostPort(tcBind, strconv.Itoa(socksPort))); err != nil {
			return err
		}
		c.socks = socksLn.Addr().String()
	}
	c.cl = &tailcat.Client{Server: tailcat.Addr(strings.TrimSpace(addr)), Key: k, Logf: c.engineLogf}
	if cacheDir != "" {
		c.cl.DERPMapCache = tcDirCache(filepath.Join(cacheDir, "derpmaps"))
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.cancel = cancel

	tcMu.Lock()
	tcConns[id] = c
	tcMu.Unlock()
	slog.Info("Tailcat: connection started", "id", id, "listening", strings.Join(c.listening, ", "), "socks", c.socks)

	for i, m := range ms {
		c.wg.Add(1)
		go c.accept(ctx, c.lns[i], m)
	}
	if socksLn != nil {
		c.wg.Add(1)
		go c.serveSOCKS(ctx, socksLn, socksUser, socksPass)
	}
	c.wg.Add(1)
	go c.watch(ctx)
	c.notify()
	return nil
}

// watch brings the tunnel up straight away, so the card says whether the
// server answers before anything is sent, and keeps asking until it does —
// the server may start after the client. Then it keeps asking how packets
// travel: a few quick disco pings while NAT traversal may still find a direct
// path, one a minute after, and a few quick ones when the network changes.
func (c *tcConn) watch(ctx context.Context) {
	defer c.wg.Done()
	defer tcRecover("watch " + c.id)

	for {
		pctx, pcancel := context.WithTimeout(ctx, 20*time.Second)
		res, err := c.cl.Ping(pctx)
		pcancel()
		if ctx.Err() != nil {
			return
		}
		if err == nil {
			c.logf("the server answered through the relay in %v", res.Latency.Round(time.Millisecond))
			c.set(func(c *tcConn) { c.state, c.lastErr = "forwarding", "" })
			break
		}
		c.warnf("the server did not answer: %v", err)
		why := tcWhy(err)
		c.set(func(c *tcConn) { c.state, c.lastErr = "error", why })
		if !c.sleep(ctx, tcRetryEvery) {
			return
		}
	}

	quick := 5
	for {
		c.probePath(ctx)
		wait := tcPathProbeEvery
		if quick > 0 {
			quick--
			wait = 2 * time.Second
			c.mu.Lock()
			if c.path == "direct" {
				quick = 0
			}
			c.mu.Unlock()
		}
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-c.wake:
			t.Stop()
			quick = 3
		case <-t.C:
		}
	}
}

// tcWhy is an error as the card shows it: "" for a server that never
// answered — offline, or its --allow leaves this key out, which look alike
// from here — so the app can say that in its own words.
func tcWhy(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return ""
	}
	return err.Error()
}

// sleep waits d, or less when the network changes; false once the connection stops.
func (c *tcConn) sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-c.wake:
	case <-t.C:
	}
	return true
}

func (c *tcConn) probePath(ctx context.Context) {
	c.remeow(ctx)
	pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	pr, err := c.cl.DiscoPing(pctx)
	if err != nil || pr == nil || pr.Err != "" {
		return
	}
	path := "direct"
	if pr.Endpoint == "" {
		path = pr.DERPRegionCode
		if path == "" {
			path = "relay"
		}
	}
	ms := int(pr.LatencySeconds * 1000)
	c.mu.Lock()
	changed := c.path != path
	c.path, c.latencyMs = path, ms
	c.mu.Unlock()
	if changed {
		c.logf("path: %s, %d ms", path, ms)
		c.notify()
	}
}

func (c *tcConn) accept(ctx context.Context, ln net.Listener, m tcMapping) {
	defer c.wg.Done()
	defer tcRecover("accept " + c.id)
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		c.track(conn)
		c.wg.Add(1)
		go func() {
			defer c.wg.Done()
			defer tcRecover("forward " + c.id)
			defer c.untrack(conn)
			remote, err := c.dialRetry(ctx, func(ctx context.Context) (net.Conn, error) {
				if m.target.IsValid() {
					return c.cl.DialTCP(ctx, m.target)
				}
				return c.cl.DialTCPPort(ctx, m.port)
			})
			if err != nil {
				if ctx.Err() == nil {
					to := strconv.Itoa(int(m.port))
					if m.target.IsValid() {
						to = m.target.String()
					}
					c.dialFailed(fmt.Sprintf("%s -> %s", conn.LocalAddr(), to), err)
				}
				return
			}
			c.dialed()
			tailcat.ProxyConns(conn, remote)
		}()
	}
}

// remeow sends the client's registration to the server again. tailcat
// registers a client once, on first use ("meow"), and a server that has
// restarted since — same address, nothing remembered — drops its packets
// until it gets another. Ping sends one on every call and, once the first
// was acknowledged, returns without waiting; the server acks duplicates.
func (c *tcConn) remeow(ctx context.Context) {
	pctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	c.cl.Ping(pctx)
}

// dialRetry dials, and once more after registering again when the first try
// fails: the server may have restarted and forgotten this client.
func (c *tcConn) dialRetry(ctx context.Context, dial func(context.Context) (net.Conn, error)) (net.Conn, error) {
	dctx, cancel := context.WithTimeout(ctx, tcDialTimeout)
	conn, err := dial(dctx)
	cancel()
	if err == nil || ctx.Err() != nil {
		return conn, err
	}
	c.remeow(ctx)
	dctx, cancel = context.WithTimeout(ctx, tcDialTimeout)
	defer cancel()
	return dial(dctx)
}

// track counts an accepted connection, for the card; untrack closes it and
// lets it go. A stop closes whatever is still tracked.
func (c *tcConn) track(conn net.Conn) {
	c.mu.Lock()
	c.active[conn] = struct{}{}
	c.served++
	c.mu.Unlock()
	c.notify()
}

func (c *tcConn) untrack(conn net.Conn) {
	c.mu.Lock()
	delete(c.active, conn)
	c.mu.Unlock()
	conn.Close()
	c.notify()
}

// dialed marks the connection forwarding once a dial through it worked, even
// before the watcher's ping says so.
func (c *tcConn) dialed() {
	c.mu.Lock()
	up := c.state == "forwarding"
	c.mu.Unlock()
	if !up {
		c.set(func(c *tcConn) { c.state, c.lastErr = "forwarding", "" })
	}
}

func (c *tcConn) dialFailed(what string, err error) {
	c.warnf("%s: %v", what, err)
	why := tcWhy(err)
	c.set(func(c *tcConn) { c.lastErr = why })
}

// serveSOCKS runs a SOCKS5 proxy on ln that dials through the server, as
// `tailcat socks <addr>` does (clientSOCKSMode in its cmd/tailcat), TCP and
// UDP: the host "server.tailcat", or the server's own tc… address, means a
// port on the server; any other host is reached through the server, which
// must then run as an exit node (tailcat serve exit-node). Names are resolved
// on this device.
func (c *tcConn) serveSOCKS(ctx context.Context, ln net.Listener, user, pass string) {
	defer c.wg.Done()
	defer tcRecover("socks " + c.id)
	ss := &socks5.Server{
		Logf:     func(format string, args ...any) { c.logf("socks5: "+format, args...) },
		Username: user,
		Password: pass,
		Dialer: func(_ context.Context, network, addr string) (net.Conn, error) {
			// Not socks5's context: it gives a dial 5 s, WireGuard's handshake
			// retransmit interval, so one lost handshake packet would fail it
			// (the CLI allows 15); dialRetry bounds each try itself.
			conn, err := c.dialRetry(ctx, func(ctx context.Context) (net.Conn, error) { return c.dialSOCKS(ctx, network, addr) })
			if err != nil {
				if ctx.Err() == nil {
					c.dialFailed("socks5 "+network+" "+addr, err)
				}
				return nil, err
			}
			c.dialed()
			return conn, nil
		},
	}
	ss.Serve(tcTrackingListener{ln, c})
}

// dialSOCKS dials a SOCKS5 destination through the server; see serveSOCKS
// and classifySOCKSAddr in tailcat's cmd/tailcat, which it follows.
func (c *tcConn) dialSOCKS(ctx context.Context, network, addr string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	p, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return nil, err
	}
	port := uint16(p)
	if host == "" || host == "server.tailcat" || host == string(c.cl.Server) {
		if network == "udp" {
			return c.cl.DialUDPPort(ctx, port)
		}
		return c.cl.DialTCPPort(ctx, port)
	}
	if strings.HasPrefix(host, "tc") && !strings.Contains(host, ".") {
		if _, err := tailcat.ParseAddr(tailcat.Addr(host)); err == nil {
			return nil, errors.New("that is another tailcat server; this proxy reaches only its own")
		}
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
		if len(ips) == 0 {
			return nil, fmt.Errorf("no addresses found for %q", host)
		}
		// IPv4 first: it rides the server's NAT64 mapping, and the server may
		// have no IPv6.
		ip = ips[0]
		for _, a := range ips {
			if a.Unmap().Is4() {
				ip = a
				break
			}
		}
	}
	ap := netip.AddrPortFrom(ip.Unmap(), port)
	if network == "udp" {
		return c.cl.DialUDP(ctx, ap)
	}
	return c.cl.DialTCP(ctx, ap)
}

// tcTrackingListener counts the SOCKS5 proxy's clients as accept counts the
// forwarded ones; socks5.Server accepts them itself.
type tcTrackingListener struct {
	net.Listener
	c *tcConn
}

func (l tcTrackingListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	l.c.track(conn)
	return &tcTrackedConn{Conn: conn, c: l.c}, nil
}

type tcTrackedConn struct {
	net.Conn
	c    *tcConn
	once sync.Once
}

func (t *tcTrackedConn) Close() error {
	t.once.Do(func() { t.c.untrack(t.Conn) })
	return nil
}

// TailcatStop closes a connection's listeners, its forwarded connections and
// its WireGuard engine, and returns once all of them have let go, so a restart
// can bind the same ports at once.
func TailcatStop(id string) {
	tcMu.Lock()
	c := tcConns[id]
	delete(tcConns, id)
	tcMu.Unlock()
	if c == nil {
		return
	}
	c.cancel()
	for _, ln := range c.lns {
		ln.Close()
	}
	c.mu.Lock()
	for conn := range c.active {
		conn.Close()
	}
	c.mu.Unlock()
	c.wg.Wait()
	c.cl.Close()
	slog.Info("Tailcat: connection stopped", "id", id)
	c.notify()
}

// TailcatStopAll stops every connection.
func TailcatStopAll() {
	tcMu.Lock()
	ids := make([]string, 0, len(tcConns))
	for id := range tcConns {
		ids = append(ids, id)
	}
	tcMu.Unlock()
	for _, id := range ids {
		TailcatStop(id)
	}
}

// TailcatStatusJSON describes every running connection:
// {"<id>":{"state","listening","socks","error","path","latencyMs","active","served"}}.
// A connection that is not in it is stopped.
func TailcatStatusJSON() string {
	type st struct {
		State     string   `json:"state"`
		Listening []string `json:"listening"`
		Socks     string   `json:"socks,omitempty"`
		Error     string   `json:"error,omitempty"`
		Path      string   `json:"path,omitempty"`
		LatencyMs int      `json:"latencyMs,omitempty"`
		Active    int      `json:"active"`
		Served    int      `json:"served"`
	}
	out := map[string]st{}
	tcMu.Lock()
	for id, c := range tcConns {
		c.mu.Lock()
		out[id] = st{c.state, c.listening, c.socks, c.lastErr, c.path, c.latencyMs, len(c.active), c.served}
		c.mu.Unlock()
	}
	tcMu.Unlock()
	b, _ := json.Marshal(out)
	return string(b)
}

// TailcatLog is a connection's output, newest last.
func TailcatLog(id string) string {
	tcMu.Lock()
	c := tcConns[id]
	tcMu.Unlock()
	if c == nil {
		return ""
	}
	return c.text()
}

// tailcatNetworkChanged wakes the network monitors of every tailcat client
// and of the server — they poll every ten minutes on Android otherwise — has
// each connection look at its path again, and a server that could not start
// try again. Called from InjectNetworkState.
func tailcatNetworkChanged() {
	netmon.WakePollingMonitors()
	tcMu.Lock()
	for _, c := range tcConns {
		select {
		case c.wake <- struct{}{}:
		default:
		}
	}
	tcMu.Unlock()
	wakeServer()
}

// tcRecover keeps a bug in our own forwarding goroutines from taking the
// whole app down with it. tailcat's and Tailscale's own goroutines cannot be
// guarded from here.
func tcRecover(where string) {
	if r := recover(); r != nil {
		slog.Error("Tailcat: recovered from a panic", "in", where, "panic", fmt.Sprint(r), "stack", string(debug.Stack()))
	}
}

// tcDirCache is tailcat.DERPMapCache on disk, as the CLI keeps its own.
type tcDirCache string

func (d tcDirCache) path(url string) string {
	return filepath.Join(string(d), strings.NewReplacer("/", "_", ":", "_").Replace(url))
}

func (d tcDirCache) Get(url string) ([]byte, string, time.Time, bool) {
	p := d.path(url)
	fi, err := os.Stat(p + ".json")
	if err != nil {
		return nil, "", time.Time{}, false
	}
	data, err := os.ReadFile(p + ".json")
	if err != nil {
		return nil, "", time.Time{}, false
	}
	etag, _ := os.ReadFile(p + ".etag")
	return data, strings.TrimSpace(string(etag)), fi.ModTime(), true
}

func (d tcDirCache) Put(url string, data []byte, etag string) error {
	p := d.path(url)
	if err := os.MkdirAll(string(d), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(p+".json", data, 0o600); err != nil {
		return err
	}
	return os.WriteFile(p+".etag", []byte(etag), 0o600)
}
