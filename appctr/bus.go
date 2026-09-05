package appctr

// bus.go — IPN Bus Listener: subscribes to the tailscaled LocalAPI event
// stream (Notify struct) and maintains an in-memory snapshot of daemon state.

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// ─── IPN Notify snapshot ───────────────────────────────────────────────────

// BusNotify mirrors the tailscaled Notify struct fields used by appctr.
type BusNotify struct {
	// Session / version
	Version    string  `json:"Version,omitempty"`
	SessionID  string  `json:"SessionID,omitempty"`
	ErrMessage *string `json:"ErrMessage,omitempty"`

	// Connectivity state (0=NoState, 1=InUseOtherUser, 2=NeedsLogin, 3=NeedsMachineAuth, 4=Stopped, 5=Starting, 6=Running)
	State *int `json:"State,omitempty"`

	// Preferences
	Prefs *BusPrefs `json:"Prefs,omitempty"`

	// Full network map (sent on connect when NotifyInitialNetMap is set in mask)
	NetMap *BusNetMap `json:"NetMap,omitempty"`

	// Incremental peer updates
	PeersChanged     []*BusPeer        `json:"PeersChanged,omitempty"`
	PeersRemoved     []json.RawMessage `json:"PeersRemoved,omitempty"` // []NodeID
	PeerChangedPatch []*BusPeerPatch   `json:"PeerChangedPatch,omitempty"`

	// Health & suggestions
	Health            *BusHealth `json:"Health,omitempty"`
	SuggestedExitNode *string    `json:"SuggestedExitNode,omitempty"`

	// Self-node change
	SelfChange *BusPeer `json:"SelfChange,omitempty"`

	// Client update availability
	ClientVersion *BusClientVersion `json:"ClientVersion,omitempty"`

	// Engine stats
	Engine *BusEngineStatus `json:"Engine,omitempty"`

	// Auth
	BrowseToURL   *string   `json:"BrowseToURL,omitempty"`
	LoginFinished *struct{} `json:"LoginFinished,omitempty"`
}

// BusPrefs mirrors the subset of ipn.Prefs we care about.
type BusPrefs struct {
	ControlURL      string   `json:"ControlURL,omitempty"`
	RouteAll        bool     `json:"RouteAll,omitempty"`
	ExitNodeID      string   `json:"ExitNodeID,omitempty"`
	ExitNodeIP      string   `json:"ExitNodeIP,omitempty"`
	CorpDNS         bool     `json:"CorpDNS,omitempty"`
	WantRunning     bool     `json:"WantRunning,omitempty"`
	ShieldsUp       bool     `json:"ShieldsUp,omitempty"`
	AdvertiseRoutes []string `json:"AdvertiseRoutes,omitempty"`
	Hostname        string   `json:"Hostname,omitempty"`
}

// BusPeer mirrors tailcfg.Node / ipnstate.PeerStatus fields we need.
type BusPeer struct {
	ID             json.RawMessage `json:"ID,omitempty"` // tailcfg.NodeID
	StableID       string          `json:"StableID,omitempty"`
	Name           string          `json:"Name,omitempty"` // FQDN
	HostName       string          `json:"HostName,omitempty"`
	DNSName        string          `json:"DNSName,omitempty"`
	OS             string          `json:"OS,omitempty"`
	Addresses      []string        `json:"Addresses,omitempty"` // CIDR strings
	TailscaleIPs   []string        `json:"TailscaleIPs,omitempty"`
	Online         *bool           `json:"Online,omitempty"`
	Active         bool            `json:"Active,omitempty"`
	ExitNode       bool            `json:"ExitNode,omitempty"`
	ExitNodeOption bool            `json:"ExitNodeOption,omitempty"`
	Tags           []string        `json:"Tags,omitempty"`
	Capabilities   []string        `json:"Capabilities,omitempty"`
	Relay          string          `json:"Relay,omitempty"` // preferred DERP region
	LastSeen       *string         `json:"LastSeen,omitempty"`
}

// BusPeerPatch mirrors tailcfg.PeerChange.
type BusPeerPatch struct {
	NodeID   json.RawMessage `json:"NodeID,omitempty"`
	Online   *bool           `json:"Online,omitempty"`
	LastSeen *string         `json:"LastSeen,omitempty"`
	DERPHome *int            `json:"DERPHome,omitempty"`
}

