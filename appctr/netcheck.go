package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"tailscale.com/net/netcheck"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
)

// GetNetcheckFromAPI runs a native network test using the DERP map from the daemon.
func GetNetcheckFromAPI() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/derpmap (for netcheck)")
	// 1. Fetch DERP map from the daemon.
	data, err := doLocalRequest("GET", "/localapi/v0/derpmap", nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Failed to get DERP map: %v"}`, err)
	}

	var dm tailcfg.DERPMap
	if err := json.Unmarshal(data, &dm); err != nil {
		return fmt.Sprintf(`{"Error": "Failed to parse DERP map: %v"}`, err)
	}

	// 2. Initialise a static network monitor (no event bus needed here).
	nm := netmon.NewStatic()
	defer nm.Close()

	// 3. Run the native netcheck.
	c := &netcheck.Client{
		NetMon: nm,
		Logf: func(format string, args ...any) {
			slog.Info(fmt.Sprintf("netcheck: "+format, args...))
		},
	}

	report, err := c.GetReport(context.Background(), &dm, nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Netcheck failed: %v"}`, err)
	}

	if report == nil {
		return `{"Error": "Netcheck returned nil report"}`
	}

	slog.Info("LocalAPI: Netcheck completed")
	// 4. Return the JSON report.
	res, err := json.Marshal(report)
	if err != nil {
		return fmt.Sprintf(`{"Error": "JSON marshal failed: %v"}`, err)
	}
	return string(res)
}
