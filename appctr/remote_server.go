package appctr

// remote_server.go — the HTTP side of remote management: what is checked on
// every request, and the handful of things a paired device may ask for.
//
// Order of checks, the same for every path, so that a caller learns nothing
// from a path it may not use: the overall rate; the request's shape (no
// browser, an address for a host); who is calling, from the daemon; that
// caller's own rate; then the route, the token and the class; and only then
// the body.

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"log/slog"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var remoteDiscardLog = log.New(io.Discard, "", 0)

// remoteRequest is one request after the checks every route shares.
type remoteRequest struct {
	caller remoteCaller
	self   remoteSelf
	// class and left describe the caller's pairing; class is remoteNobody on
	// the two routes that need none.
	class remoteClass
	left  int64
	query map[string][]string
	body  []byte
}

// remoteRoute is one entry of the API. op names the right the caller needs
// (remotePolicy.Rights); "" is for the two routes open to any tailnet node
// whois can resolve.
type remoteRoute struct {
	method string
	path   string
	op     string
	handle func(m *remoteManager, rq *remoteRequest) (int, any)
}

// remoteRoutes is the whole API. There is no route that stops the daemon,
// signs out, touches an account, a key or a credential, or returns a file.
var remoteRoutes = []remoteRoute{
	{"GET", "/v1/hello", "", (*remoteManager).handleHello},
	{"POST", "/v1/pair", "", (*remoteManager).handlePair},
	{"POST", "/v1/unpair", remoteOpUnpair, (*remoteManager).handleUnpair},
	{"GET", "/v1/status", remoteOpStatus, (*remoteManager).handleStatus},
	{"GET", "/v1/exit-nodes", remoteOpExitNodes, (*remoteManager).handleExitNodes},
	{"POST", "/v1/exit-node", remoteOpSetExitNode, (*remoteManager).handleSetExitNode},
	{"GET", "/v1/settings", remoteOpSettings, (*remoteManager).handleSettings},
	{"POST", "/v1/settings", remoteOpSetSetting, (*remoteManager).handleSetSetting},
	{"POST", "/v1/actions", remoteOpAction, (*remoteManager).handleAction},
	{"GET", "/v1/files", remoteOpFiles, (*remoteManager).handleFiles},
	{"POST", "/v1/files/delete", remoteOpDeleteFile, (*remoteManager).handleDeleteFile},
	{"GET", "/v1/log", remoteOpLog, (*remoteManager).handleLog},
}

// remoteError is the body of every refusal: a fixed word a client can switch
// on, and sometimes a sentence for a person.
type remoteError struct {
	Error   string `json:"error"`
	Message string `json:"message,omitempty"`
}

func remoteWrite(w http.ResponseWriter, status int, v any) {
	h := w.Header()
	h.Set("Content-Type", "application/json")
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (m *remoteManager) handler() http.Handler {
	return http.HandlerFunc(m.serveHTTP)
}

func (m *remoteManager) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if !m.global.allow("") {
		w.Header().Set("Retry-After", "1")
		remoteWrite(w, http.StatusTooManyRequests, remoteError{Error: "slow_down"})
		return
	}
	// The client is the TailSocks app, which sends no Origin and dials an
	// address. A browser on a paired phone speaks as that node too; a page in
	// it must not be able to reach this API, by a form or by pointing its own
	// name at the device. (JSON bodies and a bearer header already put every
	// state-changing request beyond a cross-origin page; this closes the rest.)
	if r.Header.Get("Origin") != "" || !remoteHostIsAddress(r.Host) {
		remoteWrite(w, http.StatusForbidden, remoteError{Error: "forbidden"})
		return
	}
	caller, err := m.whois(r.RemoteAddr)
	if err != nil {
		remoteWrite(w, http.StatusForbidden, remoteError{Error: "forbidden"})
		return
	}
	self, err := m.selfIdentity()
	if err != nil || caller.NodeID == self.NodeID {
		// Without an identity of our own nobody can be compared with it; and
		// "this node" is a local process using the device's own proxy.
		remoteWrite(w, http.StatusForbidden, remoteError{Error: "forbidden"})
		return
	}
	if !m.perNode.allow(caller.NodeID) {
		w.Header().Set("Retry-After", "1")
		remoteWrite(w, http.StatusTooManyRequests, remoteError{Error: "slow_down"})
		return
	}

	var route *remoteRoute
	pathKnown := false
	for i := range remoteRoutes {
		if remoteRoutes[i].path != r.URL.Path {
			continue
		}
		pathKnown = true
		if remoteRoutes[i].method == r.Method {
			route = &remoteRoutes[i]
			break
		}
	}
	if route == nil {
		if pathKnown {
			remoteWrite(w, http.StatusMethodNotAllowed, remoteError{Error: "method_not_allowed"})
		} else {
			remoteWrite(w, http.StatusNotFound, remoteError{Error: "not_found"})
		}
		return
	}

	rq := &remoteRequest{caller: caller, self: self, left: -1, query: r.URL.Query()}
	if route.op != "" {
		class, left, ok := m.authorize(caller, self, remoteBearer(r))
		if !ok {
			remoteWrite(w, http.StatusUnauthorized, remoteError{Error: "unauthorized"})
			return
		}
		if !m.policy.allows(route.op, class) {
			m.noteDenied(caller, class, route.op+": not for a "+class.String())
			remoteWrite(w, http.StatusForbidden, remoteError{Error: "forbidden"})
			return
		}
		rq.class, rq.left = class, left
	}
	if r.Method == http.MethodPost {
		body, status := remoteReadBody(w, r)
		if status != 0 {
			remoteWrite(w, status, remoteError{Error: "bad_request"})
			return
		}
		rq.body = body
	}
	status, v := route.handle(m, rq)
	remoteWrite(w, status, v)
}

