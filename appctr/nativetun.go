package appctr

// nativetun.go — the bridge half of the native TUN engine. Kotlin's VpnService
// establishes the device and hands over a duplicate of its fd; the daemon is
// relaunched with --tun=android-vpn and the fd inherited as fd 3 (TS_TUN_FD,
// see patch 18). The bridge keeps its copy so every later launch of the daemon
// — a crash restart included — gets the same device, until ClearNativeTun.

import (
	"encoding/json"
	"log/slog"
	"net"
	"os"
	"strings"
	"sync"
)

var nativeTun struct {
	sync.Mutex
	f *os.File
	// swapPending is set between ReleaseNativeTunForSwap and the SetNativeTun
	// that completes the restart, so the latter relaunches the daemon it stopped.
	swapPending bool
	// lastSelfIPs is the node's last known tailnet addresses, kept across
	// daemon relaunches for the callers that need them while no daemon can
	// answer (a device swap, a start on the VpnService device).
	lastSelfIPs string
}

// HasNativeTun reports whether a VpnService fd is waiting for, or in use by,
// the daemon.
func HasNativeTun() bool {
	nativeTun.Lock()
	defer nativeTun.Unlock()
	return nativeTun.f != nil
}

// nativeTunFile is the fd the next daemon launch inherits, or nil.
func nativeTunFile() *os.File {
	nativeTun.Lock()
	defer nativeTun.Unlock()
	return nativeTun.f
}

// SetNativeTun takes ownership of fd, a dup of the VpnService fd, and relaunches
// the daemon on it. Returns "" or the reason it could not.
func SetNativeTun(fd int32) string {
	f := os.NewFile(uintptr(fd), "vpn-tun")
	if f == nil {
		return "invalid fd"
	}
	running := IsRunning()
	if running {
		refreshOptionsFromDaemon()
	}
	nativeTun.Lock()
	if nativeTun.f != nil {
		nativeTun.f.Close()
	}
	nativeTun.f = f
	swap := nativeTun.swapPending
	nativeTun.swapPending = false
	nativeTun.Unlock()

	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()
	if opt == nil || (!running && !swap) {
		// No daemon to replace: the app established the VPN before Start(), so
		// the daemon's first launch is already on the device — no relaunch.
		slog.Info("Native TUN: device stored, the daemon will start on it")
		return ""
	}
	slog.Info("Native TUN: relaunching the daemon on the VpnService device")
	Start(opt)
	return ""
}

// ReleaseNativeTunForSwap prepares an in-place restart of the native engine:
// the caller is about to establish a new VPN (exit node or route change) and
// hand over a new fd. The live prefs are captured so the relaunch cannot revert
// them, the bridge copy of the old fd is dropped, and the daemon is stopped —
// not relaunched — so the coming SetNativeTun starts it exactly once, on the
// new device, and its exit is never mistaken for a crash.
func ReleaseNativeTunForSwap() {
	refreshOptionsFromDaemon()
	nativeTun.Lock()
	if nativeTun.f != nil {
		nativeTun.f.Close()
		nativeTun.f = nil
	}
	nativeTun.swapPending = true
	nativeTun.Unlock()
	slog.Info("Native TUN: stopping the daemon for a device swap")
	Stop()
}

// refreshOptionsFromDaemon copies the prefs a user can change outside
// ApplySettings — today the exit node, set straight through SetPrefs — from the
// running daemon into lastOptions, so a relaunch re-applies what is in effect.
func refreshOptionsFromDaemon() {
	if !IsRunning() {
		return
	}
	data, err := doLocalRequest("GET", "/localapi/v0/prefs", nil)
	if err != nil {
		return
	}
	var prefs struct {
		ExitNodeID string `json:"ExitNodeID"`
	}
	if json.Unmarshal(data, &prefs) != nil {
		return
	}
	stateMu.Lock()
	if lastOptions != nil && lastOptions.ExitNodeID != prefs.ExitNodeID {
		slog.Info("Native TUN: taking the exit node in effect into the relaunch options", "exit_node", prefs.ExitNodeID)
		lastOptions.ExitNodeID = prefs.ExitNodeID
	}
	stateMu.Unlock()
}

// ClearNativeTun drops the fd. With relaunch, a running daemon is started again
// in userspace-networking mode; the caller passes false when the whole
// connection is going down, so a daemon the app just stopped is not revived.
func ClearNativeTun(relaunch bool) {
	nativeTun.Lock()
	had := nativeTun.f != nil
	if had {
		nativeTun.f.Close()
		nativeTun.f = nil
	}
	nativeTun.swapPending = false
	nativeTun.Unlock()
	if !had || !relaunch {
		return
	}
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()
	if opt != nil && IsRunning() {
		slog.Info("Native TUN: relaunching the daemon without the VpnService device")
		Start(opt)
	}
}

// GetSelfIPs returns the node's tailnet addresses, comma-separated: from the
// IPN bus snapshot when the netmap has arrived, otherwise from LocalAPI
// /status; "" when neither knows them yet. The VpnService.Builder in native
// TUN mode uses them as the interface addresses.
func GetSelfIPs() string {
	if ips := liveSelfIPs(); ips != "" {
		nativeTun.Lock()
		nativeTun.lastSelfIPs = ips
		nativeTun.Unlock()
		return ips
	}
	nativeTun.Lock()
	defer nativeTun.Unlock()
	return nativeTun.lastSelfIPs
}

// liveSelfIPs asks the bus snapshot, then LocalAPI; "" when neither answers.
func liveSelfIPs() string {
	if s := GetBusState(); s.Self != nil {
		if ips := peerIPs(s.Self); len(ips) > 0 {
			return strings.Join(ips, ",")
		}
	}
	if !IsRunning() {
		return ""
	}
	data, err := doLocalRequest("GET", "/localapi/v0/status?peers=false", nil)
	if err != nil {
		return ""
	}
	var st struct{ TailscaleIPs []string }
	if json.Unmarshal(data, &st) != nil {
		return ""
	}
	return strings.Join(st.TailscaleIPs, ",")
}

// peerIPs lists a node's addresses without prefix lengths: TailscaleIPs when
// the message carried them, else Addresses (CIDRs) stripped.
func peerIPs(p *BusPeer) []string {
	if len(p.TailscaleIPs) > 0 {
		return p.TailscaleIPs
	}
	var ips []string
	for _, a := range p.Addresses {
		if i := strings.Index(a, "/"); i >= 0 {
			a = a[:i]
		}
		if net.ParseIP(a) != nil {
			ips = append(ips, a)
		}
	}
	return ips
}
