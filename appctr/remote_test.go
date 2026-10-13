package appctr

// The tests of remote management run the real endpoint on a loopback port
// against a fake daemon: a LocalAPI that answers whois and status with the
// daemon's own types (apitype.WhoIsResponse, ipnstate.Status), and a proxy map
// filled the way netstack's forwardTCP fills it — the loopback source address
// of a connection stands for the tailnet node it was opened for.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/views"
)

const (
	rtUserSelf  = tailcfg.UserID(1001)
	rtUserOther = tailcfg.UserID(2002)
)

// rtNode is a node of the fake tailnet.
type rtNode struct {
	id      string
	ip      string
	name    string
	user    tailcfg.UserID
	tags    []string
	sharer  tailcfg.UserID
	sharee  bool
	expired bool
	// unsigned: tailnet lock has not signed the node.
	unsigned bool
	// As a peer in the status: an exit node on offer, its location.
	exitOption bool
	exitNode   bool
	offline    bool
	country    string
	key        key.NodePublic
}

func (n *rtNode) addr() netip.Addr { return netip.MustParseAddr(n.ip) }

var (
	rtSelf     = &rtNode{id: "nSELFtv", ip: "100.64.0.1", name: "living-room", user: rtUserSelf}
	rtPhone    = &rtNode{id: "nPHONE", ip: "100.64.0.2", name: "pixel", user: rtUserSelf}
	rtTablet   = &rtNode{id: "nTABLET", ip: "100.64.0.3", name: "tab", user: rtUserSelf}
	rtFriend   = &rtNode{id: "nFRIEND", ip: "100.64.0.4", name: "friends-phone", user: rtUserOther}
	rtTagged   = &rtNode{id: "nTAGGED", ip: "100.64.0.5", name: "ci-runner", user: rtUserSelf, tags: []string{"tag:ci"}}
	rtSharedIn = &rtNode{id: "nSHARED", ip: "100.64.0.6", name: "laptop.other.ts.net", user: rtUserSelf, sharer: rtUserOther}
	rtSharee   = &rtNode{id: "nSHAREE", ip: "100.64.0.7", name: "sharee", user: rtUserOther, sharee: true}
	rtExpired  = &rtNode{id: "nEXPIRED", ip: "100.64.0.8", name: "old-phone", user: rtUserSelf, expired: true}
	rtUnsigned = &rtNode{id: "nUNSIGNED", ip: "100.64.0.9", name: "unsigned", user: rtUserSelf, unsigned: true}
	rtExitA    = &rtNode{id: "nEXITA", ip: "100.64.0.20", name: "home-server", user: rtUserSelf, exitOption: true}
	rtExitB    = &rtNode{id: "nEXITB", ip: "100.64.0.21", name: "se-sto-wg-001", user: rtUserOther, exitOption: true, offline: true, country: "Sweden"}
)

func rtAllNodes() []*rtNode {
	return []*rtNode{rtSelf, rtPhone, rtTablet, rtFriend, rtTagged, rtSharedIn, rtSharee, rtExpired, rtUnsigned, rtExitA, rtExitB}
}

// remoteFakeDaemon is the LocalAPI of a tailscaled, as far as remote
// management asks it.
type remoteFakeDaemon struct {
	mu    sync.Mutex
	self  *rtNode
	nodes map[netip.Addr]*rtNode
	// proxy is the daemon's proxy map: loopback source address -> the tailnet
	// address the connection was forwarded for.
	proxy map[netip.AddrPort]netip.Addr
	// down makes every call fail, as with no daemon; noSelf answers status
	// without a self node, as before login.
	down   bool
	noSelf bool
	tun    bool
	health []string
	// suggest is the exit node the daemon would pick; noSuggest makes the
	// question itself fail, as before the first relay measurement.
	suggest   string
	noSuggest bool
	// advertised is what the daemon's prefs offer to route, approved what the
	// tailnet let this node answer for; keyExpiry when its key runs out.
	advertised []netip.Prefix
	approved   []netip.Prefix
	keyExpiry  time.Time
	whois      int
	status     int
}

func newRemoteFakeDaemon() *remoteFakeDaemon {
	d := &remoteFakeDaemon{self: rtSelf, nodes: map[netip.Addr]*rtNode{}, proxy: map[netip.AddrPort]netip.Addr{}}
	for _, n := range rtAllNodes() {
		if n.key.IsZero() {
			n.key = key.NewNode().Public()
		}
		// Each test owns its copy: some of them change a node.
		cp := *n
		d.nodes[n.addr()] = &cp
	}
	return d
}

// change alters this daemon's copy of a node: what control would push.
func (d *remoteFakeDaemon) change(n *rtNode, f func(n *rtNode)) {
	d.mu.Lock()
	defer d.mu.Unlock()
	f(d.nodes[n.addr()])
}

// set alters the daemon itself.
func (d *remoteFakeDaemon) set(f func(d *remoteFakeDaemon)) {
	d.mu.Lock()
	defer d.mu.Unlock()
	f(d)
}