// remoteBearer returns the token of an "Authorization: Bearer" header, or "".
func remoteBearer(r *http.Request) string {
	scheme, token, ok := strings.Cut(r.Header.Get("Authorization"), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return ""
	}
	return strings.TrimSpace(token)
}

// remoteReadBody reads a JSON body of at most remoteMaxBody bytes; a non-zero
// status is the refusal. An empty body is allowed and is the empty object.
func remoteReadBody(w http.ResponseWriter, r *http.Request) ([]byte, int) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, remoteMaxBody))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return nil, http.StatusRequestEntityTooLarge
		}
		return nil, http.StatusBadRequest
	}
	if len(body) == 0 {
		return []byte("{}"), 0
	}
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		return nil, http.StatusUnsupportedMediaType
	}
	return body, 0
}

// remoteDecode parses a request body strictly: one JSON object, no fields the
// route does not know.
func remoteDecode(body []byte, into any) bool {
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(into); err != nil {
		return false
	}
	return !dec.More()
}

// remoteDummyHash stands in for a pairing that does not exist, so that "not
// paired" costs the same comparison as "wrong token".
var remoteDummyHash = remoteHashToken("no such pairing")

// authorize checks the caller's token against its pairing and returns the
// class it may act as and the seconds the pairing has left (-1: until revoked).
func (m *remoteManager) authorize(c remoteCaller, self remoteSelf, token string) (remoteClass, int64, bool) {
	now := m.policy.classOf(c, self)

	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.enabled || m.store == nil {
		return remoteNobody, 0, false
	}
	m.expireLocked()
	m.adoptSelfLocked(self)
	p := m.store.find(c.NodeID)
	hash := remoteDummyHash
	if p != nil {
		hash = p.TokenSHA256
	}
	if !remoteTokenMatches(token, hash) || p == nil {
		if p != nil && token != "" {
			// The node is paired and something on it presented a token that
			// is not the one issued: another app guessing, or the app after
			// the pairing was replaced.
			m.noteDeniedLocked(c, remoteClassOf(p.Class), "wrong token")
		}
		return remoteNobody, 0, false
	}
	class := remoteClassOf(p.Class)
	switch {
	case p.UserID != c.UserID:
		// The node changed hands since it paired.
		m.noteDeniedLocked(c, class, "the device belongs to another user now")
		return remoteNobody, 0, false
	case now < class:
		// It paired as an owner and is no longer one — it was tagged, or this
		// node was — or guests are no longer let in at all. Pairing again
		// settles what it is now.
		m.noteDeniedLocked(c, class, "the device no longer qualifies as "+class.String())
		return remoteNobody, 0, false
	}
	p.LastUsed = m.clock.Now().Unix()
	if m.store.stale() {
		m.saveLocked()
	}
	return class, m.store.left(p), true
}

