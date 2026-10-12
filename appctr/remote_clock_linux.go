//go:build linux

package appctr

import (
	"syscall"
	"time"
	"unsafe"
)

// clockBoottime is CLOCK_BOOTTIME: time since boot, suspend included. Go's own
// monotonic clock is CLOCK_MONOTONIC, which stands still while the device
// sleeps — and a TV in standby sleeps most of the day.
const clockBoottime = 7

// remoteBootTime reads the boot-time clock; ok is false where the kernel will
// not say, and the caller falls back to the process's own monotonic clock.
func remoteBootTime() (up time.Duration, ok bool) {
	var ts syscall.Timespec
	if _, _, errno := syscall.Syscall(syscall.SYS_CLOCK_GETTIME, clockBoottime, uintptr(unsafe.Pointer(&ts)), 0); errno != 0 {
		return 0, false
	}
	return time.Duration(ts.Sec)*time.Second + time.Duration(ts.Nsec), true
}