func (d *remoteFakeDaemon) asked() (whois, status int) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.whois, d.status
}

func (d *remoteFakeDaemon) register(local net.Addr, tsIP netip.Addr) netip.AddrPort {
	ap := local.(*net.TCPAddr).AddrPort()
	ap = netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
	d.mu.Lock()
	d.proxy[ap] = tsIP
	d.mu.Unlock()
	return ap
}

func (d *remoteFakeDaemon) unregister(ap netip.AddrPort) {
	d.mu.Lock()
	delete(d.proxy, ap)
	d.mu.Unlock()
}

func (d *remoteFakeDaemon) get(path string) ([]byte, error) {
	u, err := url.Parse(path)
	if err != nil {
		return nil, err
	}
	if u.Path == "/localapi/v0/whois" {
		// The daemon registers a forwarded connection a moment after it has
		// dialed it, and its whois waits for that (proxymap.WhoIsIPPort tries
		// for a fifth of a second). So does this one, more briefly.
		for try := 0; ; try++ {
			d.mu.Lock()
			if d.down {
				d.mu.Unlock()
				return nil, ErrDaemonNotRunning
			}
			if try == 0 {
				d.whois++
			}
			out, err := d.whoisLocked(u.Query())
			d.mu.Unlock()
			if err == nil || err != errRtNoMatch || try == 5 {
				return out, err
			}
			time.Sleep(2 * time.Millisecond)
		}
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.down {
		return nil, ErrDaemonNotRunning
	}
	if u.Path == "/localapi/v0/status" {
		d.status++
		return d.statusLocked(u.Query().Get("peers") != "false")
	}
	if u.Path == "/localapi/v0/suggest-exit-node" {
		if d.noSuggest {
			return nil, errors.New("HTTP 500: no preferred DERP, try again later")
		}
		// With nothing to recommend the daemon answers 200 and an empty id.
		return json.Marshal(apitype.ExitNodeSuggestionResponse{ID: tailcfg.StableNodeID(d.suggest), Name: "suggested"})
	}
	if u.Path == "/localapi/v0/prefs" {
		// ipn.Prefs, as far as it is read: the routes, and a key that is in
		// the real document and must never be passed on.
		return json.Marshal(struct {
			AdvertiseRoutes []netip.Prefix
			ControlURL      string
		}{d.advertised, "https://controlplane.example"})
	}
	return nil, fmt.Errorf("HTTP 404: no such LocalAPI path %s", u.Path)
}

var errRtNoMatch = errors.New("HTTP 404: no match for IP:port")

// whoisLocked follows ipnlocal.WhoIs: an address that is a node's own resolves
// to that node, the self node included; anything else goes through the proxy
// map, and only with a port.
func (d *remoteFakeDaemon) whoisLocked(q url.Values) ([]byte, error) {
	notFound := errRtNoMatch
	ap, err := netip.ParseAddrPort(q.Get("addr"))
	if err != nil {
		return nil, errors.New("HTTP 400: invalid 'addr' parameter")
	}
	n := d.nodes[ap.Addr()]
	if n == nil {
		if q.Get("proto") != "tcp" || ap.Port() == 0 {
			return nil, notFound
		}
		ip, ok := d.proxy[ap]
		if !ok {
			return nil, notFound
		}
		if n = d.nodes[ip]; n == nil {
			return nil, notFound
		}
	}
	node := &tailcfg.Node{
		ID:                  tailcfg.NodeID(len(n.id) + int(n.addr().As4()[3])),
		StableID:            tailcfg.StableNodeID(n.id),
		Name:                n.name + ".tailnet.ts.net.",
		User:                n.user,
		Sharer:              n.sharer,
		Key:                 n.key,
		Addresses:           []netip.Prefix{netip.PrefixFrom(n.addr(), 32)},
		Tags:                n.tags,
		Expired:             n.expired,
		UnsignedPeerAPIOnly: n.unsigned,
		ComputedName:        n.name,
		Hostinfo:            (&tailcfg.Hostinfo{Hostname: n.name, OS: "linux", ShareeNode: n.sharee}).View(),
	}
	return json.Marshal(apitype.WhoIsResponse{
		Node:        node,
		UserProfile: &tailcfg.UserProfile{ID: n.user, LoginName: fmt.Sprintf("user%d@example.com", n.user)},
	})
}

func (d *remoteFakeDaemon) statusLocked(peers bool) ([]byte, error) {
	st := ipnstate.Status{
		Version:      "1.104.1-test",
		TUN:          d.tun,
		BackendState: "Running",
		Health:       d.health,
	}
	if !d.noSelf {
		st.Self = d.peerStatus(d.nodes[d.self.addr()])
		st.TailscaleIPs = []netip.Addr{d.self.addr()}
		allowed := views.SliceOf(append([]netip.Prefix{netip.PrefixFrom(d.self.addr(), 32)}, d.approved...))
		st.Self.AllowedIPs = &allowed
		if !d.keyExpiry.IsZero() {
			st.Self.KeyExpiry = &d.keyExpiry
		}
	}
	for _, n := range d.nodes {
		if n.addr() == d.self.addr() {
			continue
		}
		if n.exitNode {
			st.ExitNodeStatus = &ipnstate.ExitNodeStatus{ID: tailcfg.StableNodeID(n.id), Online: !n.offline}
		}
		if peers {
			if st.Peer == nil {
				st.Peer = map[key.NodePublic]*ipnstate.PeerStatus{}
			}
			st.Peer[n.key] = d.peerStatus(n)
		}
	}
	return json.Marshal(st)
}

func (d *remoteFakeDaemon) peerStatus(n *rtNode) *ipnstate.PeerStatus {
	ps := &ipnstate.PeerStatus{
		ID:             tailcfg.StableNodeID(n.id),
		PublicKey:      n.key,
		HostName:       n.name,
		DNSName:        n.name + ".tailnet.ts.net.",
		OS:             "linux",
		UserID:         n.user,
		TailscaleIPs:   []netip.Addr{n.addr()},
		Online:         !n.offline,
		ExitNode:       n.exitNode,
		ExitNodeOption: n.exitOption,
		ShareeNode:     n.sharee,
	}
	if len(n.tags) > 0 {
		tags := views.SliceOf(n.tags)
		ps.Tags = &tags
	}
	if n.country != "" {
		ps.Location = &tailcfg.Location{Country: n.country, City: "Stockholm"}
	}
	return ps
}

// remoteFakeApp is the Kotlin side: it knows the settings a real app would
// know, refuses the rest, and notes every call.
type remoteFakeApp struct {
	mu       sync.Mutex
	settings map[string]string
	calls    []string
	fail     error
	block    chan struct{}
	// report is what the app says of itself, as JSON; askResult is what it
	// answers to a request for a permission's page, and asked the request as
	// it arrived; identified the same for "say whose hands you are in".
	report     string
	askResult  string
	asked      string
	identified string
	// answers are what it gives to the other requests, by name; refusals what
	// it refuses with — a request by its name, or one call as it is noted
	// ("set exit_node=nEXITA"). notices are the changes it was told to show.
	answers  map[string]string
	refusals map[string]string
	notices  []string
	// blockOnly narrows a hang to the calls that start with it.
	blockOnly string
}

// rtReport is a television's report: no access to storage, so its own folder
// cannot be written; one file the folder took earlier; one shared folder.
const rtReport = `{
 "permissions":[
  {"id":"install","state":"missing","ask":true},
  {"id":"storage","state":"missing","ask":true},
  {"id":"notifications","state":"granted","ask":true},
  {"id":"battery","state":"na","ask":true},
  {"id":"vpn","state":"missing","ask":true},
  {"id":"alarms","state":"granted","ask":false},
  {"id":"autostart","state":"unknown","ask":false},
  {"id":"camera","state":"granted","ask":true},
  {"id":"storage","state":"granted","ask":false}],
 "receive":{"state":"needs_access","folder":"Movies/From phone","reset":true,"free":51234567890,"waiting":2},
 "saved":[{"name":"holiday.mp4","size":812647424,"time":1791727380,"folder":"Download/Taildrop"}],
 "folders":[{"name":"Downloads","path":"/storage/emulated/0/Download"}],
 "folderCount":7,
 "device":{"model":"Xiaomi MiTV-MOOQ1","android":"11","sdk":30,"build":"0e629420","mode":"tun","engine":"hev","keyExpiry":5,"serial":"R58M"},
 "logTail":"up --auth-key=tskey-auth-abc"}`

func newRemoteFakeApp() *remoteFakeApp {
	return &remoteFakeApp{
		settings: map[string]string{
			"accept_dns":              "true",
			"accept_routes":           "false",
			"exit_node_lan_access":    "false",
			"tun_mode":                "false",
			"auto_reconnect":          "false",
			"keep_awake":              "false",
			"taildrive_enabled":       "true",
			"taildrive_proxy_enabled": "true",
			"advertise_exit_node":     "false",
			"advertise_routes":        "",
			"full_control":            "false",
			"device_class":            "tv",
			"app_version":             "4.11.0",
			RemoteKeyExitNode:         "",
		},
		report:     rtReport,
		askResult:  "shown",
		identified: "shown",
		answers:    map[string]string{},
		refusals:   map[string]string{},
	}
}

func (a *remoteFakeApp) note(call string) error {
	a.mu.Lock()
	a.calls = append(a.calls, call)
	block := a.block
	if a.blockOnly != "" && !strings.HasPrefix(call, a.blockOnly) {
		block = nil
	}
	a.mu.Unlock()
	if block != nil {
		<-block
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if why, ok := a.refusals[call]; ok {
		return errors.New(why)
	}
	return a.fail
}

func (a *remoteFakeApp) GetSetting(k string) (string, error) {
	if err := a.note("get " + k); err != nil {
		return "", err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	v, ok := a.settings[k]
	if !ok {
		return "", errors.New("unknown setting " + k)
	}
	return v, nil
}

func (a *remoteFakeApp) SetSetting(k, v string) error {
	if err := a.note("set " + k + "=" + v); err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, ok := a.settings[k]; !ok {
		return errors.New("unknown setting " + k)
	}
	a.settings[k] = v
	return nil
}

func (a *remoteFakeApp) PerformAction(name string) error {
	if err := a.note("action " + name); err != nil {
		return err
	}
	if name != "reconnect" && name != "receive_folder_default" && name != "save_received" {
		return errors.New("unknown action " + name)
	}
	return nil
}

// rtReads are the requests that change nothing: noted as "get", so that
// writes() stays what was changed.
var rtReads = map[string]bool{
	remoteRequestReport: true, remoteRequestStorageList: true, remoteRequestStorageFile: true, remoteRequestDiagReport: true,
}

func (a *remoteFakeApp) Request(name, args string) (string, error) {
	if name == remoteRequestNotice {
		// Comes on a thread of its own, whenever; kept apart from the calls a
		// test counts, and never held up.
		a.mu.Lock()
		a.notices = append(a.notices, args)
		a.mu.Unlock()
		return "", nil
	}
	call := "request " + name
	if rtReads[name] {
		call = "get " + name
	}
	if args != "" && name != remoteRequestAsk && name != remoteRequestIdentify {
		call += " " + args
	}
	if err := a.note(call); err != nil {
		return "", err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if why, ok := a.refusals[name]; ok {
		return "", errors.New(why)
	}
	switch name {
	case remoteRequestReport:
		return a.report, nil
	case remoteRequestAsk:
		a.asked = args
		return a.askResult, nil
	case remoteRequestIdentify:
		a.asked = args
		return a.identified, nil
	}
	v, ok := a.answers[name]
	if !ok {
		return "", errors.New("unknown request " + name)
	}
	return v, nil
}

// noticed waits for the app to have been told of n changes and returns them.
func (a *remoteFakeApp) noticed(t *testing.T, n int) []string {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		a.mu.Lock()
		got := append([]string(nil), a.notices...)
		a.mu.Unlock()
		if len(got) >= n || time.Now().After(deadline) {
			return got
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// set changes what the fake app would answer.
func (a *remoteFakeApp) set(f func(a *remoteFakeApp)) {
	a.mu.Lock()
	defer a.mu.Unlock()
	f(a)
}

func (a *remoteFakeApp) called() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]string(nil), a.calls...)
}

// writes are the calls that changed something.
func (a *remoteFakeApp) writes() []string {
	var out []string
	for _, c := range a.called() {
		if !strings.HasPrefix(c, "get ") {
			out = append(out, c)
		}
	}
	return out
}

// remoteRig is a managed device under test.
type remoteRig struct {
	t     *testing.T
	m     *remoteManager
	d     *remoteFakeDaemon
	clock *remoteFakeClock
	app   *remoteFakeApp
	store string
	addr  string
}

func newRemoteRig(t *testing.T, tune ...func(*remotePolicy)) *remoteRig {
	t.Helper()
	policy := remoteDefaultPolicy()
	for _, f := range tune {
		f(&policy)
	}
	r := &remoteRig{
		t:     t,
		d:     newRemoteFakeDaemon(),
		clock: newRemoteFakeClock(),
		app:   newRemoteFakeApp(),
		store: filepath.Join(t.TempDir(), "remote", "1760000000000.json"),
	}
	r.m = newRemoteManager(policy, r.clock, r.d)
	// The rates have a test of their own; everywhere else they would only
	// make the outcome depend on how fast the machine is.
	r.m.global = newRemoteLimiter(1e6, 1e6)
	r.m.perNode = newRemoteLimiter(1e6, 1e6)
	r.m.pairs = newRemoteLimiter(1e6, 1e6)
	r.m.asks = newRemoteLimiter(1e6, 1e6)
	r.m.taildropDir = func() string { return "" }
	// Nothing of a test reaches the real daemon, its log or a real transfer.
	r.m.sendFile = func(string, string) string { return "Error: no transfer in this test" }
	r.m.logText = func() string { return "" }
	r.m.relays = func() string { return "" }
	// As SetRemoteDelegate does, but here and now rather than on a thread of
	// its own, so that a test knows which calls the app has seen.
	r.m.setDelegate(r.app)
	r.m.loadFacts()
	r.start()
	t.Cleanup(func() { r.m.configure(false, "") })
	return r
}

// start switches the endpoint on and gives it a listener on a free port.
func (r *remoteRig) start() {
	r.t.Helper()
	if !r.m.configure(true, r.store) {
		r.t.Fatal("the endpoint did not ask for a listener")
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		r.t.Fatal(err)
	}
	r.addr = ln.Addr().String()
	if !r.m.serve(ln) {
		r.t.Fatal("the endpoint would not serve")
	}
}

// restart is the app process starting again: a new manager on the same file,
// daemon and clock.
func (r *remoteRig) restart() {
	r.t.Helper()
	r.m.configure(false, "")
	policy := r.m.policy
	m := newRemoteManager(policy, r.clock, r.d)
	m.global, m.perNode, m.pairs, m.asks = r.m.global, r.m.perNode, r.m.pairs, r.m.asks
	m.taildropDir = r.m.taildropDir
	m.delegateWait, m.delegateAnswer = r.m.delegateWait, r.m.delegateAnswer
	m.reportWait, m.reportAnswer = r.m.reportWait, r.m.reportAnswer
	m.sendFile, m.logText, m.relays = r.m.sendFile, r.m.logText, r.m.relays
	// The two switches are files of the app's: they are there after a restart.
	r.m.mu.Lock()
	full, noGuests := r.m.full, r.m.noGuests
	r.m.mu.Unlock()
	m.setAccess(full, noGuests)
	m.setDelegate(r.app)
	m.loadFacts()
	r.m = m
	r.start()
}

// rtConn tells the fake daemon when a forwarded connection ends.
type rtConn struct {
	net.Conn
	once sync.Once
	done func()
}

func (c *rtConn) Close() error {
	c.once.Do(c.done)
	return c.Conn.Close()
}

// dialAs opens a connection to the endpoint the way the daemon does for a
// peer: from a loopback port that the proxy map attributes to that peer. With
// a nil node nothing is registered — a process on the device itself.
func (r *remoteRig) dialAs(n *rtNode) (net.Conn, error) {
	conn, err := net.Dial("tcp4", r.addr)
	if err != nil {
		return nil, err
	}
	if n == nil {
		return conn, nil
	}
	ap := r.d.register(conn.LocalAddr(), n.addr())
	return &rtConn{Conn: conn, done: func() { r.d.unregister(ap) }}, nil
}

// as is an HTTP client that reaches the endpoint as node n.
func (r *remoteRig) as(n *rtNode) *http.Client {
	return &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(context.Context, string, string) (net.Conn, error) {
				return r.dialAs(n)
			},
		},
	}
}

// rtReply is a decoded answer.
type rtReply struct {
	status int
	raw    string
	obj    map[string]any
}

func (a rtReply) str(k string) string {
	s, _ := a.obj[k].(string)
	return s
}

func (a rtReply) num(k string) float64 {
	f, ok := a.obj[k].(float64)
	if !ok {
		return -999
	}
	return f
}

// call sends one request as node n. body may be nil, a string (sent as is) or
// anything JSON can encode.
func (r *remoteRig) call(n *rtNode, method, path, token string, body any) rtReply {
	r.t.Helper()
	return r.send(n, method, path, token, body, nil)
}

func (r *remoteRig) send(n *rtNode, method, path, token string, body any, tune func(*http.Request)) rtReply {
	r.t.Helper()
	out, err := r.try(n, method, path, token, body, tune)
	// Every request here is a connection of its own, and a node has a few at
	// a time: on a busy machine the one before may not be given back yet when
	// the next arrives, and that one is closed unanswered. It is asked again;
	// a test that expects to be shut out uses shut, which does not.
	for again := 0; err != nil && again < 5; again++ {
		time.Sleep(20 * time.Millisecond)
		out, err = r.try(n, method, path, token, body, tune)
	}
	if err != nil {
		r.t.Fatalf("%s %s: %v", method, path, err)
	}
	return out
}

// shut reports that a request as node n got no answer at all: the connection
// was closed on it, which is what a caller the daemon does not vouch for gets.
func (r *remoteRig) shut(n *rtNode, method, path, token string, body any) bool {
	r.t.Helper()
	a, err := r.try(n, method, path, token, body, nil)
	if err == nil {
		r.t.Logf("%s %s was answered: %d %s", method, path, a.status, strings.TrimSpace(a.raw))
	}
	return err != nil
}

func (r *remoteRig) try(n *rtNode, method, path, token string, body any, tune func(*http.Request)) (rtReply, error) {
	r.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			r.t.Fatal(err)
		}
		rd = bytes.NewReader(raw)
	}
	// The phone dials the managed node's tailnet address; the test's dialer
	// takes it from there.
	req, err := http.NewRequest(method, "http://"+rtSelf.ip+":28115"+path, rd)
	if err != nil {
		r.t.Fatal(err)
	}
	if rd != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	// As this build's own client sends it; a test of an older manager takes it off.
	req.Header.Set(remoteClientHeader, remoteClientVersion)
	if tune != nil {
		tune(req)
	}
	resp, err := r.as(n).Do(req)
	if err != nil {
		return rtReply{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	out := rtReply{status: resp.StatusCode, raw: string(raw), obj: map[string]any{}}
	if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
		r.t.Errorf("%s %s: Content-Type %q", method, path, ct)
	}
	if err := json.Unmarshal(raw, &out.obj); err != nil {
		r.t.Errorf("%s %s: the answer is not a JSON object: %q", method, path, raw)
	}
	return out, nil
}

