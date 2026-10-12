package appctr

// remote_client.go — the managing phone's side of remote management.
//
// A peer is always named by its StableNodeID. The address is looked up in this
// phone's own network map each time, so a token is only ever sent to the node
// the tailnet says holds that id now — not to whoever inherited an address.
//
// The connection goes through this phone's own daemon, by its SOCKS5 port,
// and nowhere else. There is deliberately no direct dial to fall back on:
// outside the tunnel a 100.64.0.0/10 address is the carrier's, and the request
// carries the token.

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

const (
	remoteProbeTimeout   = 4 * time.Second
	remoteRequestTimeout = 12 * time.Second
	// remoteMaxAnswer bounds what is read of a peer's answer; the largest
	// honest one is a long exit-node list.
	remoteMaxAnswer = 1 << 20
	// remotePeerTTL is how long a peer's address is reused before the network
	// map is asked again.
	remotePeerTTL = 15 * time.Second
)

// remoteClientEnv is what the phone side takes from the rest of the bridge;
// a test points it at a fake daemon and a SOCKS5 server of its own.
type remoteClientEnv struct {
	api   remoteLocalAPI
	socks func() (addr, user, pass string)
	port  uint16

	mu     sync.Mutex
	key    string
	client *http.Client
	peers  map[string]remotePeerAddr
}

type remotePeerAddr struct {
	addr netip.Addr
	at   time.Time
}

var remoteClient = &remoteClientEnv{
	api: remoteDaemonAPI{},
	socks: func() (string, string, string) {
		socks, user, pass, _ := GConfig.get()
		return socks, user, pass
	},
	port: RemotePort,
}

var (
	errRemoteNoPeer   = errors.New("no such device in this tailnet")
	errRemoteNoTunnel = errors.New("TailSocks is not connected")
	errRemoteBadPath  = errors.New("not a remote-management path")
)

// closeIdle drops the pooled connections; they run through the daemon's SOCKS5
// port and must not outlive it.
func (e *remoteClientEnv) closeIdle() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.client != nil {
		e.client.CloseIdleConnections()
		e.client, e.key = nil, ""
	}
	e.peers = nil
}