// BusNetMap mirrors netmap.NetworkMap for DNS population.
type BusNetMap struct {
	MagicDNSSuffix string       `json:"MagicDNSSuffix,omitempty"`
	SelfNode       *BusPeer     `json:"SelfNode,omitempty"`
	Peers          []*BusPeer   `json:"Peers,omitempty"`
	DNS            BusNetMapDNS `json:"DNS"`
}

// BusNetMapDNS mirrors the DNS sub-struct inside NetworkMap.
type BusNetMapDNS struct {
	Domains []string `json:"Domains,omitempty"`
	Routes  map[string][]struct {
		Addr string `json:"Addr"`
	} `json:"Routes,omitempty"`
}

// BusHealth mirrors health.State.
type BusHealth struct {
	Warnings map[string]struct {
		WarnableCode string `json:"WarnableCode,omitempty"`
		Title        string `json:"Title,omitempty"`
		Text         string `json:"Text,omitempty"`
	} `json:"Warnings,omitempty"`
}

// BusEngineStatus mirrors ipn.EngineStatus.
type BusEngineStatus struct {
	RBytes    int64 `json:"RBytes,omitempty"`
	WBytes    int64 `json:"WBytes,omitempty"`
	NumLive   int   `json:"NumLive,omitempty"`
	LiveDERPs int   `json:"LiveDERPs,omitempty"`
}

// BusClientVersion mirrors tailcfg.ClientVersion.
type BusClientVersion struct {
	RunningLatest bool   `json:"RunningLatest,omitempty"`
	LatestVersion string `json:"LatestVersion,omitempty"`
}

// ─── In-memory state snapshot ────────────────────────────────────────────────

type busStateSnapshot struct {
	BackendState   string
	AuthURL        string
	TailscaleIPs   []string
	Self           *BusPeer
	Health         []BusHealthWarning
	Prefs          *BusPrefs
	ClientVersion  *BusClientVersion
	MagicDNSSuffix string
}

type BusHealthWarning struct {
	Code string
	Text string
}

var (
	busState   busStateSnapshot
	busStateMu sync.RWMutex
)

// GetBusState returns a read-only copy of the current daemon state snapshot.
func GetBusState() busStateSnapshot {
	busStateMu.RLock()
	defer busStateMu.RUnlock()
	return busState
}

// resetBusState forgets everything learned from the previous daemon (or
// profile). The snapshot short-circuits GetBackendState and GetLoginURL, so
// left in place it answered for a daemon that no longer existed: "Running" for
// a successor still in NoState, or a dead AuthURL that turned SetPrefs into a
// silent no-op. With an empty snapshot those fall through to one live status
// query and the bus listener repopulates it from there.
func resetBusState() {
	busStateMu.Lock()
	busState = busStateSnapshot{}
	lastHealthFingerprint = ""
	busStateMu.Unlock()
}

// ─── Shared DNS state (written here, read in dns.go) ────────────────────────

var splitDNSCache sync.Map // domain → []string resolverIPs
var nodesCache sync.Map    // hostname / FQDN → []string IPs
var magicDNSSuffix string
var magicDNSSuffixMu sync.RWMutex

func getMagicDNSSuffix() string {
	magicDNSSuffixMu.RLock()
	defer magicDNSSuffixMu.RUnlock()
	return magicDNSSuffix
}

func setMagicDNSSuffix(s string) {
	magicDNSSuffixMu.Lock()
	magicDNSSuffix = s
	magicDNSSuffixMu.Unlock()
}

// ─── Bus lifecycle ───────────────────────────────────────────────────────────

// lastHealthFingerprint suppresses repeated identical health reports. Guarded
// by busStateMu like the snapshot it describes.
var lastHealthFingerprint string

var busCancel context.CancelFunc

// busGen is bumped every time a new listener's cancel is installed. A listener
// goroutine captures the generation it was born with and only clears busCancel
// if it still owns the current one — a Stop()->Start() may have already replaced
// the listener while a stale goroutine was winding down, and an unconditional
// nil would wipe the NEW listener's cancel and leak it.
var busGen uint64
var busMu sync.Mutex