// code puts a pairing code on show and returns it.
func (r *remoteRig) code() string {
	r.t.Helper()
	v := r.m.startPairing()
	if !v.OK || !remoteIsCode(v.Code) {
		r.t.Fatalf("no pairing code: %+v", v)
	}
	return v.Code
}

// wrongCode is a well-formed code that is not the one on show.
func wrongCode(code string) string {
	if code[5] == '9' {
		return code[:5] + "0"
	}
	return code[:5] + string(code[5]+1)
}

// pair pairs node n with a fresh code and returns its token.
func (r *remoteRig) pair(n *rtNode) string {
	r.t.Helper()
	a := r.call(n, "POST", "/v1/pair", "", map[string]string{"code": r.code()})
	if a.status != 200 || a.str("token") == "" {
		r.t.Fatalf("pairing %s: %d %s", n.name, a.status, a.raw)
	}
	return a.str("token")
}

func (r *remoteRig) want(a rtReply, status int, errWord string) {
	r.t.Helper()
	if a.status != status || a.str("error") != errWord {
		r.t.Errorf("got %d %s, want %d %q", a.status, strings.TrimSpace(a.raw), status, errWord)
	}
}

// ops are the last operations in the action log, newest first.
func (r *remoteRig) ops() []string {
	var out []string
	for _, e := range r.m.actions() {
		out = append(out, e.Op)
	}
	return out
}