// remoteDeniedEvery spaces the "denied" lines one node can put in the action
// log: the log is short and every line is a write to flash.
const remoteDeniedEvery = 10 * time.Minute

func (m *remoteManager) noteDenied(c remoteCaller, class remoteClass, why string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.noteDeniedLocked(c, class, why)
}

func (m *remoteManager) noteDeniedLocked(c remoteCaller, class remoteClass, why string) {
	if m.store == nil {
		return
	}
	now := m.clock.Now().Unix()
	a := m.store.data.Audit
	for i := len(a) - 1; i >= 0; i-- {
		if a[i].Op == "denied" && a[i].NodeID == c.NodeID {
			if d := now - a[i].Time; d >= 0 && d < int64(remoteDeniedEvery/time.Second) {
				return
			}
			break
		}
	}
	slog.Warn("Remote management: refused", "device", c.Name, "node", c.NodeID, "why", why)
	m.auditLocked(remoteEvent{NodeID: c.NodeID, Name: c.Name, Class: class.String(), Op: "denied", Detail: why})
	m.saveLocked()
}

// record writes one line of the action log and saves it.
func (m *remoteManager) record(rq *remoteRequest, op, detail string, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.auditLocked(remoteEvent{NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(), Op: op, Detail: detail, OK: ok})
	m.saveLocked()
	slog.Info("Remote management: "+op, "device", rq.caller.Name, "node", rq.caller.NodeID, "detail", remoteClean(detail, 160), "ok", ok)
}

// ─── The app's side ─────────────────────────────────────────────────────────

var (
	errRemoteNoDelegate = errors.New("the app has not registered its settings")
	errRemoteBusy       = errors.New("the app is busy")
)

// callDelegate runs one call into the app: one at a time, and not for longer
// than remoteDelegateTimeout. A call that outlives the wait keeps its slot
// until it returns, so a hung app refuses the next request instead of
// collecting goroutines.
func (m *remoteManager) callDelegate(f func(d RemoteDelegate) (string, error)) (string, error) {
	m.mu.Lock()
	d := m.delegate
	m.mu.Unlock()
	if d == nil {
		return "", errRemoteNoDelegate
	}
	wait := time.NewTimer(m.delegateTimeout)
	defer wait.Stop()
	select {
	case m.delegateSlot <- struct{}{}:
	case <-wait.C:
		return "", errRemoteBusy
	}
	type result struct {
		v   string
		err error
	}
	done := make(chan result, 1)
	go func() {
		defer func() { <-m.delegateSlot }()
		defer func() {
			if r := recover(); r != nil {
				done <- result{err: errors.New("the app failed")}
			}
		}()
		v, err := f(d)
		done <- result{v, err}
	}()
	select {
	case r := <-done:
		return r.v, r.err
	case <-wait.C:
		return "", errRemoteBusy
	}
}

// remoteDelegateRefusal turns a failed call into the answer for the paired
// device: the app's own words, cut short, when it refused; a fixed word when
// it could not be asked.
func remoteDelegateRefusal(err error) (int, any) {
	switch {
	case errors.Is(err, errRemoteNoDelegate), errors.Is(err, errRemoteBusy):
		return http.StatusServiceUnavailable, remoteError{Error: "unavailable", Message: err.Error()}
	}
	return http.StatusConflict, remoteError{Error: "refused", Message: remoteClean(err.Error(), 200)}
}

// fact returns one of the read-only settings, asking the app once.
func (m *remoteManager) fact(key string) string {
	m.mu.Lock()
	v, ok := m.facts[key]
	m.mu.Unlock()
	if ok {
		return v
	}
	v, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.GetSetting(key) })
	if err != nil {
		return ""
	}
	v = remoteClean(v, 64)
	if key == remoteKeyDeviceClass && !remoteDeviceClasses[v] {
		v = ""
	}
	m.mu.Lock()
	if m.facts == nil {
		m.facts = map[string]string{}
	}
	m.facts[key] = v
	m.mu.Unlock()
	return v
}

// ─── Routes ─────────────────────────────────────────────────────────────────

