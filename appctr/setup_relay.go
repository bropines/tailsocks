package appctr

// setup_relay.go — a setup session's second road: tailcat.
//
// A phone that is not on the television's network cannot reach its LAN
// address: another Wi-Fi, a guest network, mobile data, an access point that
// keeps its clients apart. tailcat (tailcat.go) gets it there anyway. The
// television starts a tailcat server, the phone a client, and a WireGuard
// tunnel between them runs through Tailscale's relays — or directly, once NAT
// traversal finds a way. Neither side needs an account.
//
// The tunnel is reachability and nothing else. What goes through it is the
// request that would have gone over the LAN, sealed the same way
// (setup_wire.go), and the television's answers carry the same tags; nothing a
// session decides rests on who is at the other end of the tunnel, or on
// anyone who runs a relay.
//
// The keys. A tailcat address is a hundred bytes — the server's two public
// keys and a pre-shared key — and a code that carried one would be three
// times as long and no longer readable across a room. So nothing is carried:
// both ends derive the server's key and the pre-shared key from the code's
// secret (setupRelaySeed), and the code names only the relay region, in two
// bytes. That makes the server's private key known to whoever read the code.
// It is meant to be: holding the code is the only thing that ever admitted
// anyone, and someone who has it and impersonates the server gets a sealed
// request they cannot open and cannot answer.
//
// The clients. A tailcat server can be told which client keys to let in. A
// session lets in any: there is no client to name before the code is read,
// and a client key derived from the code would be one more thing every reader
// of the code has — and two phones that shared it would knock each other off
// the tunnel. Each phone comes with a key of its own, made for the one send.
//
// The server. The bridge keeps one tailcat server for the user
// (tailcat_server.go), with an address that is theirs to hand out and keep. A
// session does not touch it. Serving setup from it would put that lasting
// address on a television's screen and tie the session to its allow-list and
// its lifetime. Nor is the session turned away while it runs: tailcat has no
// one-per-process limit, only the bridge's own bookkeeping does, so a session
// starts a server of its own beside it — its own key, one port, gone with
// the session.

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

const (
	// setupRelayPort is the one port a session's tailcat server takes
	// connections on, on its own address inside the tunnel.
	setupRelayPort = 7771
	// setupRelayOpenTimeout bounds what a television does before it can draw
	// a code that names the relay: the relay map, the nearest region, the
	// server. Past it the code goes up without one.
	setupRelayOpenTimeout = 8 * time.Second
	// setupRelayDialTimeout bounds a phone's way through the relay: the map,
	// the relay, the television's acknowledgement, the tunnel, a connection.
	// Three seconds did it on a good line; the rest is for a poor one, and is
	// what a phone waits to learn that no television is there any more.
	setupRelayDialTimeout = 15 * time.Second
	// setupRelayDrain is how long a closing end waits for its last segments
	// to be acknowledged. The TCP stack is in this process: closed at once,
	// an answer just written may never leave.
	setupRelayDrain = 2 * time.Second
	// setupRelayRegionKeep is how long the region a television picked is
	// taken to be the nearest still, so a second code does not wait for a
	// second measurement.
	setupRelayRegionKeep = 10 * time.Minute
	// setupRelayProbe bounds the one question asked when a relay does not
	// answer: is it there at all.
	setupRelayProbe = 3 * time.Second
)

var (
	// errSetupRelayDown: this device does not get to the relay.
	errSetupRelayDown = errors.New("the relay cannot be reached")
	// errSetupRelayNobody: it does, and no television answers there.
	errSetupRelayNobody = errors.New("nobody answers through the relay")
)

// setupRelayKeys is the tunnel's key material, the same on both ends.
type setupRelayKeys struct {
	node key.NodePrivate      // the server's WireGuard key
	psk  tailcat.PresharedKey // and the pre-shared key of its tunnels
}

// setupRelayKeysOf derives them from a code's key and secret.
func setupRelayKeysOf(pub, secret []byte) (setupRelayKeys, error) {
	var k setupRelayKeys
	seed, err := setupRelaySeed(pub, secret)
	if err != nil {
		return k, err
	}
	defer clear(seed)
	// A Curve25519 private key, in the form every implementation stores it.
	seed[0] &= 248
	seed[31] = seed[31]&127 | 64
	if err := k.node.UnmarshalText([]byte("privkey:" + hex.EncodeToString(seed[:32]))); err != nil {
		return k, err
	}
	copy(k.psk[:], seed[32:])
	return k, nil
}

