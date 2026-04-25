// +build android

package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"

	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		// 1. Try to get state from environment variable (injected by appctr)
		if envState := os.Getenv("TS_NET_STATE"); envState != "" {
			var list []struct {
				Name      string   `json:"name"`
				Addresses []string `json:"addresses"`
				Up        bool     `json:"up"`
				MTU       int      `json:"mtu"`
			}
			if err := json.Unmarshal([]byte(envState), &list); err == nil && len(list) > 0 {
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
							// On Android, we don't always know the mask, use /32 or /128
							mask := net.CIDRMask(32, 32)
							if ip.To4() == null {
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

		// 2. Fallback to anet (might fail with netlinkrib permission denied)
		ifs, err := anet.Interfaces()
		if err != nil {
			// CRITICAL: On Android 10+ netlink is blocked. 
			// Returning empty list is better than erroring out and breaking netcheck.
			return []netmon.Interface{}, nil 
		}

		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				// Continue with empty addresses for this interface if we can't get them
				ret[i] = netmon.Interface{
					Interface: &ifs[i],
				}
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

var null = net.IP(nil)