type remoteHello struct {
	// App is always "tailsocks": with a 200 from this port it is how a phone
	// tells that a peer offers management at all.
	App string `json:"app"`
	API int    `json:"api"`
	// DeviceClass is "tv", "phone", "tablet" or "" when the app has not said.
	DeviceClass string `json:"deviceClass"`
	Name        string `json:"name"`
	NodeID      string `json:"nodeId"`
	// Pairing: a code is on show right now.
	Pairing bool `json:"pairing"`
	// Paired: the calling node holds a pairing (its token is not checked
	// here). Class is what the node is or would be: "owner", "guest", or ""
	// when the policy would not let it pair. NeedsCode: it needs the code.
	Paired    bool   `json:"paired"`
	Class     string `json:"class"`
	NeedsCode bool   `json:"needsCode"`
}

func (m *remoteManager) handleHello(rq *remoteRequest) (int, any) {
	class := m.policy.classOf(rq.caller, rq.self)
	h := remoteHello{
		App:         "tailsocks",
		API:         remoteAPIVersion,
		DeviceClass: m.fact(remoteKeyDeviceClass),
		Name:        rq.self.Name,
		NodeID:      rq.self.NodeID,
		Class:       class.String(),
		NeedsCode:   class != remoteOwner || m.policy.OwnerNeedsCode,
	}
	m.mu.Lock()
	m.expireLocked()
	h.Pairing = m.window != nil
	if m.store != nil {
		if p := m.store.find(rq.caller.NodeID); p != nil {
			h.Paired = true
			h.Class = p.Class
		}
	}
	m.mu.Unlock()
	return http.StatusOK, h
}

func (m *remoteManager) handlePair(rq *remoteRequest) (int, any) {
	var in struct {
		Code string `json:"code"`
	}
	if !remoteDecode(rq.body, &in) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	if !m.pairs.allow(rq.caller.NodeID) {
		return http.StatusTooManyRequests, remoteError{Error: "slow_down"}
	}
	return m.pair(rq.caller, rq.self, in.Code)
}

func (m *remoteManager) handleUnpair(rq *remoteRequest) (int, any) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store.remove(rq.caller.NodeID) {
		m.auditLocked(remoteEvent{NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(), Op: "unpaired", OK: true})
		m.saveLocked()
		slog.Info("Remote management: a device unpaired itself", "device", rq.caller.Name, "node", rq.caller.NodeID)
	}
	return http.StatusOK, struct{}{}
}

type remoteExitNode struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
	// Selected marks the one in use. Country and City are set for nodes that
	// carry a location (a commercial exit-node fleet).
	Selected bool   `json:"selected,omitempty"`
	Country  string `json:"country,omitempty"`
	City     string `json:"city,omitempty"`
}

type remoteYou struct {
	Class string `json:"class"`
	// Left is the seconds the caller's pairing has left, -1 for "until
	// revoked".
	Left int64 `json:"left"`
}

type remoteStatus struct {
	// Running: the backend state is "Running".
	Running      bool   `json:"running"`
	BackendState string `json:"backendState"`
	// Version is the daemon's; AppVersion the app's, "" when it has not said.
	Version     string `json:"version"`
	AppVersion  string `json:"appVersion"`
	DeviceClass string `json:"deviceClass"`
	Name        string `json:"name"`
	DNSName     string `json:"dnsName"`
	NodeID      string `json:"nodeId"`
	// ExitNode is the one in use, or absent. Its name is filled in when the
	// peer list was at hand.
	ExitNode *remoteExitNode `json:"exitNode,omitempty"`
	// Health is the daemon's list of problems, empty when all is well.
	Health []string `json:"health"`
	// Since is when the daemon started, Unix seconds, 0 when not known.
	Since int64     `json:"since"`
	You   remoteYou `json:"you"`
}

func (m *remoteManager) handleStatus(rq *remoteRequest) (int, any) {
	s := rq.self
	st := remoteStatus{
		Running:      s.BackendState == "Running",
		BackendState: s.BackendState,
		Version:      s.Version,
		AppVersion:   m.fact(remoteKeyAppVersion),
		DeviceClass:  m.fact(remoteKeyDeviceClass),
		Name:         s.Name,
		DNSName:      s.DNSName,
		NodeID:       s.NodeID,
		Health:       []string{},
		Since:        GetDaemonStartTime(),
		You:          remoteYou{Class: rq.class.String(), Left: rq.left},
	}
	if st.Since < 0 {
		st.Since = 0
	}
	for i, h := range s.Health {
		if i == 10 {
			break
		}
		st.Health = append(st.Health, remoteClean(h, 300))
	}
	if s.ExitNodeID != "" {
		st.ExitNode = &remoteExitNode{ID: s.ExitNodeID, Online: s.ExitOnline, Selected: true}
		if n, ok := m.exitNodeNamed(s.ExitNodeID); ok {
			st.ExitNode.Name, st.ExitNode.Country, st.ExitNode.City = n.Name, n.Country, n.City
		}
	}
	return http.StatusOK, st
}

