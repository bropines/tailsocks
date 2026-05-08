package appctr

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
)

// GetStatusFromAPI returns the daemon status as JSON.
func GetStatusFromAPI() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}
	slog.Info("LocalAPI: [GET] /localapi/v0/status?peers=true")
	data, err := doLocalRequest("GET", "/localapi/v0/status?peers=true", nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}
	if len(data) == 0 {
		return `{"Error": "Status API returned empty response"}`
	}
	return string(data)
}

// GetDnsStatusJSON returns DNS information for DnsActivity.
func GetDnsStatusJSON() string {
	if !IsRunning() {
		return "{}"
	}

	socks, _, _, dns := GConfig.get()

	// Build the structure expected by DnsActivity.kt.
	type dnsAddr struct {
		Addr string `json:"Addr"`
	}
	type tailnetInfo struct {
		MagicDNSEnabled bool    `json:"MagicDNSEnabled"`
		MagicDNSSuffix  string  `json:"MagicDNSSuffix"`
		SelfDNSName     string  `json:"SelfDNSName"`
	}
	type status struct {
		TailscaleDNS   bool                    `json:"TailscaleDNS"`
		CurrentTailnet tailnetInfo             `json:"CurrentTailnet"`
		SplitDNSRoutes map[string][]dnsAddr   `json:"SplitDNSRoutes"`
	}

	res := status{
		TailscaleDNS: dns != "",
		CurrentTailnet: tailnetInfo{
			MagicDNSEnabled: magicDNSSuffix != "",
			MagicDNSSuffix:  magicDNSSuffix,
		},
		SplitDNSRoutes: make(map[string][]dnsAddr),
	}

	// Populate Split DNS routes from cache.
	splitDNSCache.Range(func(key, value interface{}) bool {
		domain := key.(string)
		ips := value.([]string)
		var addrs []dnsAddr
		for _, ip := range ips {
			addrs = append(addrs, dnsAddr{Addr: ip})
		}
		res.SplitDNSRoutes[domain] = addrs
		return true
	})

	// Try to find own node name in the nodes cache.
	if socks != "" { 
		nodesCache.Range(func(key, value interface{}) bool {
			name := key.(string)
			if strings.HasSuffix(name, magicDNSSuffix) && !strings.Contains(strings.TrimSuffix(name, magicDNSSuffix), ".") {
				res.CurrentTailnet.SelfDNSName = name
				return false
			}
			return true
		})
	}

	data, _ := json.Marshal(res)
	return string(data)
}

// GetBackendState returns the current backend state (Running, Starting, etc.).
func GetBackendState() string {
	if !IsRunning() {
		return "Stopped"
	}
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err != nil {
		return "Error"
	}
	var res struct {
		BackendState string `json:"BackendState"`
	}
	if err := json.Unmarshal(data, &res); err != nil {
		slog.Error("LocalAPI: Failed to parse BackendState", "err", err)
		return "Error"
	}
	return res.BackendState
}

// GetSelfDNSName returns the MagicDNS name of the current device.
func GetSelfDNSName() string {
	if !IsRunning() {
		return ""
	}
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err != nil {
		return ""
	}
	var res struct {
		Self struct {
			DNSName string `json:"DNSName"`
		} `json:"Self"`
	}
	if err := json.Unmarshal(data, &res); err != nil {
		return ""
	}
	return strings.TrimSuffix(res.Self.DNSName, ".")
}

// GetCoreVersion returns the Tailscale Core version string.
func GetCoreVersion() string {
	return coreVersion
}
