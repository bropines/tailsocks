package appctr

// bus.go — IPN Bus Listener: subscribes to the full tailscaled LocalAPI event
// stream (Notify struct) and maintains an in-memory snapshot of daemon state.
//
// Architecture:
//   - listenToBus() receives ALL fields the daemon can emit.
//   - BusState is the single source of truth; callers read whatever they need.
//   - dns.go reads dnsCache/nodesCache/splitDNSCache/magicDNSSuffix from here.
//   - status.go / other callers read State, Prefs, InitialStatus, etc.

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ─── Full IPN Notify snapshot ────────────────────────────────────────────────

// BusNotify mirrors the full tailscaled Notify struct that comes over the
// watch-ipn-bus stream. All fields are optional (pointer / slice / map).
// See appctr/tailscale_src/ipn/backend.go for canonical field docs.
type BusNotify struct {
	// Session / version
	Version   string  `json:"Version,omitempty"`
	SessionID string  `json:"SessionID,omitempty"`
	ErrMessage *string `json:"ErrMessage,omitempty"`

	// Connectivity state (0=NoState, 1=InUseOtherUser, 2=NeedsLogin, 3=NeedsMachineAuth, 4=Stopped, 5=Starting, 6=Running)
	State *int `json:"State,omitempty"`

	// Preferences (sparse — only fields we actually use)
	Prefs *BusPrefs `json:"Prefs,omitempty"`

	// Initial full status snapshot (sent once on connect when NotifyInitialStatus set)
	InitialStatus *BusStatus `json:"InitialStatus,omitempty"`

	// Full network map (legacy, non-Windows mostly nil after first message)
	NetMap *BusNetMap `json:"NetMap,omitempty"`

	// Incremental peer updates
	PeersChanged      []*BusPeer     `json:"PeersChanged,omitempty"`
	PeersRemoved      []json.RawMessage `json:"PeersRemoved,omitempty"` // []NodeID (opaque)
	PeerChangedPatch  []*BusPeerPatch `json:"PeerChangedPatch,omitempty"`

	// Health & suggestions
	Health            *BusHealth `json:"Health,omitempty"`
	SuggestedExitNode *string    `json:"SuggestedExitNode,omitempty"`

	// Self-node change (lighter than full NetMap)
	SelfChange *BusPeer `json:"SelfChange,omitempty"`

	// Client update availability
	ClientVersion *BusClientVersion `json:"ClientVersion,omitempty"`

	// Taildrop
	FilesWaiting  *struct{} `json:"FilesWaiting,omitempty"`
	IncomingFiles []json.RawMessage `json:"IncomingFiles,omitempty"`
	OutgoingFiles []json.RawMessage `json:"OutgoingFiles,omitempty"`

	// Engine stats
	Engine *BusEngineStatus `json:"Engine,omitempty"`

	// Auth
	BrowseToURL  *string `json:"BrowseToURL,omitempty"`
	LoginFinished *struct{} `json:"LoginFinished,omitempty"`
}

// BusPrefs mirrors the subset of ipn.Prefs we care about.
type BusPrefs struct {
	ControlURL     string `json:"ControlURL,omitempty"`
	RouteAll       bool   `json:"RouteAll,omitempty"`
	ExitNodeID     string `json:"ExitNodeID,omitempty"`
	ExitNodeIP     string `json:"ExitNodeIP,omitempty"`
	CorpDNS        bool   `json:"CorpDNS,omitempty"`
	WantRunning    bool   `json:"WantRunning,omitempty"`
	ShieldsUp      bool   `json:"ShieldsUp,omitempty"`
	AdvertiseRoutes []string `json:"AdvertiseRoutes,omitempty"`
	Hostname       string `json:"Hostname,omitempty"`
}

// BusStatus mirrors ipnstate.Status (the InitialStatus snapshot).
type BusStatus struct {
	Version        string       `json:"Version,omitempty"`
	BackendState   string       `json:"BackendState,omitempty"`
	TailscaleIPs   []string     `json:"TailscaleIPs,omitempty"`
	MagicDNSSuffix string       `json:"MagicDNSSuffix,omitempty"`
	AuthURL        string       `json:"AuthURL,omitempty"`
	Self           *BusPeer     `json:"Self,omitempty"`
	Peer           map[string]*BusPeer `json:"Peer,omitempty"` // keyed by NodePublic hex
	Health         []string     `json:"Health,omitempty"`
	CurrentTailnet *BusTailnet  `json:"CurrentTailnet,omitempty"`
	ClientVersion  *BusClientVersion `json:"ClientVersion,omitempty"`
}