// remoteNamesTTL is how long the peer list is reused for naming the exit node
// in a status answer. The status is what a phone polls; the full peer list of
// a large tailnet is hundreds of kilobytes, and a name does not change.
const remoteNamesTTL = 30 * time.Second

// exitNodeNamed finds an exit node by id in a recent peer list.
func (m *remoteManager) exitNodeNamed(id string) (remoteExitNode, bool) {
	m.mu.Lock()
	nodes, fresh := m.names, time.Since(m.namesAt) < remoteNamesTTL
	m.mu.Unlock()
	find := func() (remoteExitNode, bool) {
		for _, n := range nodes {
			if n.ID == id {
				return n, true
			}
		}
		return remoteExitNode{}, false
	}
	if n, ok := find(); ok || fresh {
		return n, ok
	}
	var err error
	if nodes, err = m.exitNodes(); err != nil {
		return remoteExitNode{}, false
	}
	return find()
}

// remoteMaxExitNodes bounds the list an answer carries.
const remoteMaxExitNodes = 2000

// exitNodes asks the daemon for the peers that may be used as an exit node
// right now: offered by the peer and approved by the tailnet.
func (m *remoteManager) exitNodes() ([]remoteExitNode, error) {
	raw, err := m.api.get("/localapi/v0/status")
	if err != nil {
		return nil, err
	}
	var st struct {
		Peer map[string]*struct {
			ID             string
			HostName       string
			DNSName        string
			Online         bool
			ExitNode       bool
			ExitNodeOption bool
			Location       *struct {
				Country string
				City    string
			}
		}
	}
	if err := json.Unmarshal(raw, &st); err != nil {
		return nil, err
	}
	out := []remoteExitNode{}
	for _, p := range st.Peer {
		if p == nil || !p.ExitNodeOption || !remoteIsNodeID(p.ID) {
			continue
		}
		name, _, _ := strings.Cut(p.DNSName, ".")
		if name == "" {
			name = p.HostName
		}
		n := remoteExitNode{ID: p.ID, Name: remoteClean(name, 64), Online: p.Online, Selected: p.ExitNode}
		if p.Location != nil {
			n.Country, n.City = remoteClean(p.Location.Country, 64), remoteClean(p.Location.City, 64)
		}
		out = append(out, n)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return out[i].ID < out[j].ID
	})
	if len(out) > remoteMaxExitNodes {
		out = out[:remoteMaxExitNodes]
	}
	m.mu.Lock()
	m.names, m.namesAt = out, time.Now()
	m.mu.Unlock()
	return out, nil
}

func (m *remoteManager) handleExitNodes(rq *remoteRequest) (int, any) {
	nodes, err := m.exitNodes()
	if err != nil {
		return http.StatusServiceUnavailable, remoteError{Error: "unavailable"}
	}
	return http.StatusOK, struct {
		Nodes []remoteExitNode `json:"nodes"`
	}{nodes}
}

func (m *remoteManager) handleSetExitNode(rq *remoteRequest) (int, any) {
	var in struct {
		ID string `json:"id"`
	}
	if !remoteDecode(rq.body, &in) || (in.ID != "" && !remoteIsNodeID(in.ID)) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	name := "none"
	if in.ID != "" {
		// Only a node the daemon itself offers: the app is never handed an id
		// a paired device made up.
		nodes, err := m.exitNodes()
		if err != nil {
			return http.StatusServiceUnavailable, remoteError{Error: "unavailable"}
		}
		found := false
		for _, n := range nodes {
			if n.ID == in.ID {
				found, name = true, n.Name
				break
			}
		}
		if !found {
			return http.StatusNotFound, remoteError{Error: "not_found", Message: "not one of this device's exit nodes"}
		}
	}
	if _, err := m.callDelegate(func(d RemoteDelegate) (string, error) {
		return "", d.SetSetting(RemoteKeyExitNode, in.ID)
	}); err != nil {
		m.record(rq, "exit_node", name, false)
		return remoteDelegateRefusal(err)
	}
	// This node's exit node is part of the identity cached for a few seconds.
	m.mu.Lock()
	m.selfAt = time.Time{}
	m.mu.Unlock()
	m.record(rq, "exit_node", name, true)
	return http.StatusOK, struct{}{}
}