func (r *remoteRig) hasOp(op string) bool {
	for _, o := range r.ops() {
		if o == op {
			return true
		}
	}
	return false
}

// Who a caller is comes from whois alone, and decides what it may become.
func TestRemoteWhoisClasses(t *testing.T) {
	for _, tc := range []struct {
		name  string
		node  *rtNode
		class string // what the pairing is; "" when the node is nobody at all
	}{
		{"same user", rtPhone, "owner"},
		{"another user", rtFriend, "guest"},
		{"tagged, created by the same user", rtTagged, "guest"},
		{"shared in from another tailnet", rtSharedIn, "guest"},
		{"sharee of this node", rtSharee, "guest"},
		{"expired key", rtExpired, ""},
		{"not signed by tailnet lock", rtUnsigned, ""},
		{"this node itself, through its own proxy", rtSelf, ""},
		{"a process on the device", nil, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := newRemoteRig(t)
			if tc.class == "" {
				// Nobody: the connection is closed without a word, whatever
				// it asks, with the right code or the wrong one.
				code := r.code()
				for _, try := range []string{code, wrongCode(code)} {
					if !r.shut(tc.node, "GET", "/v1/hello", "", nil) || !r.shut(tc.node, "POST", "/v1/pair", "", map[string]string{"code": try}) {
						t.Error("a caller that is nobody was answered")
					}
				}
				if len(r.m.paired()) != 0 {
					t.Error("a pairing was made")
				}
				if left := r.m.state().Pairing.AttemptsLeft; left != r.m.policy.CodeAttempts {
					t.Errorf("a caller that is nobody spent a guess: %d left", left)
				}
				return
			}
			hello := r.call(tc.node, "GET", "/v1/hello", "", nil)
			pair := r.call(tc.node, "POST", "/v1/pair", "", map[string]string{"code": r.code()})
			if hello.status != 200 || hello.str("app") != "tailsocks" {
				t.Errorf("hello: %d %s", hello.status, hello.raw)
			}
			if pair.status != 200 || pair.str("class") != tc.class {
				t.Fatalf("pair: %d %s", pair.status, pair.raw)
			}
			// An owner's pairing does not expire; a guest's lasts GuestTTL.
			wantLeft := float64(-1)
			if tc.class == "guest" {
				wantLeft = (24 * time.Hour).Seconds()
			}
			if pair.num("left") != wantLeft {
				t.Errorf("left = %v, want %v", pair.num("left"), wantLeft)
			}
			st := r.call(tc.node, "GET", "/v1/status", pair.str("token"), nil)
			if st.status != 200 {
				t.Errorf("status with the token: %d %s", st.status, st.raw)
			}
			list := r.m.paired()
			if len(list) != 1 || list[0].NodeID != tc.node.id || list[0].Class != tc.class || list[0].Name != tc.node.name {
				t.Errorf("paired list: %+v", list)
			}
		})
	}
}

