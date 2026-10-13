package appctr

// remote_server.go — the HTTP side of remote management: what is checked on
// every request, and the handful of things a paired device may ask for.
//
// Order of checks, the same for every path, so that a caller learns nothing
// from a path it may not use: the overall rate; the request's shape (no
// browser, an address for a host); who is calling, from the daemon; that
// caller's own rate; then the route, the token and the class; and only then
// the body.
//
// Who is calling is necessary and never enough (remote_auth.go). Two routes
// answer on it alone, and are made so that this is harmless: /v1/hello says
// the same few things to everyone and asks nothing of the app, and /v1/pair
// does nothing without the code from the screen. Everything else needs the
// token that was issued to that node.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"log/slog"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"sort"
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
	body  []byte
	// modern: the caller's bridge knows more than the first build's API
	// (remoteClientHeader). What an older one would show without
	// understanding — a setting by its bare key, with a switch that works —
	// is kept from it.
	modern bool
}

// remoteClientHeader is what the managing side's bridge sends with every
// request since the API grew: the number says how much of it the caller
// knows. The first build sends none.
const (
	remoteClientHeader  = "X-Tailsocks-Remote"
	remoteClientVersion = "2"
)

// remoteFirstSettings is what /v1/settings listed in the first build. A caller
// that does not say it knows more is shown these and no others: it would draw
// every other switch under its raw key and let it be turned.
var remoteFirstSettings = map[string]bool{
	"accept_dns": true, "accept_routes": true, "exit_node_lan_access": true,
	remoteKeyDeviceClass: true, remoteKeyAppVersion: true,
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
// signs out, touches an account, a key or a credential, or returns a file —
// and none that returns the log: it quotes auth keys, sign-in links, proxy
// credentials and paths in more forms than a filter can be trusted to know.
// None grants a permission either: /v1/permissions/request asks the app to
// show a page. What comes after it in the list is newer: a confirmation of a
// guarded change, "say whose hands you are in", and what takes full control —
// the storage by name, shares, the diagnostics and the scrubbed log
// (remote_level.go).
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
	{"POST", "/v1/permissions/request", remoteOpAskPermission, (*remoteManager).handleAskPermission},
	{"POST", "/v1/confirm", remoteOpConfirm, (*remoteManager).handleConfirm},
	{"POST", "/v1/identify", remoteOpIdentify, (*remoteManager).handleIdentify},
	{"POST", "/v1/shares/remove", remoteOpSharesRemove, (*remoteManager).handleShareRemove},
	{"POST", "/v1/shares/add", remoteOpSharesAdd, (*remoteManager).handleShareAdd},
	{"POST", "/v1/storage/list", remoteOpStorageList, (*remoteManager).handleStorageList},
	{"POST", "/v1/storage/send", remoteOpStorageSend, (*remoteManager).handleStorageSend},
	{"GET", "/v1/diag/report", remoteOpDiagReport, (*remoteManager).handleDiagReport},
	{"GET", "/v1/diag/log", remoteOpDiagLog, (*remoteManager).handleDiagLog},
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
	// net/http survives a handler that panics, and reports it to the server's
	// error log — which is discarded here. Say that it happened, not what was
	// being handled, and let net/http close the connection as it would.
	defer func() {
		if rec := recover(); rec != nil {
			slog.Error("Remote management: a request handler failed", "panic", fmt.Sprintf("%T", rec))
			panic(http.ErrAbortHandler)
		}
	}()
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

	rq := &remoteRequest{caller: caller, self: self, left: -1, modern: r.Header.Get(remoteClientHeader) != ""}
	if route.op != "" {
		class, left, ok := m.authorize(caller, self, remoteBearer(r))
		if !ok {
			remoteWrite(w, http.StatusUnauthorized, remoteError{Error: "unauthorized"})
			return
		}
		if ok, needsFull := m.may(route.op, class); !ok {
			if needsFull {
				// The caller's, at a level the device is not at.
				m.noteDenied(caller, class, route.op+": full control is off")
				remoteWrite(w, http.StatusForbidden, remoteNeedsFull)
				return
			}
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
	now := m.classOf(c, self)

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
	if ok {
		// And on the device's screen, while the app is up: never silent.
		m.notice(rq.caller.Name, op, detail)
	}
}

// ─── The app's side ─────────────────────────────────────────────────────────

var (
	// Not made: there is no app to call, or it never got to this call.
	errRemoteNoDelegate = errors.New("the app has not registered its settings")
	errRemoteBusy       = errors.New("the app is busy; nothing was changed")
	// Made, and not back yet. Nobody can say what came of it.
	errRemotePending = errors.New("the app has not answered; the change may still be applied")
	// Made and answered, in a word that is not one of the answers.
	errRemoteBadAnswer = errors.New("the app answered with something else; what it did is not known")
)

// remoteCallResult is what a call into the app came back with.
type remoteCallResult struct {
	v   string
	err error
}

// callDelegate runs one call into the app, and what it reports is what
// happened — a call into Kotlin cannot be taken back, so there are exactly
// three outcomes:
//
//   - The call was not made: no app (errRemoteNoDelegate), or the app was
//     still busy with an earlier call after delegateWait (errRemoteBusy).
//     Nothing was changed, and that is what the caller is told.
//   - The call returned within delegateAnswer: its own result.
//   - The call was made and has not returned (errRemotePending): the outcome
//     is not known, and late delivers it whenever the app does answer.
//
// One call at a time: a call that has not returned keeps the slot, so a hung
// app refuses what comes next instead of collecting calls it may carry out
// all at once later.
func (m *remoteManager) callDelegate(f func(d RemoteDelegate) (string, error)) (v string, late <-chan remoteCallResult, err error) {
	return m.callDelegateWithin(m.delegateWait, m.delegateAnswer, f)
}

// callDelegateWithin is callDelegate with waits of the caller's choosing: how
// long for the app to be free, how long for its answer.
func (m *remoteManager) callDelegateWithin(free, answered time.Duration, f func(d RemoteDelegate) (string, error)) (v string, late <-chan remoteCallResult, err error) {
	m.mu.Lock()
	d := m.delegate
	m.mu.Unlock()
	if d == nil {
		return "", nil, errRemoteNoDelegate
	}
	wait := time.NewTimer(free)
	select {
	case m.delegateSlot <- struct{}{}:
		wait.Stop()
	case <-wait.C:
		return "", nil, errRemoteBusy
	}
	done := make(chan remoteCallResult, 1)
	go func() {
		defer func() { <-m.delegateSlot }()
		defer func() {
			if r := recover(); r != nil {
				done <- remoteCallResult{err: errors.New("the app failed")}
			}
		}()
		v, err := f(d)
		done <- remoteCallResult{v, err}
	}()
	answer := time.NewTimer(answered)
	defer answer.Stop()
	select {
	case r := <-done:
		return r.v, nil, r.err
	case <-answer.C:
		return "", done, errRemotePending
	}
}

// remoteDelegateRefusal turns a call that did not succeed into the answer for
// the paired device: 503 when it was not made, 504 "pending" when nobody knows
// yet, and the app's own words, cut short, when it refused.
func remoteDelegateRefusal(err error) (int, any) {
	switch {
	case errors.Is(err, errRemoteNoDelegate), errors.Is(err, errRemoteBusy):
		return http.StatusServiceUnavailable, remoteError{Error: "unavailable", Message: err.Error()}
	case errors.Is(err, errRemotePending):
		return http.StatusGatewayTimeout, remoteError{Error: "pending", Message: err.Error()}
	case errors.Is(err, errRemoteBadAnswer):
		return http.StatusBadGateway, remoteError{Error: "failed", Message: err.Error()}
	}
	// Two of the app's refusals are the API's own words, not sentences: what
	// was named is not there, or is not for anyone to name.
	switch err.Error() {
	case "not_found":
		return http.StatusNotFound, remoteError{Error: "not_found"}
	case "forbidden":
		return http.StatusForbidden, remoteError{Error: "forbidden"}
	}
	return http.StatusConflict, remoteError{Error: "refused", Message: remoteClean(err.Error(), 200)}
}

// change makes one change through the app, writes it to the action log as what
// it turned out to be, and answers the same.
func (m *remoteManager) change(rq *remoteRequest, op, detail string, f func(d RemoteDelegate) error) (int, any) {
	_, status, v := m.changeTold(rq, op, detail, func(d RemoteDelegate) (string, error) { return "", f(d) }, nil)
	return status, v
}

// changeTold is change for a call whose answer says more than "done". told
// reads the app's word: what to add to the line of the action log, or that the
// word is not one the bridge knows — and then nobody can say what was done.
// The word comes back only with a 200.
func (m *remoteManager) changeTold(rq *remoteRequest, op, detail string, f func(d RemoteDelegate) (string, error), told func(v string) (string, bool)) (string, int, any) {
	v, late, err := m.callDelegate(f)
	if err == nil && told != nil {
		if more, ok := told(v); ok {
			detail += more
		} else {
			err = errRemoteBadAnswer
		}
	}
	switch {
	case err == nil:
		m.record(rq, op, detail, true)
		m.forgetReport()
		return v, http.StatusOK, struct{}{}
	case errors.Is(err, errRemotePending):
		// Neither done nor failed. The log says so now and says the rest when
		// the app comes back, however long after this request is over.
		m.record(rq, op, detail+" (no answer from the app yet)", false)
		go func() {
			if r := <-late; r.err == nil {
				m.record(rq, op, detail+" (applied late)", true)
			} else {
				m.record(rq, op, detail+" (failed late)", false)
			}
			m.forgetReport()
		}()
	case errors.Is(err, errRemoteNoDelegate), errors.Is(err, errRemoteBusy):
		m.record(rq, op, detail+" (not asked of the app)", false)
	case errors.Is(err, errRemoteBadAnswer):
		m.record(rq, op, detail+" (the app's answer was not understood)", false)
		m.forgetReport()
	default:
		m.record(rq, op, detail, false)
	}
	status, body := remoteDelegateRefusal(err)
	return "", status, body
}

// cachedFact returns a read-only setting if the app has already told it, and
// asks nothing.
func (m *remoteManager) cachedFact(key string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.facts[key]
}

// fact returns one of the read-only settings, asking the app if it has not
// been told yet. It is for requests that have shown a token; /v1/hello uses
// cachedFact.
func (m *remoteManager) fact(key string) string {
	m.mu.Lock()
	v, ok := m.facts[key]
	asked := m.delegateGen
	m.mu.Unlock()
	if ok {
		return v
	}
	v, _, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.GetSetting(key) })
	if err != nil {
		return ""
	}
	v = remoteClean(v, 64)
	if key == remoteKeyDeviceClass && !remoteDeviceClasses[v] {
		v = ""
	}
	m.mu.Lock()
	if m.delegateGen == asked {
		if m.facts == nil {
			m.facts = map[string]string{}
		}
		m.facts[key] = v
		m.rev++
	}
	m.mu.Unlock()
	return v
}