type remoteSettingView struct {
	Key  string `json:"key"`
	Kind string `json:"kind"` // "bool": "true" or "false"; "text"
	// Writable: the setting can be changed, and by this caller.
	Writable bool   `json:"writable"`
	Value    string `json:"value"`
	// Error is set, and Value empty, when the app could not say.
	Error string `json:"error,omitempty"`
}

func (m *remoteManager) handleSettings(rq *remoteRequest) (int, any) {
	mayWrite := m.policy.allows(remoteOpSetSetting, rq.class)
	out := []remoteSettingView{}
	for _, s := range remoteSettings {
		v := remoteSettingView{Key: s.key, Kind: s.kind, Writable: s.writable && mayWrite}
		if !s.writable {
			v.Value = m.fact(s.key)
		} else {
			val, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.GetSetting(s.key) })
			switch {
			case errors.Is(err, errRemoteNoDelegate), errors.Is(err, errRemoteBusy):
				return remoteDelegateRefusal(err)
			case err != nil || !remoteValueFits(s, val):
				v.Error = "unavailable"
			default:
				v.Value = val
			}
		}
		out = append(out, v)
	}
	return http.StatusOK, struct {
		Settings []remoteSettingView `json:"settings"`
	}{out}
}

// remoteValueFits reports whether value is one a setting of this kind can
// hold: checked on the way in and on the way out.
func remoteValueFits(s remoteSetting, value string) bool {
	switch s.kind {
	case "bool":
		return value == "true" || value == "false"
	}
	return false
}

func (m *remoteManager) handleSetSetting(rq *remoteRequest) (int, any) {
	var in struct {
		Key   string `json:"key"`
		Value string `json:"value"`
	}
	if !remoteDecode(rq.body, &in) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	s, ok := remoteSettingFor(in.Key)
	if !ok || !s.writable {
		// The app is not asked: a key that is not on the list does not exist.
		m.noteDenied(rq.caller, rq.class, "setting not on the list: "+remoteClean(in.Key, 40))
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "not a remote setting"}
	}
	if !remoteValueFits(s, in.Value) {
		return http.StatusBadRequest, remoteError{Error: "bad_request", Message: "not a value for this setting"}
	}
	detail := s.key + "=" + in.Value
	if _, err := m.callDelegate(func(d RemoteDelegate) (string, error) {
		return "", d.SetSetting(s.key, in.Value)
	}); err != nil {
		m.record(rq, "setting", detail, false)
		return remoteDelegateRefusal(err)
	}
	m.record(rq, "setting", detail, true)
	return http.StatusOK, struct{}{}
}

func (m *remoteManager) handleAction(rq *remoteRequest) (int, any) {
	var in struct {
		Action string `json:"action"`
	}
	if !remoteDecode(rq.body, &in) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	if !remoteActionKnown(in.Action) {
		m.noteDenied(rq.caller, rq.class, "action not on the list: "+remoteClean(in.Action, 40))
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "not a remote action"}
	}
	if _, err := m.callDelegate(func(d RemoteDelegate) (string, error) {
		return "", d.PerformAction(in.Action)
	}); err != nil {
		m.record(rq, "action", in.Action, false)
		return remoteDelegateRefusal(err)
	}
	m.record(rq, "action", in.Action, true)
	return http.StatusOK, struct{}{}
}

// ─── Received files ─────────────────────────────────────────────────────────

type remoteFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	// ModTime is Unix seconds.
	ModTime int64 `json:"modTime"`
}

// remoteMaxFiles bounds the list an answer carries.
const remoteMaxFiles = 1000

