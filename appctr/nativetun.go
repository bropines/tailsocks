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
	nativeTun.Lock()
	if nativeTun.f != nil {
		nativeTun.f.Close()
	}
	nativeTun.f = f
	nativeTun.Unlock()

	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()
	if opt == nil {
		return "the daemon has not been started yet"
	}
	slog.Info("Native TUN: relaunching the daemon on the VpnService device")
	Start(opt)
	return ""
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
	if s := GetBusState(); s.Self != nil {
		if ips := peerIPs(s.Self); len(ips) > 0 {
			return strings.Join(ips, ",")
		}
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