// BusTailnet mirrors ipnstate.TailnetStatus.
type BusTailnet struct {
	Name            string `json:"Name,omitempty"`
	MagicDNSSuffix  string `json:"MagicDNSSuffix,omitempty"`
	MagicDNSEnabled bool   `json:"MagicDNSEnabled,omitempty"`
}

// BusPeer mirrors tailcfg.Node / ipnstate.PeerStatus fields we need.
type BusPeer struct {
	ID           json.RawMessage `json:"ID,omitempty"`        // tailcfg.NodeID
	StableID     string          `json:"StableID,omitempty"`
	Name         string          `json:"Name,omitempty"`      // FQDN
	HostName     string          `json:"HostName,omitempty"`
	DNSName      string          `json:"DNSName,omitempty"`
	OS           string          `json:"OS,omitempty"`
	Addresses    []string        `json:"Addresses,omitempty"` // CIDR strings
	TailscaleIPs []string        `json:"TailscaleIPs,omitempty"`
	Online       *bool           `json:"Online,omitempty"`
	Active       bool            `json:"Active,omitempty"`
	ExitNode     bool            `json:"ExitNode,omitempty"`
	ExitNodeOption bool          `json:"ExitNodeOption,omitempty"`
	Tags         []string        `json:"Tags,omitempty"`
	Capabilities []string        `json:"Capabilities,omitempty"`
	Relay        string          `json:"Relay,omitempty"`  // preferred DERP region
	LastSeen     *string         `json:"LastSeen,omitempty"`
}

// BusPeerPatch mirrors tailcfg.PeerChange (narrow field updates).
type BusPeerPatch struct {
	NodeID  json.RawMessage `json:"NodeID,omitempty"`
	Online  *bool           `json:"Online,omitempty"`
	LastSeen *string        `json:"LastSeen,omitempty"`
	DERPHome *int           `json:"DERPHome,omitempty"`
}

// BusNetMap mirrors a subset of netmap.NetworkMap for DNS population.
type BusNetMap struct {
	MagicDNSSuffix string    `json:"MagicDNSSuffix,omitempty"`
	SelfNode       *BusPeer  `json:"SelfNode,omitempty"`
	Peers          []*BusPeer `json:"Peers,omitempty"`
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
	Warnings []struct {
		Code string `json:"Code,omitempty"`
		Text string `json:"Text,omitempty"`
	} `json:"Warnings,omitempty"`
}

// BusEngineStatus mirrors ipn.EngineStatus.
type BusEngineStatus struct {
	RBytes   int64 `json:"RBytes,omitempty"`
	WBytes   int64 `json:"WBytes,omitempty"`
	NumLive  int   `json:"NumLive,omitempty"`
	LiveDERPs int  `json:"LiveDERPs,omitempty"`
}

// BusClientVersion mirrors tailcfg.ClientVersion.
type BusClientVersion struct {
	RunningLatest bool   `json:"RunningLatest,omitempty"`
	LatestVersion string `json:"LatestVersion,omitempty"`
}

// ─── In-memory state snapshot ────────────────────────────────────────────────