// With no daemon to ask, nobody is anybody — paired or not.
func TestRemoteWhoisFailure(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	r.d.set(func(d *remoteFakeDaemon) { d.down = true })
	r.forgetSelf()
	for _, path := range []string{"/v1/hello", "/v1/status", "/v1/nonsense"} {
		if !r.shut(rtPhone, "GET", path, token, nil) {
			t.Errorf("%s was answered with no daemon to say who asked", path)
		}
	}

	// The daemon answers again but this node has no identity yet (logged
	// out): there is nothing to compare a caller with.
	r.d.set(func(d *remoteFakeDaemon) { d.down, d.noSelf = false, true })
	if !r.shut(rtPhone, "GET", "/v1/status", token, nil) {
		t.Error("answered without an identity of this node's own")
	}

	r.d.set(func(d *remoteFakeDaemon) { d.noSelf = false })
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("after the daemon came back: %d %s", a.status, a.raw)
	}

	// A connection that was let in is asked about again with every request:
	// the daemon going away, or the node's key expiring, ends it there.
	keep := &http.Client{Transport: &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) { return r.dialAs(rtPhone) },
	}}
	defer keep.CloseIdleConnections()
	get := func() (int, error) {
		req, _ := http.NewRequest("GET", "http://"+rtSelf.ip+":28115/v1/status", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := keep.Do(req)
		if err != nil {
			return 0, err
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return resp.StatusCode, nil
	}
	if status, err := get(); status != 200 {
		t.Fatalf("on a kept connection: %d %v", status, err)
	}
	r.d.change(rtPhone, func(n *rtNode) { n.expired = true })
	if status, err := get(); status != 403 {
		t.Errorf("the same connection after the key expired: %d %v", status, err)
	}
}

func TestRemoteClassOf(t *testing.T) {
	self := remoteSelf{NodeID: "nSELF", UserID: 7}
	open, closed := remoteDefaultPolicy(), remoteDefaultPolicy()
	closed.AllowGuests = false
	for _, tc := range []struct {
		name   string
		caller remoteCaller
		self   remoteSelf
		want   remoteClass
		strict remoteClass // with AllowGuests off
	}{
		{"same user", remoteCaller{NodeID: "a", UserID: 7}, self, remoteOwner, remoteOwner},
		{"other user", remoteCaller{NodeID: "a", UserID: 8}, self, remoteGuest, remoteNobody},
		{"tagged", remoteCaller{NodeID: "a", UserID: 7, Tagged: true}, self, remoteGuest, remoteNobody},
		{"shared in", remoteCaller{NodeID: "a", UserID: 7, Shared: true}, self, remoteGuest, remoteNobody},
		{"no user at all", remoteCaller{NodeID: "a"}, remoteSelf{NodeID: "nSELF"}, remoteGuest, remoteNobody},
		{"this node is tagged", remoteCaller{NodeID: "a", UserID: 7}, remoteSelf{NodeID: "nSELF", UserID: 7, Tagged: true}, remoteGuest, remoteNobody},
		{"this node", remoteCaller{NodeID: "nSELF", UserID: 7}, self, remoteNobody, remoteNobody},
		{"no id", remoteCaller{UserID: 7}, self, remoteNobody, remoteNobody},
		{"no self", remoteCaller{NodeID: "a", UserID: 7}, remoteSelf{}, remoteNobody, remoteNobody},
	} {
		if got := open.classOf(tc.caller, tc.self); got != tc.want {
			t.Errorf("%s: %v, want %v", tc.name, got, tc.want)
		}
		if got := closed.classOf(tc.caller, tc.self); got != tc.strict {
			t.Errorf("%s, guests off: %v, want %v", tc.name, got, tc.strict)
		}
	}
}

// The address whois is asked about must be the connection's loopback source;
// a tailnet address there would be answered by address alone.
func TestRemoteWhoisOnlyLoopback(t *testing.T) {
	r := newRemoteRig(t)
	for _, addr := range []string{rtPhone.ip + ":4242", "192.168.1.5:4242", "[fd7a:115c:a1e0::2]:4242", "127.0.0.1", "", "nonsense"} {
		if c, err := r.m.whois(addr); err == nil {
			t.Errorf("%q resolved to %+v", addr, c)
		}
	}
	if asked, _ := r.d.asked(); asked != 0 {
		t.Errorf("the daemon was asked %d times", asked)
	}
}

// What a request must look like before anyone looks at what it asks.
func TestRemoteRequestShape(t *testing.T) {
	r := newRemoteRig(t)
	token := r.pair(rtPhone)
	set := map[string]string{"key": "accept_dns", "value": "false"}

	// A browser on the paired phone is that node too.
	a := r.send(rtPhone, "POST", "/v1/settings", token, set, func(q *http.Request) { q.Header.Set("Origin", "https://evil.example") })
	r.want(a, 403, "forbidden")
	a = r.send(rtPhone, "GET", "/v1/status", token, nil, func(q *http.Request) { q.Host = "rebound.evil.example:28115" })
	r.want(a, 403, "forbidden")
	a = r.send(rtPhone, "POST", "/v1/settings", token, set, func(q *http.Request) { q.Header.Set("Content-Type", "text/plain") })
	r.want(a, 415, "bad_request")

	r.want(r.call(rtPhone, "GET", "/v1/nonsense", token, nil), 404, "not_found")
	r.want(r.call(rtPhone, "GET", "/v2/status", token, nil), 404, "not_found")
	r.want(r.call(rtPhone, "DELETE", "/v1/status", token, nil), 405, "method_not_allowed")
	r.want(r.call(rtPhone, "GET", "/v1/pair", token, nil), 405, "method_not_allowed")
	// Someone who is nobody learns nothing about paths.
	if !r.shut(nil, "GET", "/v1/nonsense", "", nil) || !r.shut(nil, "GET", "/v1/status", token, nil) {
		t.Error("a process on the device was answered")
	}

	big := `{"key":"accept_dns","value":"` + strings.Repeat("x", remoteMaxBody) + `"}`
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, big), 413, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, `{"key":"accept_dns","value":"false","extra":1}`), 400, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, `{"key":"accept_dns","value":"false"} {}`), 400, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, `not json`), 400, "bad_request")
	r.want(r.call(rtPhone, "POST", "/v1/settings", token, `["accept_dns"]`), 400, "bad_request")

	if w := r.app.writes(); len(w) != 0 {
		t.Errorf("the app was asked to change something: %q", w)
	}
}