// remoteFiles lists the received Taildrop files of the current run: names,
// sizes and dates, never a path and never contents.
func (m *remoteManager) remoteFiles() ([]remoteFile, string) {
	dir := m.taildropDir()
	if dir == "" {
		return []remoteFile{}, ""
	}
	// The listing rules — no transfers in progress, no markers, no dot-files —
	// are the Files screen's own.
	var files []remoteFile
	if err := json.Unmarshal([]byte(GetWaitingFiles(dir)), &files); err != nil || files == nil {
		return []remoteFile{}, dir
	}
	if len(files) > remoteMaxFiles {
		files = files[:remoteMaxFiles]
	}
	return files, dir
}

func (m *remoteManager) handleFiles(rq *remoteRequest) (int, any) {
	files, _ := m.remoteFiles()
	return http.StatusOK, struct {
		Files []remoteFile `json:"files"`
	}{files}
}

// remoteFileNameOK reports whether name can only mean one file directly
// inside the Taildrop directory, and one a person received: a single path
// element, no separators of any platform, nothing hidden, not a transfer in
// progress or the daemon's deletion marker.
func remoteFileNameOK(name string) bool {
	if name == "" || len(name) > 255 || !utf8.ValidString(name) {
		return false
	}
	if name == "." || name == ".." || strings.HasPrefix(name, ".") {
		return false
	}
	if strings.HasSuffix(name, ".partial") || strings.HasSuffix(name, ".deleted") {
		return false
	}
	for _, r := range name {
		if r == '/' || r == '\\' || r == 0 || unicode.IsControl(r) {
			return false
		}
	}
	return filepath.Base(name) == name && filepath.Clean(name) == name && !filepath.IsAbs(name)
}

func (m *remoteManager) handleDeleteFile(rq *remoteRequest) (int, any) {
	var in struct {
		Name string `json:"name"`
	}
	if !remoteDecode(rq.body, &in) || !remoteFileNameOK(in.Name) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	// A name is accepted only if the listing shows it: the check above says
	// what a name may look like, this says the file is one of them.
	files, dir := m.remoteFiles()
	listed := false
	for _, f := range files {
		if f.Name == in.Name {
			listed = true
			break
		}
	}
	if !listed {
		return http.StatusNotFound, remoteError{Error: "not_found"}
	}
	path := filepath.Join(dir, in.Name)
	if fi, err := os.Lstat(path); err != nil || !fi.Mode().IsRegular() {
		return http.StatusNotFound, remoteError{Error: "not_found"}
	}
	if err := os.Remove(path); err != nil {
		m.record(rq, "file_deleted", in.Name, false)
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	m.record(rq, "file_deleted", in.Name, true)
	return http.StatusOK, struct{}{}
}

// ─── Log tail ───────────────────────────────────────────────────────────────

const (
	remoteLogDefault = 100
	remoteLogMax     = 200
	remoteLogLine    = 500
)

// remoteLogSecret matches lines that may quote a credential or a login link.
// The log is the owner's to read, but a sign-in URL decides which account a
// device joins and a key is a key: those lines stay on the device.
var remoteLogSecret = regexp.MustCompile(`(?i)tskey-|auth_?key|password|passwd|secret|token|bearer|authurl|/a/[0-9a-z]{6,}|/register/|socks5://[^ ]*@|https?://[^ ]*@`)

// remoteLogTail returns the last n entries of the in-memory log.
func remoteLogTail(n int) []LogEntry {
	logManager.mu.RLock()
	defer logManager.mu.RUnlock()
	logs := logManager.logs
	if len(logs) > n {
		logs = logs[len(logs)-n:]
	}
	return append([]LogEntry(nil), logs...)
}

func (m *remoteManager) handleLog(rq *remoteRequest) (int, any) {
	n := remoteLogDefault
	if v := rq.query["lines"]; len(v) > 0 {
		parsed, err := strconv.Atoi(v[0])
		if err != nil || parsed < 1 {
			return http.StatusBadRequest, remoteError{Error: "bad_request"}
		}
		n = parsed
	}
	if n > remoteLogMax {
		n = remoteLogMax
	}
	out := []LogEntry{}
	for _, e := range m.logTail(n) {
		if remoteLogSecret.MatchString(e.Message) {
			continue
		}
		if len(e.Message) > remoteLogLine {
			e.Message = strings.ToValidUTF8(e.Message[:remoteLogLine], "") + "…"
		}
		out = append(out, e)
	}
	return http.StatusOK, struct {
		Lines []LogEntry `json:"lines"`
	}{out}
}