// setupRelay is the relay as a session uses it. The real one is tailcat;
// the tests have one that never leaves the process.
type setupRelay interface {
	// open starts taking connections through the relay and hands each to
	// handle. It returns what closes it again and the region it listens in.
	open(ctx context.Context, keys setupRelayKeys, handle func(net.Conn)) (io.Closer, uint16, error)
	// dial connects to the television that listens in region. An error is
	// errSetupRelayDown, errSetupRelayNobody or ctx's.
	dial(ctx context.Context, keys setupRelayKeys, region uint16) (net.Conn, error)
}

// tailcatRelay is the relay over tailcat. mapURL is where the relay map
// comes from, "" for tailcat's own.
type tailcatRelay struct {
	mapURL string
}

// setupRelayLogf drops what tailcat's engines say. Their output names keys
// and endpoints, and a session's are derived from its code.
func setupRelayLogf(string, ...any) {}

// mapOpts are the options of a relay map fetch. The server's copy is cached
// apart from the clients': the map's server may answer the two differently.
func (r tailcatRelay) mapOpts(server bool) []any {
	var opts []any
	dir := "derpmaps"
	if server {
		opts = append(opts, tailcat.ExpandForServer)
		dir = "derpmaps-setup"
	}
	if r.mapURL != "" {
		opts = append(opts, tailcat.DERPMapURL(r.mapURL))
	}
	tcMu.Lock()
	cacheDir := tcCacheDir
	tcMu.Unlock()
	if cacheDir != "" {
		opts = append(opts, tcDirCache(filepath.Join(cacheDir, dir)))
	}
	return opts
}

// setupRelayRegion is the region the last session listened in.
var setupRelayRegion struct {
	sync.Mutex
	id tailcfg.DERPRegionID
	at time.Time
}

// setupRelayForget drops it: the network changed, and with it what is near.
func setupRelayForget() {
	setupRelayRegion.Lock()
	setupRelayRegion.id = 0
	setupRelayRegion.Unlock()
}

// region picks where to listen: the region of a moment ago if its relay
// still answers, else the nearest by measurement, which takes a few seconds.
func (r tailcatRelay) region(ctx context.Context, dm *tailcfg.DERPMap) (*tailcfg.DERPRegion, error) {
	setupRelayRegion.Lock()
	id, at := setupRelayRegion.id, setupRelayRegion.at
	setupRelayRegion.Unlock()
	if reg := dm.Regions[id]; id != 0 && reg != nil && time.Since(at) < setupRelayRegionKeep && setupRelayAnswers(ctx, reg) {
		return reg, nil
	}
	id, err := tailcat.PickBestRegion(ctx, dm)
	if err != nil {
		return nil, err
	}
	reg := dm.Regions[id]
	if id <= 0 || id > 0xffff || reg == nil {
		return nil, errors.New("no relay region answered")
	}
	setupRelayRegion.Lock()
	setupRelayRegion.id, setupRelayRegion.at = id, time.Now()
	setupRelayRegion.Unlock()
	return reg, nil
}

// setupRelayAnswers reports whether a region's relay takes a connection.
func setupRelayAnswers(ctx context.Context, reg *tailcfg.DERPRegion) bool {
	ctx, cancel := context.WithTimeout(ctx, setupRelayProbe)
	defer cancel()
	for _, n := range reg.Nodes {
		port := n.DERPPort
		if port == 0 {
			port = 443
		}
		var d net.Dialer
		c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(n.HostName, strconv.Itoa(port)))
		if err == nil {
			c.Close()
			return true
		}
	}
	return false
}