// EnsureIPNBusListener starts the bus listener goroutine if not already running.
// The listener waits for the LocalAPI socket to accept connections before it
// issues its first request, so calling this during startup is safe.
func EnsureIPNBusListener() {
	busMu.Lock()
	defer busMu.Unlock()
	if busCancel != nil {
		return
	}

	stateMu.Lock()
	sock := PC.Socket()
	stateMu.Unlock()
	if sock == "" {
		return
	}

	slog.Info("Starting IPN Bus Listener...")
	busCtx, cancel := context.WithCancel(context.Background())
	busCancel = cancel
	busGen++
	gen := busGen
	go func() {
		if !waitForLocalAPI(busCtx, 30*time.Second) {
			if busCtx.Err() == nil {
				slog.Warn("IPN Bus: daemon socket never became ready, listener not started")
			}
			busMu.Lock()
			// Only clear if we still own the current listener; a Stop()->Start()
			// may have installed a newer cancel that must not be dropped.
			if busGen == gen {
				busCancel = nil
			}
			busMu.Unlock()
			cancel()
			return
		}
		startIPNBusListener(busCtx, gen)
	}()
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

// ─── Listener loop ───────────────────────────────────────────────────────────

func startIPNBusListener(ctx context.Context, gen uint64) {
	defer func() {
		busMu.Lock()
		// Only clear if this goroutine still owns the current listener; a
		// Stop()->Start() may have replaced it, and niling would leak the new one.
		if busGen == gen {
			busCancel = nil
		}
		busMu.Unlock()
	}()

	// One transport for the listener's lifetime, closed when it exits. A fresh
	// transport per reconnect attempt parked the finished stream's connection in
	// an idle pool nothing could reach any more — the same fd leak the cached
	// LocalAPI client fixed. The socket path is read at dial time under stateMu:
	// every socket change goes through StopIPNBusListener, and a release that
	// clears it turns the next dial into an error the loop already handles.
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", getPC().Socket())
		},
		MaxIdleConns:        1,
		MaxIdleConnsPerHost: 1,
		IdleConnTimeout:     60 * time.Second,
	}
	defer tr.CloseIdleConnections()

	delay := 2 * time.Second

	for {
		select {
		case <-ctx.Done():
			return
		default:
			err := listenToBus(ctx, tr)
			if err == nil {
				delay = 2 * time.Second
				continue
			}

			if ctx.Err() != nil {
				return
			}

			errStr := err.Error()
			if strings.Contains(errStr, "permission denied") {
				slog.Error("Bus listener: permission denied — SELinux/chmod issue. Not retrying.", "err", err)
				return
			}

			// The daemon is gone (stopped, killed, or detached): stop reconnecting
			// instead of retrying a dead socket for the lifetime of the process.
			if !IsRunning() {
				slog.Info("Bus listener: daemon is no longer running, stopping listener")
				return
			}

			slog.Warn("Bus listener disconnected, retrying", "err", err, "backoff", delay)
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
			}

			delay *= 2
			if delay > 30*time.Second {
				delay = 30 * time.Second
			}
		}
	}
}

// listenToBus opens the streaming LocalAPI connection and processes all Notify
// messages until context cancellation or a fatal error.
func listenToBus(ctx context.Context, tr *http.Transport) error {
	client := http.Client{Transport: tr}

	req, _ := http.NewRequestWithContext(ctx, "GET",
		"http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask=4095", nil)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	dec := json.NewDecoder(resp.Body)
	for {
		var msg BusNotify
		if err := dec.Decode(&msg); err != nil {
			return err
		}
		// A message decoded just as the listener was stopped belongs to the
		// daemon being released; applying it would repopulate the snapshot
		// releaseGoResources has just reset.
		if ctx.Err() != nil {
			return ctx.Err()
		}
		applyNotify(&msg)
	}
}