// CurrentBusState holds the latest merged snapshot from all received Notify
// messages. Callers read it under busStateMu.
type busStateSnapshot struct {
	BackendState   string
	AuthURL        string
	TailscaleIPs   []string
	Self           *BusPeer
	Peers          map[string]*BusPeer // StableID → BusPeer
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

// ─── Shared DNS state (written here, read in dns.go) ────────────────────────

var dnsCache sync.Map
var splitDNSCache sync.Map // domain → []string resolverIPs
var nodesCache sync.Map    // hostname / FQDN → []string IPs
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
				// Clean disconnect — reset counter and reconnect.
				attempt = 0
				continue
			}

			errStr := err.Error()
			switch {
			case strings.Contains(errStr, "permission denied"):
				slog.Error("Bus listener: permission denied — SELinux/chmod issue. Not retrying.", "err", err)
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

// listenToBus opens the streaming LocalAPI connection and processes all Notify
// messages until context cancellation or a fatal error.
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

	// Backend state
	if msg.State != nil {
		val := *msg.State
		stateNames := [...]string{"NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running"}
		if val >= 0 && val < len(stateNames) {
			busState.BackendState = stateNames[val]
		} else {
			busState.BackendState = fmt.Sprintf("State(%d)", val)
		}
		slog.Info("Bus: state changed", "state", busState.BackendState, "code", val)
	}

	// Auth URL (login flow)
	if msg.BrowseToURL != nil {
		busState.AuthURL = *msg.BrowseToURL
		slog.Info("Bus: auth URL updated")
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
		for _, w := range msg.Health.Warnings {
			busState.Health = append(busState.Health, BusHealthWarning{Code: w.Code, Text: w.Text})
		}
		slog.Warn("Bus: health warnings", "count", len(busState.Health))
	}

	// InitialStatus snapshot — populate caches from full Status
	if msg.InitialStatus != nil {
		s := msg.InitialStatus
		busState.BackendState = s.BackendState
		busState.AuthURL = s.AuthURL
		busState.TailscaleIPs = s.TailscaleIPs
		busState.Self = s.Self
		if busState.Peers == nil {
			busState.Peers = make(map[string]*BusPeer)
		}
		for _, p := range s.Peer {
			if p.StableID != "" {
				busState.Peers[p.StableID] = p
			}
			updateNodeCacheFromPeer(p)
		}
		if s.Self != nil {
			updateNodeCacheFromPeer(s.Self)
		}
		suffix := s.MagicDNSSuffix
		if suffix == "" && s.CurrentTailnet != nil {
			suffix = s.CurrentTailnet.MagicDNSSuffix
		}
		if suffix != "" {
			magicDNSSuffix = strings.ToLower(strings.Trim(suffix, "."))
		}
		slog.Info("Bus: initial status applied", "state", s.BackendState, "peers", len(s.Peer))
	}

	// Self-node change (lightweight alternative to full NetMap)
	if msg.SelfChange != nil {
		busState.Self = msg.SelfChange
		updateNodeCacheFromPeer(msg.SelfChange)
	}

	// Full NetMap (legacy path, first message on non-Windows)
	if msg.NetMap != nil {
		applyNetMapToDNSCache(msg.NetMap)
		if msg.NetMap.SelfNode != nil {
			busState.Self = msg.NetMap.SelfNode
		}
	}

	// Incremental peer upserts
	if len(msg.PeersChanged) > 0 {
		if busState.Peers == nil {
			busState.Peers = make(map[string]*BusPeer)
		}
		for _, p := range msg.PeersChanged {
			if p.StableID != "" {
				busState.Peers[p.StableID] = p
			}
			updateNodeCacheFromPeer(p)
		}
		slog.Info("Bus: peers upserted", "count", len(msg.PeersChanged))
	}

	// Incremental peer removals (remove from caches by StableID)
	// PeersRemoved contains NodeIDs (opaque integers), we skip cache eviction
	// for now as the nodes cache TTL is not critical for DNS.
}

// ─── DNS cache helpers ───────────────────────────────────────────────────────

// applyNetMapToDNSCache updates all DNS caches from a NetMap snapshot.
func applyNetMapToDNSCache(nm *BusNetMap) {
	// 1. MagicDNS suffix
	if nm.MagicDNSSuffix != "" {
		magicDNSSuffix = strings.ToLower(strings.Trim(nm.MagicDNSSuffix, "."))
	} else if len(nm.DNS.Domains) > 0 {
		magicDNSSuffix = strings.ToLower(strings.Trim(nm.DNS.Domains[0], "."))
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

// syncNetMapFromBus does a one-shot poll to pre-populate DNS caches before
// the persistent listener is ready (e.g. on DNS proxy startup).
func syncNetMapFromBus() {
	pc := PC
	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
		Timeout: 3 * time.Second,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	req, _ := http.NewRequestWithContext(ctx, "GET",
		"http://local-tailscaled.sock/localapi/v0/watch-ipn-bus?mask=4095", nil)
	resp, err := client.Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()

	dec := json.NewDecoder(resp.Body)
	for {
		var msg BusNotify
		if err := dec.Decode(&msg); err != nil {
			break
		}
		applyNotify(&msg)
		// Stop after first message that gives us NetMap or InitialStatus
		if msg.NetMap != nil || msg.InitialStatus != nil {
			break
		}
	}
}