func (r tailcatRelay) open(ctx context.Context, keys setupRelayKeys, handle func(net.Conn)) (_ io.Closer, _ uint16, err error) {
	// tailcat and the engine under it are not ours to vouch for; a session
	// without a relay is better than an app that is gone.
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("relay: %v", p)
		}
	}()
	dm, err := tailcat.FetchDERPMap(ctx, r.mapOpts(true)...)
	if err != nil {
		return nil, 0, fmt.Errorf("relay map: %w", err)
	}
	reg, err := r.region(ctx, dm)
	if err != nil {
		return nil, 0, err
	}
	s := &tailcat.Server{
		Key:          keys.node,
		PresharedKey: keys.psk,
		Region:       reg,
		Logf:         setupRelayLogf,
		// One port, in the packet filter and again where connections are
		// handed out. No forwarding, no UDP: this server is not a way into
		// anything but the session.
		ServedTCPPorts: []filter.PortRange{{First: setupRelayPort, Last: setupRelayPort}},
		OnTCP: func(port uint16) func(net.Conn) {
			if port != setupRelayPort {
				return nil
			}
			return func(c net.Conn) {
				defer tcRecover("setup relay")
				handle(c)
			}
		},
	}
	if err := s.Start(); err != nil {
		return nil, 0, err
	}
	if ctx.Err() != nil {
		s.Close()
		return nil, 0, ctx.Err()
	}
	return tailcatRelayServer{s}, uint16(reg.RegionID), nil
}

// tailcatRelayServer closes a session's server once what it last wrote has
// been taken.
type tailcatRelayServer struct{ s *tailcat.Server }

func (t tailcatRelayServer) Close() error {
	ctx, cancel := context.WithTimeout(context.Background(), setupRelayDrain)
	_ = t.s.DrainTCP(ctx)
	cancel()
	return t.s.Close()
}

func (r tailcatRelay) dial(ctx context.Context, keys setupRelayKeys, region uint16) (_ net.Conn, err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("%w: %v", errSetupRelayDown, p)
		}
	}()
	// The address a tailcat client is given, put together here from what the
	// code's secret yields instead of being read off the screen.
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: keys.node.Public()},
		ServerDiscoPublic: tailcat.DiscoPublicForNode(keys.node),
		PresharedKey:      keys.psk,
		RegionID:          tailcfg.DERPRegionID(region),
	}
	cl := &tailcat.Client{Server: ci.Addr(), Logf: setupRelayLogf, DERPMapURL: r.mapURL}
	for _, opt := range r.mapOpts(false) {
		if cache, ok := opt.(tailcat.DERPMapCache); ok {
			cl.DERPMapCache = cache
		}
	}
	fail := func(err error) (net.Conn, error) {
		cl.Close()
		return nil, err
	}

	// The first exchange with the server goes through the relay alone, and
	// says whether the television is there. tailcat gives one try ten
	// seconds whatever it is asked for, so it is asked again.
	for {
		if _, err = cl.Ping(ctx); err == nil || ctx.Err() != nil || !errors.Is(err, context.DeadlineExceeded) {
			break
		}
	}
	switch {
	case err == nil:
	case errors.Is(ctx.Err(), context.Canceled):
		return fail(ctx.Err())
	case !errors.Is(err, context.DeadlineExceeded):
		// No relay map, or one without the code's region.
		slog.Warn("Setup: the relay cannot be used", "err", err)
		return fail(errSetupRelayDown)
	default:
		// Nothing came back in time: is it the relay, or the television?
		probe, cancel := context.WithTimeout(context.Background(), setupRelayProbe)
		defer cancel()
		dm, merr := tailcat.FetchDERPMap(probe, r.mapOpts(false)...)
		if merr != nil || dm.Regions[tailcfg.DERPRegionID(region)] == nil || !setupRelayAnswers(probe, dm.Regions[tailcfg.DERPRegionID(region)]) {
			return fail(errSetupRelayDown)
		}
		return fail(errSetupRelayNobody)
	}
	conn, err := cl.DialTCPPort(ctx, setupRelayPort)
	if err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			return fail(ctx.Err())
		}
		return fail(errSetupRelayNobody)
	}
	return &setupRelayConn{Conn: conn, cl: cl}, nil
}

// setupRelayConn is a phone's connection through the relay, with the client
// that carries it: closing the one closes the other, after its last segments
// are out.
type setupRelayConn struct {
	net.Conn
	cl   *tailcat.Client
	once sync.Once
}

func (c *setupRelayConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() {
		go func() {
			defer tcRecover("setup relay close")
			ctx, cancel := context.WithTimeout(context.Background(), setupRelayDrain)
			_ = c.cl.DrainTCP(ctx)
			cancel()
			c.cl.Close()
		}()
	})
	return err
}
