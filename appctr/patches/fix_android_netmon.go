// +build android

package main

import (
	"fmt"

	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := anet.Interfaces()
		if err != nil {
			return nil, fmt.Errorf("anet.Interfaces: %w", err)
		}
		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				return nil, fmt.Errorf("ifs[%d].Addrs: %w", i, err)
			}
			ret[i] = netmon.Interface{
				Interface: &ifs[i],
				AltAddrs:  addrs,
			}
		}

		return ret, nil
	})
}