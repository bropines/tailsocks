package appctr

import (
	"log/slog"
	"net"
	"os"
	"sync"
	"syscall"
)

var latestInterfaceState string

// defaultRouteInterface is the name last reported by SetDefaultRouteInterface,
// and defaultRoutePath is the file the daemon reads it from.
var defaultRouteInterface string
var defaultRoutePath string

var stateMu sync.Mutex

type GlobalConfig struct {
	mu         sync.RWMutex
	Socks5Addr string
	Socks5User string
	Socks5Pass string
	DNSAddr    string
}

func (c *GlobalConfig) get() (socks, user, pass, dns string) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return dialableAddr(c.Socks5Addr), c.Socks5User, c.Socks5Pass, c.DNSAddr
}

// dialableAddr rewrites a listen address into one that can be dialed locally.
// A proxy bound to a wildcard address (0.0.0.0 / ::) so that the LAN can reach
// it is still reachable over loopback, which is what in-process consumers use.
func dialableAddr(addr string) string {
	if addr == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	switch host {
	case "", "0.0.0.0":
		return net.JoinHostPort("127.0.0.1", port)
	case "::":
		return net.JoinHostPort("::1", port)
	}
	return addr
}

func (c *GlobalConfig) update(socks, user, pass, dns string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Socks5Addr = socks
	c.Socks5User = user
	c.Socks5Pass = pass
	c.DNSAddr = dns
}

var GConfig GlobalConfig

// SetDefaultRouteInterface records which interface currently carries the
// default route, as ConnectivityManager sees it.
//
// The daemon cannot work this out for itself: netmon's Android backend reads
// the name from a value the app is expected to set, and without it every
// change is classified as minor — NewChangeDelta gates a rebind on the default
// interface being viable, and an empty name is never viable. So the tunnel
// would notice a network change and still not rebind its sockets or re-run a
// netcheck. The name reaches the daemon through a file next to its state,
// because it is another process and its environment is fixed at launch.
func SetDefaultRouteInterface(name string) {
	// The network monitors in this process — tailcat's — read the default
	// route from here, as the official app's do.
	setInProcessDefaultRoute(name)
	stateMu.Lock()
	if name == defaultRouteInterface {
		stateMu.Unlock()
		return
	}
	defaultRouteInterface = name
	path := defaultRoutePath
	stateMu.Unlock()

	if path == "" {
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(name), 0o600); err != nil {
		slog.Debug("Could not write the default route interface", "err", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		slog.Debug("Could not publish the default route interface", "err", err)
		return
	}
	slog.Info("Default route interface", "name", name)
}

// seedDefaultRoute makes path the file SetDefaultRouteInterface keeps current
// and writes the interface known so far into it, for a daemon that reads it on
// start (TS_NETROUTE_FILE, patch 06).
func seedDefaultRoute(path string) {
	stateMu.Lock()
	defaultRoutePath = path
	route := defaultRouteInterface
	stateMu.Unlock()
	if route == "" {
		return
	}
	if err := os.WriteFile(path, []byte(route), 0o600); err != nil {
		slog.Debug("Could not seed the default route interface", "err", err)
	}
}

// InjectNetworkState takes Kotlin's view of the interfaces and, just as
// importantly, tells the daemon that something moved.
//
// The daemon cannot see that for itself: netlink is closed to an app's
// processes ("monitor_linux: AF_NETLINK RTMGRP failed, falling back to
// polling"), and upstream's polling fallback runs every ten minutes on Android
// precisely because it expects the app to wake it — which is what the official
// client does through Monitor.InjectEvent. Ours is a separate process, so the
// nudge is a signal; the daemon answers it in fix_android_netmon.go. Without
// it a network change was noticed at the next ten-minute poll, if at all: a
// Wi-Fi drop that healed before the poll was never noticed, and the tunnel sat
// on the path it had until DERP's own reconnect happened to fix it.
func InjectNetworkState(jsonState string) {
	stateMu.Lock()
	latestInterfaceState = jsonState
	proc := cmd
	stateMu.Unlock()
	slog.Info("Network state injected from Kotlin")

	if proc != nil && proc.Process != nil {
		if err := proc.Process.Signal(syscall.SIGUSR1); err != nil {
			slog.Debug("Could not wake the daemon's network monitor", "err", err)
		}
	}
	// And the monitors in this process: tailcat's clients run here.
	tailcatNetworkChanged()
	// A setup session listens on addresses that may just have gone (setup.go).
	setupNetworkChanged()
}
