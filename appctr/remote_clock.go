package appctr

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"strings"
	"sync"
	"time"
)

// remoteSystemClock is the remoteClock of a real device.
type remoteSystemClock struct{}

func (remoteSystemClock) Now() time.Time { return time.Now() }

var remoteBoot struct {
	once  sync.Once
	id    string
	start time.Time
}

// Boot identifies the boot by the kernel's boot_id and reads the boot-time
// clock. Where either cannot be read — Android may keep /proc/sys from an app
// — every process counts as a boot of its own: its deadlines are then carried
// over through the wall clock each time the app restarts, which the store
// bounds the same way as a real reboot. Without the boot-time clock the
// process's monotonic clock stands in; it does not count sleep, so a pairing
// can then outlive its term by the time the device slept.
func (remoteSystemClock) Boot() (string, time.Duration) {
	remoteBoot.once.Do(func() {
		remoteBoot.start = time.Now()
		if _, ok := remoteBootTime(); ok {
			if raw, err := os.ReadFile("/proc/sys/kernel/random/boot_id"); err == nil {
				if id := strings.TrimSpace(string(raw)); len(id) >= 16 && len(id) <= 64 {
					remoteBoot.id = id
				}
			}
		}
		if remoteBoot.id == "" {
			var b [8]byte
			_, _ = rand.Read(b[:])
			remoteBoot.id = "process-" + hex.EncodeToString(b[:])
		}
	})
	if up, ok := remoteBootTime(); ok {
		return remoteBoot.id, up
	}
	return remoteBoot.id, time.Since(remoteBoot.start)
}
