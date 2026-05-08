// +build android

package appctr

import (
	"encoding/json"
	"net"

	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		// 1. Try to use latest injected state from Kotlin
		stateMu.Lock()
		state := latestInterfaceState
		stateMu.Unlock()

		if state != "" {
			var list []struct {
				Name      string   `json:"name"`
				Addresses []string `json:"addresses"`
				Up        bool     `json:"up"`
				MTU       int      `json:"mtu"`
			}
			if err := json.Unmarshal([]byte(state), &list); err == nil && len(list) > 0 {
				ret := make([]netmon.Interface, 0, len(list))
				for _, iface := range list {
					if !iface.Up {
						continue
					}
					ni := netmon.Interface{
						Interface: &net.Interface{
							Name:  iface.Name,
							MTU:   iface.MTU,
							Flags: net.FlagUp,
						},
					}
					for _, addr := range iface.Addresses {
						ip := net.ParseIP(addr)
						if ip != nil {
							mask := net.CIDRMask(32, 32)
							if ip.To4() == nil {
								mask = net.CIDRMask(128, 128)
							}
							ni.AltAddrs = append(ni.AltAddrs, &net.IPNet{IP: ip, Mask: mask})
						}
					}
					if len(ni.AltAddrs) > 0 {
						ret = append(ret, ni)
					}
				}
				if len(ret) > 0 {
					return ret, nil
				}
			}
		}

		// 2. Fallback to anet
		ifs, err := anet.Interfaces()
		if err != nil {
			return []netmon.Interface{}, nil // Graceful fallback
		}

		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				ret[i] = netmon.Interface{Interface: &ifs[i]}
				continue
			}
			ret[i] = netmon.Interface{
				Interface: &ifs[i],
				AltAddrs:  addrs,
			}
		}

		return ret, nil
	})
}