// applyNotify merges a single Notify message into busState and DNS caches.
func applyNotify(msg *BusNotify) {
	busStateMu.Lock()
	defer busStateMu.Unlock()

	if msg.ErrMessage != nil {
		slog.Error("Bus: daemon error message", "msg", *msg.ErrMessage)
	}

	// Backend state. The daemon repeats the current state on many notifications
	// and on every bus reconnect, so only actual transitions are worth a line —
	// logging each message buried the log while waiting for a login.
	if msg.State != nil {
		val := *msg.State
		stateNames := [...]string{"NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running"}
		name := fmt.Sprintf("State(%d)", val)
		if val >= 0 && val < len(stateNames) {
			name = stateNames[val]
		}
		if name != busState.BackendState {
			slog.Info("Bus: state changed", "from", busState.BackendState, "to", name)
		}
		busState.BackendState = name
	}

	// Auth URL (login flow)
	if msg.BrowseToURL != nil {
		if *msg.BrowseToURL != busState.AuthURL {
			slog.Info("Bus: auth URL updated")
		}
		busState.AuthURL = *msg.BrowseToURL
	}

	// Preferences
	if msg.Prefs != nil {
		busState.Prefs = msg.Prefs
	}

	// Client version info
	if msg.ClientVersion != nil {
		busState.ClientVersion = msg.ClientVersion
	}

	// Health
	if msg.Health != nil && len(msg.Health.Warnings) > 0 {
		busState.Health = make([]BusHealthWarning, 0, len(msg.Health.Warnings))
		for code, w := range msg.Health.Warnings {
			c := w.WarnableCode
			if c == "" {
				c = code
			}
			txt := w.Text
			if txt == "" {
				txt = w.Title
			}
			busState.Health = append(busState.Health, BusHealthWarning{Code: c, Text: txt})
		}
		// Health is republished constantly; report only when the set changes.
		codes := make([]string, 0, len(busState.Health))
		for _, w := range busState.Health {
			codes = append(codes, w.Code)
		}
		sort.Strings(codes)
		fingerprint := strings.Join(codes, ",")
		if fingerprint != lastHealthFingerprint {
			lastHealthFingerprint = fingerprint
			slog.Warn("Bus: health warnings", "count", len(busState.Health), "codes", fingerprint)
		}
	}

	// Self-node change
	if msg.SelfChange != nil {
		busState.Self = msg.SelfChange
		updateNodeCacheFromPeer(msg.SelfChange)
	}

	// Full NetMap
	if msg.NetMap != nil {
		applyNetMapToDNSCache(msg.NetMap)
		if msg.NetMap.SelfNode != nil {
			busState.Self = msg.NetMap.SelfNode
		}
	}

	// Incremental peer upserts feed the MagicDNS cache only. The snapshot used
	// to keep its own StableID → peer map as well, but nothing read it and it
	// was never pruned, so it grew with every peer of every profile ever seen.
	if len(msg.PeersChanged) > 0 {
		for _, p := range msg.PeersChanged {
			updateNodeCacheFromPeer(p)
		}
		slog.Info("Bus: peers upserted", "count", len(msg.PeersChanged))
	}
}

// ─── DNS cache helpers ───────────────────────────────────────────────────────

// applyNetMapToDNSCache updates all DNS caches from a NetMap snapshot.
func applyNetMapToDNSCache(nm *BusNetMap) {
	// 1. MagicDNS suffix
	if nm.MagicDNSSuffix != "" {
		setMagicDNSSuffix(strings.ToLower(strings.Trim(nm.MagicDNSSuffix, ".")))
	} else if len(nm.DNS.Domains) > 0 {
		setMagicDNSSuffix(strings.ToLower(strings.Trim(nm.DNS.Domains[0], ".")))
	}

	// 2. Peer nodes
	nodesCount := 0
	if nm.SelfNode != nil && updateNodeCacheFromPeer(nm.SelfNode) {
		nodesCount++
	}
	for _, p := range nm.Peers {
		if updateNodeCacheFromPeer(p) {
			nodesCount++
		}
	}

	// 3. Split DNS routes
	routesCount := 0
	for domain, resolvers := range nm.DNS.Routes {
		var ips []string
		for _, r := range resolvers {
			if r.Addr != "" {
				ips = append(ips, r.Addr)
			}
		}
		if len(ips) == 0 {
			ips = []string{"100.100.100.100"}
		}
		d := strings.ToLower(strings.Trim(domain, "."))
		splitDNSCache.Store(d, ips)
		routesCount++
	}

	suffixNow := getMagicDNSSuffix()
	if nodesCount > 0 || routesCount > 0 || suffixNow != "" {
		slog.Info("Bus: DNS caches updated", "nodes", nodesCount, "routes", routesCount, "suffix", suffixNow)
	}
}

// updateNodeCacheFromPeer inserts a BusPeer's addresses into nodesCache.
func updateNodeCacheFromPeer(p *BusPeer) bool {
	if p == nil {
		return false
	}

	// Prefer DNSName > Name > HostName for FQDN
	name := p.DNSName
	if name == "" {
		name = p.Name
	}
	if name == "" {
		name = p.HostName
	}
	if name == "" {
		return false
	}

	fullName := strings.ToLower(strings.Trim(name, "."))

	// Collect IPs from Addresses (CIDR) or TailscaleIPs
	var ips []string
	addrs := p.Addresses
	if len(addrs) == 0 {
		addrs = p.TailscaleIPs
	}
	for _, addr := range addrs {
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