// httpClient returns the client that dials through this phone's daemon.
func (e *remoteClientEnv) httpClient() (*http.Client, error) {
	socks, user, pass := e.socks()
	if socks == "" || strings.EqualFold(socks, "none") {
		return nil, errRemoteNoTunnel
	}
	key := socks + "\x00" + user + "\x00" + pass
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.client != nil && e.key == key {
		return e.client, nil
	}
	if e.client != nil {
		e.client.CloseIdleConnections()
	}
	dial, err := socksDialContext(socks, user, pass)
	if err != nil {
		return nil, errRemoteNoTunnel
	}
	e.client = &http.Client{
		Transport: &http.Transport{
			// Only the daemon's proxy: no proxy from the environment, and no
			// dialer of the system's.
			Proxy:               nil,
			DialContext:         dial,
			MaxIdleConns:        4,
			MaxIdleConnsPerHost: 1,
			// Shorter than the other side's idle timeout, so a connection is
			// not reused in the moment it is being closed.
			IdleConnTimeout:    20 * time.Second,
			DisableCompression: true,
		},
		// The API never redirects; a redirect would carry the token along.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	e.key = key
	return e.client, nil
}

// peerAddr finds the tailnet address of the peer with this StableNodeID in
// the phone's own network map, IPv4 first.
func (e *remoteClientEnv) peerAddr(peerID string) (netip.Addr, error) {
	if !remoteIsNodeID(peerID) {
		return netip.Addr{}, errRemoteNoPeer
	}
	e.mu.Lock()
	if p, ok := e.peers[peerID]; ok && time.Since(p.at) < remotePeerTTL {
		e.mu.Unlock()
		return p.addr, nil
	}
	e.mu.Unlock()

	raw, err := e.api.get("/localapi/v0/status")
	if err != nil {
		return netip.Addr{}, errRemoteNoTunnel
	}
	var st struct {
		Peer map[string]*struct {
			ID           string
			TailscaleIPs []netip.Addr
		}
	}
	if err := json.Unmarshal(raw, &st); err != nil {
		return netip.Addr{}, errRemoteNoTunnel
	}
	var found netip.Addr
	for _, p := range st.Peer {
		if p == nil || p.ID != peerID {
			continue
		}
		for _, ip := range p.TailscaleIPs {
			if !found.IsValid() || (ip.Is4() && !found.Is4()) {
				found = ip
			}
		}
		break
	}
	if !found.IsValid() {
		return netip.Addr{}, errRemoteNoPeer
	}
	e.mu.Lock()
	if e.peers == nil || len(e.peers) > 256 {
		e.peers = map[string]remotePeerAddr{}
	}
	e.peers[peerID] = remotePeerAddr{addr: found, at: time.Now()}
	e.mu.Unlock()
	return found, nil
}

// remotePathOK reports whether path is one of the API's: under /v1/, and
// nothing that could turn the request into one for another host or resource.
func remotePathOK(path string) bool {
	if len(path) > 512 || !strings.HasPrefix(path, "/v1/") || strings.Contains(path, "..") {
		return false
	}
	for _, r := range path {
		if r <= ' ' || r == 0x7f || r == '\\' || r == '#' || r == '@' {
			return false
		}
	}
	return true
}

// remoteAnswer is what RemoteGet and RemotePost return.
type remoteAnswer struct {
	// Status is the HTTP status, 0 when no answer arrived; then Error says
	// why: "no_peer" (not in this tailnet), "not_connected" (this phone's
	// TailSocks is not running), "bad_request" (the path), or "unreachable"
	// with the reason in Message.
	Status  int    `json:"status"`
	Body    string `json:"body"`
	Error   string `json:"error,omitempty"`
	Message string `json:"message,omitempty"`
}

func remoteFailure(err error) remoteAnswer {
	switch {
	case errors.Is(err, errRemoteNoPeer):
		return remoteAnswer{Error: "no_peer", Message: err.Error()}
	case errors.Is(err, errRemoteNoTunnel):
		return remoteAnswer{Error: "not_connected", Message: err.Error()}
	case errors.Is(err, errRemoteBadPath):
		return remoteAnswer{Error: "bad_request", Message: err.Error()}
	}
	msg := err.Error()
	var ne interface{ Timeout() bool }
	if errors.As(err, &ne) && ne.Timeout() {
		msg = "no answer in time"
	}
	return remoteAnswer{Error: "unreachable", Message: remoteClean(msg, 200)}
}

// do sends one request to the peer's remote-management port.
func (e *remoteClientEnv) do(peerID, method, path, token, body string, timeout time.Duration) remoteAnswer {
	if !remotePathOK(path) {
		return remoteFailure(errRemoteBadPath)
	}
	addr, err := e.peerAddr(peerID)
	if err != nil {
		return remoteFailure(err)
	}
	client, err := e.httpClient()
	if err != nil {
		return remoteFailure(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://"+netip.AddrPortFrom(addr, e.port).String()+path, rd)
	if err != nil {
		return remoteFailure(errRemoteBadPath)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return remoteFailure(err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, remoteMaxAnswer))
	if err != nil {
		return remoteFailure(err)
	}
	return remoteAnswer{Status: resp.StatusCode, Body: string(raw)}
}

// remoteProbeResult is what RemoteProbe returns.
type remoteProbeResult struct {
	// OK: the peer answered as a TailSocks that offers remote management.
	// Everything below is meaningful only then.
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	API   int    `json:"api,omitempty"`
	// DeviceClass is what the peer says it is: "tv", "phone", "tablet" or "".
	DeviceClass string `json:"deviceClass"`
	Name        string `json:"name,omitempty"`
	// Pairing: the peer is showing a code right now. Paired: it holds a
	// pairing for this phone's node — which says nothing about whether this
	// app still has the token. Class: what this phone is, or would be, to it:
	// "owner", "guest", or "" when its policy would not let this phone pair.
	// NeedsCode: pairing takes the code from its screen.
	Pairing   bool   `json:"pairing"`
	Paired    bool   `json:"paired"`
	Class     string `json:"class"`
	NeedsCode bool   `json:"needsCode"`
}

// RemoteProbe asks a peer, by StableNodeID, whether it is a TailSocks that
// offers remote management: one small unauthenticated request to its
// management port through this phone's daemon, four seconds at most, nothing
// of this phone's sent but the question. Meant for the moment a peer's details
// open; ask only peers that are online, an offline one costs the whole wait.
//
// The answer is {"ok":true,"api","deviceClass","name","pairing","paired",
// "class","needsCode"} or {"ok":false,"error"}. A TV that can be managed is ok
// with deviceClass "tv". Nothing in the peer list says as much without asking:
// a peer's status carries no model and no app, and by default every TailSocks
// reports the same OS as a Linux server.
func RemoteProbe(peerID string) string { return remoteReply(remoteClient.probe(peerID)) }

func (e *remoteClientEnv) probe(peerID string) remoteProbeResult {
	a := e.do(peerID, http.MethodGet, "/v1/hello", "", "", remoteProbeTimeout)
	if a.Status != http.StatusOK {
		if a.Error == "" {
			a.Error = "not_offered"
		}
		return remoteProbeResult{Error: a.Error}
	}
	var h remoteHello
	if err := json.Unmarshal([]byte(a.Body), &h); err != nil || h.App != "tailsocks" || h.NodeID != peerID {
		// Something else answers on that port, or the device that answered is
		// not the one this tailnet knows under that id.
		return remoteProbeResult{Error: "not_offered"}
	}
	class := remoteClean(h.DeviceClass, 16)
	if !remoteDeviceClasses[class] {
		class = ""
	}
	return remoteProbeResult{
		OK: true, API: h.API, DeviceClass: class, Name: remoteClean(h.Name, 64),
		Pairing: h.Pairing, Paired: h.Paired, Class: remoteClassOf(h.Class).String(), NeedsCode: h.NeedsCode,
	}
}

// remotePairAnswer is what RemotePair returns.
type remotePairAnswer struct {
	OK bool `json:"ok"`
	// Error, when not ok: the peer's own word — closed, wrong, locked, used,
	// not_allowed, full, slow_down, forbidden — or no_peer, not_connected,
	// unreachable, bad_answer from this side. AttemptsLeft comes with wrong
	// and locked.
	Error        string `json:"error,omitempty"`
	AttemptsLeft *int   `json:"attemptsLeft,omitempty"`
	Status       int    `json:"status,omitempty"`
	// Token is the secret this app must keep, sealed, and present on every
	// request; it is never shown again. Class is "owner" or "guest"; Left the
	// pairing's lifetime in seconds from now, -1 for "until revoked".
	Token string `json:"token,omitempty"`
	Class string `json:"class,omitempty"`
	Left  int64  `json:"left,omitempty"`
	// Name is what the peer calls itself.
	Name string `json:"name,omitempty"`
}

// RemotePair pairs this phone with a peer, by StableNodeID, using the code
// from the peer's screen ("" where RemoteProbe said none is needed). On
// success the answer is {"ok":true,"token","class","left","name"}: store the
// token sealed, under the peer's StableNodeID. Otherwise {"ok":false,"error",
// "attemptsLeft","status"}. Pairing again replaces the phone's earlier pairing
// and its token.
func RemotePair(peerID, code string) string { return remoteReply(remoteClient.pair(peerID, code)) }

func (e *remoteClientEnv) pair(peerID, code string) remotePairAnswer {
	if code != "" && !remoteIsCode(code) {
		// Never sent: it could not be right, and it would cost a guess.
		return remotePairAnswer{Error: "bad_request"}
	}
	body, _ := json.Marshal(struct {
		Code string `json:"code"`
	}{code})
	a := e.do(peerID, http.MethodPost, "/v1/pair", "", string(body), remoteRequestTimeout)
	if a.Status == 0 {
		return remotePairAnswer{Error: a.Error}
	}
	if a.Status != http.StatusOK {
		var r remotePairRefusal
		if err := json.Unmarshal([]byte(a.Body), &r); err != nil || r.Error == "" {
			r.Error = "bad_answer"
		}
		return remotePairAnswer{Error: remoteClean(r.Error, 32), AttemptsLeft: r.AttemptsLeft, Status: a.Status}
	}
	var p remotePaired
	if err := json.Unmarshal([]byte(a.Body), &p); err != nil || p.Token == "" || p.NodeID != peerID || remoteClassOf(p.Class) == remoteNobody {
		return remotePairAnswer{Error: "bad_answer", Status: a.Status}
	}
	return remotePairAnswer{OK: true, Token: p.Token, Class: p.Class, Left: p.Left, Name: remoteClean(p.Name, 64)}
}

// RemoteGet sends an authenticated GET to a paired peer, by StableNodeID.
// path is one of the API's, with its query: "/v1/status", "/v1/exit-nodes",
// "/v1/settings", "/v1/files", "/v1/log?lines=100". The answer is
// {"status","body","error","message"}: status is the peer's HTTP status and
// body its JSON, or status 0 and an error when nothing came back. 401 means
// the pairing is gone — revoked, expired or replaced: forget the token.
func RemoteGet(peerID, token, path string) string {
	return remoteReply(remoteClient.do(peerID, http.MethodGet, path, token, "", remoteRequestTimeout))
}

// RemotePost sends an authenticated POST with a JSON body to a paired peer:
// "/v1/exit-node" {"id"}, "/v1/settings" {"key","value"}, "/v1/actions"
// {"action"}, "/v1/files/delete" {"name"}, "/v1/unpair" with "". The answer
// is as for RemoteGet. 409 "refused" carries the peer app's reason in the
// body's "message".
func RemotePost(peerID, token, path, body string) string {
	return remoteReply(remoteClient.do(peerID, http.MethodPost, path, token, body, remoteRequestTimeout))
}
