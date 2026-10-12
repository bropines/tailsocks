//go:build !linux

package appctr

import "time"

// remoteBootTime: only Linux (Android included) has the boot-time clock read
// in remote_clock_linux.go; elsewhere the process's monotonic clock stands in.
func remoteBootTime() (up time.Duration, ok bool) { return 0, false }