// ─── Routes ─────────────────────────────────────────────────────────────────

// remoteHello is the answer to /v1/hello: the one thing said to a caller that
// has shown nothing but an address the daemon attributes to a peer. So it says
// the same to everyone, nothing about the caller, and nothing a tailnet member
// could not already see — except that this is a TailSocks offering management,
// what kind of device it is, and whether it is waiting for a code.
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
}

func (m *remoteManager) handleHello(rq *remoteRequest) (int, any) {
	h := remoteHello{
		App: "tailsocks",
		API: remoteAPIVersion,
		// What the app said when it registered; it is not asked now.
		DeviceClass: m.cachedFact(remoteKeyDeviceClass),
		Name:        rq.self.Name,
		NodeID:      rq.self.NodeID,
	}
	m.mu.Lock()
	m.expireLocked()
	h.Pairing = m.window != nil
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
	if m.store == nil || !m.store.remove(rq.caller.NodeID) {
		return http.StatusOK, struct{}{}
	}
	m.auditLocked(remoteEvent{NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(), Op: "unpaired", OK: true})
	slog.Info("Remote management: a device unpaired itself", "device", rq.caller.Name, "node", rq.caller.NodeID)
	if !m.saveRemovalLocked() {
		// Refused from now on, but not yet on disk: the device should know
		// that its token may be honoured again after a restart of the app.
		return http.StatusInternalServerError, remoteError{Error: "not_saved", Message: "unpaired, but it could not be written to disk yet"}
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
	// Suggested marks, in the list of /v1/exit-nodes, the one this device's
	// own daemon would pick. At most one, and none when it has no opinion.
	Suggested bool `json:"suggested,omitempty"`
}

type remoteYou struct {
	Class string `json:"class"`
	// Left is the seconds the caller's pairing has left, -1 for "until
	// revoked".
	Left int64 `json:"left"`
	// Can lists the operations (remoteOps) the caller may use right now: its
	// class at the device's level.
	Can []string `json:"can"`
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
	// Caps names what this build can do (remoteCaps), whoever asks; Level is
	// "basic" or "full" (remote_level.go).
	Caps  []string `json:"caps"`
	Level string   `json:"level"`
	// Guard is the caller's change that waits for a confirmation, if one
	// does (remote_guard.go); Fetch its last "send me this file"
	// (remote_storage.go).
	Guard *remoteGuardView `json:"guard,omitempty"`
	Fetch *remoteFetch     `json:"fetch,omitempty"`
	// Device is what the device is; Advertise what it offers to route
	// (remote_device.go).
	Device    *remoteDeviceFacts `json:"device,omitempty"`
	Advertise *remoteAdvertise   `json:"advertise,omitempty"`
	// App is what the app says of itself — permissions, where received files
	// go, the folders it shares — as far as this caller may know; absent when
	// the app did not say in time. See remote_app.go.
	App *remoteAppReport `json:"app,omitempty"`
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
		You:          remoteYou{Class: rq.class.String(), Left: rq.left, Can: m.can(rq.class)},
		Caps:         remoteCaps,
		Level:        m.level(),
		Guard:        m.guardFor(rq.caller.NodeID),
		Fetch:        m.fetchFor(rq.caller.NodeID),
		Advertise:    m.advertise(s),
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
	st.App, st.Device = m.appReport(rq.class)
	if st.Device == nil {
		st.Device = &remoteDeviceFacts{}
	} else {
		facts := *st.Device
		st.Device = &facts
	}
	st.Device.KeyExpiry = s.KeyExpiry
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
	if id := m.suggestedExitNode(); id != "" {
		// A copy: the list itself is kept for naming the exit node in a status.
		nodes = append([]remoteExitNode(nil), nodes...)
		for i := range nodes {
			nodes[i].Suggested = nodes[i].ID == id && nodes[i].Online
		}
	}
	return http.StatusOK, struct {
		Nodes []remoteExitNode `json:"nodes"`
	}{nodes}
}

// suggestedExitNode asks the daemon which exit node it would pick for this
// device — LocalAPI suggest-exit-node, what `tailscale exit-node suggest`
// prints — and returns its id; "" when the daemon names none: no relay
// measured yet, or no node the tailnet recommends. The answer only marks a
// node of the list: choosing it is still /v1/exit-node with that node's id.
func (m *remoteManager) suggestedExitNode() string {
	raw, err := m.api.get("/localapi/v0/suggest-exit-node")
	if err != nil {
		return ""
	}
	var s struct{ ID string }
	if json.Unmarshal(raw, &s) != nil || !remoteIsNodeID(s.ID) {
		return ""
	}
	return s.ID
}

func (m *remoteManager) handleSetExitNode(rq *remoteRequest) (int, any) {
	var in struct {
		ID string `json:"id"`
		// Guard: keep the change only if this caller confirms it afterwards
		// (remote_guard.go).
		Guard bool `json:"guard"`
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
	var status int
	var v any
	if in.Guard {
		status, v = m.changeGuarded(rq, "exit_node", RemoteKeyExitNode, in.ID, name)
	} else {
		status, v = m.change(rq, "exit_node", name, func(d RemoteDelegate) error {
			return d.SetSetting(RemoteKeyExitNode, in.ID)
		})
	}
	// This node's exit node is part of the identity cached for a few seconds.
	m.mu.Lock()
	m.selfAt = time.Time{}
	m.mu.Unlock()
	return status, v
}

type remoteSettingView struct {
	Key string `json:"key"`
	// Kind is "bool": "true" or "false"; "cidrs": prefixes separated by
	// commas; "text".
	Kind string `json:"kind"`
	// Writable: the setting can be changed, and by this caller.
	Writable bool   `json:"writable"`
	Value    string `json:"value"`
	// Only, on a switch, is the one value this caller may write to it now —
	// "true" or "false". Needs is "full" when the other way round (for a
	// list: anything but clearing it) takes full control; without it, the
	// other way round is done at the device and nowhere else.
	Only  string `json:"only,omitempty"`
	Needs string `json:"needs,omitempty"`
	// Guard is "optional" when a change may be sent to be confirmed, "always"
	// when it is held for a confirmation whatever is sent (remote_guard.go).
	Guard string `json:"guard,omitempty"`
	// Error is set, and Value empty, when the app could not say.
	Error string `json:"error,omitempty"`
}

func (m *remoteManager) handleSettings(rq *remoteRequest) (int, any) {
	mayWrite := m.policy.allows(remoteOpSetSetting, rq.class)
	full := m.fullControl()
	out := []remoteSettingView{}
	for _, s := range remoteSettings {
		if !rq.modern && !remoteFirstSettings[s.key] {
			continue
		}
		v := remoteSettingViewOf(s, mayWrite, full)
		if !rq.modern {
			// As the first build said them: none of the newer marks.
			v.Guard = ""
		}
		if !s.writable {
			v.Value = m.fact(s.key)
		} else {
			val, _, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.GetSetting(s.key) })
			switch {
			case errors.Is(err, errRemoteNoDelegate), errors.Is(err, errRemoteBusy), errors.Is(err, errRemotePending):
				// Reading changes nothing, so an app that does not answer is
				// simply not available; the rest of the list would only wait
				// behind the same call.
				return http.StatusServiceUnavailable, remoteError{Error: "unavailable", Message: "the app is not answering"}
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
	case "cidrs":
		_, ok := remoteRoutesValue(value)
		return ok
	}
	return false
}

func (m *remoteManager) handleSetSetting(rq *remoteRequest) (int, any) {
	var in struct {
		Key   string `json:"key"`
		Value string `json:"value"`
		// Guard: keep the change only if this caller confirms it afterwards
		// (remote_guard.go). Taken for a setting that can be guarded.
		Guard bool `json:"guard"`
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
	value := in.Value
	if s.kind == "cidrs" {
		// The app is handed the list as the bridge read it, not as it came.
		value, _ = remoteRoutesValue(in.Value)
	}
	switch remoteSettingRefusal(s, value, m.fullControl()) {
	case "only":
		// The app is not asked: from afar this switch goes one way.
		m.noteDenied(rq.caller, rq.class, "only at the device: "+s.key+"="+value)
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "that way round, only at the device"}
	case "full":
		m.noteDenied(rq.caller, rq.class, "full control is off: "+s.key+"="+value)
		return http.StatusForbidden, remoteNeedsFull
	}
	detail := s.key + "=" + value
	if s.guard == "always" || (s.guard == "optional" && in.Guard) {
		return m.changeGuarded(rq, "setting", s.key, value, detail)
	}
	status, v := m.change(rq, "setting", detail, func(d RemoteDelegate) error {
		return d.SetSetting(s.key, value)
	})
	if s.key == remoteKeyFullControl && status == http.StatusOK {
		// Given up, and at once: the app tells the bridge again with the next
		// settings it applies, this does not wait for that.
		m.mu.Lock()
		m.full = false
		m.rev++
		m.mu.Unlock()
	}
	return status, v
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
	if in.Action == remoteActionRelays {
		// The bridge's own: the relay connections are dialed again, nothing
		// restarts, the app is not asked. Its answer is "" or why not.
		why := m.relays()
		m.record(rq, "action", in.Action, why == "")
		if why != "" {
			return http.StatusConflict, remoteError{Error: "refused", Message: remoteClean(why, 200)}
		}
		return http.StatusOK, struct{}{}
	}
	return m.change(rq, "action", in.Action, func(d RemoteDelegate) error {
		return d.PerformAction(in.Action)
	})
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