// Every route but hello and pair needs the token, and says the same thing to
// a node with none, a wrong one, or another node's.
func TestRemoteTokenRequired(t *testing.T) {
	r := newRemoteRig(t)
	phone := r.pair(rtPhone)
	tablet := r.pair(rtTablet)
	for _, rt := range remoteRoutes {
		if rt.op == "" {
			continue
		}
		var body any
		if rt.method == "POST" {
			body = map[string]string{}
		}
		for name, token := range map[string]string{
			"no token":            "",
			"a wrong token":       strings.Repeat("A", 43),
			"another node's":      tablet,
			"the hash of its own": remoteHashToken(phone),
		} {
			a := r.call(rtPhone, rt.method, rt.path, token, body)
			if a.status != 401 || a.str("error") != "unauthorized" {
				t.Errorf("%s %s with %s: %d %s", rt.method, rt.path, name, a.status, a.raw)
			}
		}
		// And a node that never paired, with a real token of someone else.
		r.want(r.call(rtFriend, rt.method, rt.path, phone, body), 401, "unauthorized")
	}
	if w := r.app.writes(); len(w) != 0 {
		t.Errorf("the app was asked to change something: %q", w)
	}
	if len(r.m.paired()) != 2 {
		t.Errorf("pairings: %+v", r.m.paired())
	}
	if !r.hasOp("denied") {
		t.Errorf("a wrong token on a paired node left no trace: %q", r.ops())
	}
	// The real tokens still work.
	if a := r.call(rtPhone, "GET", "/v1/status", phone, nil); a.status != 200 {
		t.Errorf("phone: %d %s", a.status, a.raw)
	}
	if a := r.call(rtTablet, "GET", "/v1/status", tablet, nil); a.status != 200 {
		t.Errorf("tablet: %d %s", a.status, a.raw)
	}
}

