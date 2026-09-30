package appctr

import (
	"log/slog"
	"time"
)

// timeZoneID is the zone SetTimeZone applied; the daemon process gets it as TZ.
var timeZoneID string

// SetTimeZone gives Go the device's time zone, an IANA name such as
// "Europe/Moscow". Go looks for TZ in the copy of the environment it took when
// the library loaded, then for /etc/localtime, which Android does not have — so
// without this every time the Go side formats (the log buffer, the daemon's
// lines) is UTC, next to local times from the Kotlin side. A TZ set from Java
// after the library has loaded is never seen. The zone data itself comes from
// the system tzdata, which Go's time package reads on Android.
func SetTimeZone(id string) {
	loc, err := time.LoadLocation(id)
	if err != nil {
		slog.Debug("Unknown time zone", "id", id, "err", err)
		return
	}
	stateMu.Lock()
	timeZoneID = id
	stateMu.Unlock()
	time.Local = loc
}
