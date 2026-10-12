package appctr

import (
	"encoding/json"
)

// netmapCacheCap is the node attribute with which the coordination server
// allows a node to keep its network map on disk (tailcfg/nodecap
// CacheNetworkMaps). Without it tailscaled 1.104 neither writes nor reads the
// cache and deletes an existing one; a tailnet policy can withhold it with
// "disable-cache-network-maps", which control resolves before sending.
const netmapCacheCap = "cache-network-maps"

// netmapCacheReport is what Settings shows beside the netmap-cache switch.
type netmapCacheReport struct {
	// Known is false when there is no daemon or it holds no network map yet,
	// so nothing can be said about the server's decision.
	Known bool `json:"known"`
	// Granted: the self node carries the cache-network-maps attribute, so the
	// daemon keeps a copy (unless the switch is off).
	Granted bool `json:"granted"`
	// WithoutControl: the daemon has a network map but is not in a map poll —
	// right after a start from the saved map, before control answers, or
	// while control is unreachable later on.
	WithoutControl bool `json:"withoutControl"`
}

// GetNetmapCacheJSON reports, from the daemon's own status, whether the
// coordination server lets this node cache its network map and whether the
// node is running without control right now. "" when the status cannot be read.
func GetNetmapCacheJSON() string {
	st, err := GetStatusJSON(false)
	if err != nil || st == "" {
		return ""
	}
	r, err := netmapCacheFromStatus([]byte(st))
	if err != nil {
		return ""
	}
	out, _ := json.Marshal(r)
	return string(out)
}

func netmapCacheFromStatus(status []byte) (netmapCacheReport, error) {
	var st struct {
		Self *struct {
			Online       bool
			InNetworkMap bool
			CapMap       map[string]json.RawMessage
		}
	}
	if err := json.Unmarshal(status, &st); err != nil {
		return netmapCacheReport{}, err
	}
	if st.Self == nil || !st.Self.InNetworkMap {
		return netmapCacheReport{}, nil
	}
	_, granted := st.Self.CapMap[netmapCacheCap]
	return netmapCacheReport{
		Known:          true,
		Granted:        granted,
		WithoutControl: !st.Self.Online,
	}, nil
}

// ClearNetmapCache deletes the daemon's saved network maps — every profile in
// its state directory — and drops its in-memory handle on them, as
// `tailscale debug clear-netmap-cache` does (LocalAPI debug action
// "clear-netmap-cache"). The next full map from control is saved afresh if the
// server still grants the attribute and TS_USE_CACHED_NETMAP is not false.
func ClearNetmapCache() error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	_, err := doLocalRequest("POST", "/localapi/v0/debug?action=clear-netmap-cache", nil)
	return err
}
