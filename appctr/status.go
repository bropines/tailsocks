package appctr

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
)

// GetStatusFromAPI returns the daemon status as JSON.
func GetStatusFromAPI() string {
	data, err := GetStatusJSON(true)
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}
	if data == "" {
		return `{"Error": "Status API returned empty response"}`
	}
	return data
}

// GetDnsStatusJSON returns DNS information for DnsActivity.
func GetDnsStatusJSON() string {
	if !IsRunning() {
		return "{}"
	}

	EnsureIPNBusListener()

	socks, _, _, dns := GConfig.get()

	type dnsAddr struct {
		Addr string `json:"Addr"`
	}
	type tailnetInfo struct {
		MagicDNSEnabled bool   `json:"MagicDNSEnabled"`
		MagicDNSSuffix  string `json:"MagicDNSSuffix"`
		SelfDNSName     string `json:"SelfDNSName"`
	}
	type status struct {
		TailscaleDNS   bool                 `json:"TailscaleDNS"`
		CurrentTailnet tailnetInfo          `json:"CurrentTailnet"`
		SplitDNSRoutes map[string][]dnsAddr `json:"SplitDNSRoutes"`
	}

	bs := GetBusState()
	effectiveSuffix := magicDNSSuffix

	selfDNS := ""
	if bs.Self != nil && bs.Self.DNSName != "" {
		selfDNS = strings.TrimSuffix(bs.Self.DNSName, ".")
	}

	// Fallback to LocalAPI /status if bus state has not received NetMap yet
	if effectiveSuffix == "" || selfDNS == "" {
		var statusAPI struct {
			MagicDNSSuffix string `json:"MagicDNSSuffix"`
			CurrentTailnet struct {
				Name            string `json:"Name"`
				MagicDNSSuffix  string `json:"MagicDNSSuffix"`
				MagicDNSEnabled bool   `json:"MagicDNSEnabled"`
			} `json:"CurrentTailnet"`
			Self struct {
				DNSName string `json:"DNSName"`
			} `json:"Self"`
		}

		rawStatus, err := doLocalRequest("GET", "/localapi/v0/status", nil)
		if err == nil && json.Unmarshal(rawStatus, &statusAPI) == nil {
			if effectiveSuffix == "" {
				if statusAPI.MagicDNSSuffix != "" {
					effectiveSuffix = statusAPI.MagicDNSSuffix
				} else if statusAPI.CurrentTailnet.MagicDNSSuffix != "" {
					effectiveSuffix = statusAPI.CurrentTailnet.MagicDNSSuffix
				}
			}
			if selfDNS == "" && statusAPI.Self.DNSName != "" {
				selfDNS = strings.TrimSuffix(statusAPI.Self.DNSName, ".")
			}
		}
	}

	isMagicEnabled := effectiveSuffix != ""

	res := status{
		TailscaleDNS: dns != "" || isMagicEnabled,
		CurrentTailnet: tailnetInfo{
			MagicDNSEnabled: isMagicEnabled,
			MagicDNSSuffix:  effectiveSuffix,
			SelfDNSName:     selfDNS,
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

	// Fallback to nodes cache if selfDNS not obtained from LocalAPI or Bus
	if res.CurrentTailnet.SelfDNSName == "" && socks != "" && effectiveSuffix != "" {
		nodesCache.Range(func(key, value interface{}) bool {
			name := key.(string)
			if strings.HasSuffix(name, effectiveSuffix) && !strings.Contains(strings.TrimSuffix(name, effectiveSuffix), ".") {
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
	// Prefer bus State if set
	bs := GetBusState()
	if bs.BackendState != "" {
		return bs.BackendState
	}

	// Fallback to LocalAPI one-shot status
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
	bs := GetBusState()
	if bs.Self != nil && bs.Self.DNSName != "" {
		return strings.TrimSuffix(bs.Self.DNSName, ".")
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

