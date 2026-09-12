package appctr

// nativetun.go — the bridge half of the native TUN engine. Kotlin's VpnService
// establishes the device and hands over a duplicate of its fd; the daemon is
// relaunched with --tun=android-vpn and the fd inherited as fd 3 (TS_TUN_FD,
// see patch 18). The bridge keeps its copy so every later launch of the daemon
// — a crash restart included — gets the same device, until ClearNativeTun.

import (
	"log/slog"
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

// GetSelfIPs returns the node's tailnet addresses from the IPN bus snapshot,
// comma-separated, "" until the netmap has arrived. The VpnService.Builder in
// native TUN mode uses them as the interface addresses.
func GetSelfIPs() string {
	return strings.Join(GetBusState().TailscaleIPs, ",")
}