func TestRemoteRates(t *testing.T) {
	l := newRemoteLimiter(1, 3)
	for i := 0; i < 3; i++ {
		if !l.allow("a") {
			t.Fatalf("request %d refused inside the burst", i)
		}
	}
	if l.allow("a") {
		t.Error("the burst did not end")
	}
	if !l.allow("b") {
		t.Error("another key paid for it")
	}
	l.buckets["a"].at = time.Now().Add(-2 * time.Second)
	if !l.allow("a") || !l.allow("a") || l.allow("a") {
		t.Error("two seconds did not buy exactly two requests")
	}
	for i := 0; i < 4*remoteLimiterKeys; i++ {
		l.allow(fmt.Sprint("key", i))
	}
	if n := len(l.buckets); n > remoteLimiterKeys {
		t.Errorf("%d buckets kept", n)
	}

	// On the wire: a node's own budget, and guesses at a code.
	r := newRemoteRig(t)
	r.m.perNode = newRemoteLimiter(0.001, 2)
	r.call(rtFriend, "GET", "/v1/hello", "", nil)
	r.call(rtFriend, "GET", "/v1/hello", "", nil)
	r.want(r.call(rtFriend, "GET", "/v1/hello", "", nil), 429, "slow_down")
	if a := r.call(rtPhone, "GET", "/v1/hello", "", nil); a.status != 200 {
		t.Errorf("another node was slowed down too: %d", a.status)
	}

	r = newRemoteRig(t)
	r.m.pairs = newRemoteLimiter(0.001, 1)
	code := r.code()
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": wrongCode(code)}), 403, "wrong")
	r.want(r.call(rtFriend, "POST", "/v1/pair", "", map[string]string{"code": code}), 429, "slow_down")
	if left := r.m.state().Pairing.AttemptsLeft; left != r.m.policy.CodeAttempts-1 {
		t.Errorf("a refused request was counted as a guess: %d left", left)
	}

	// And everyone's together, counted before the daemon is asked again who
	// sent the request.
	r = newRemoteRig(t)
	r.m.global = newRemoteLimiter(0.001, 1)
	r.call(rtPhone, "GET", "/v1/hello", "", nil)
	before, _ := r.d.asked()
	r.want(r.call(rtFriend, "GET", "/v1/hello", "", nil), 429, "slow_down")
	// One question at the gate, none for the request that was refused.
	if after, _ := r.d.asked(); after != before+1 {
		t.Errorf("the daemon was asked %d times for a refused request, want 1", after-before)
	}
}
