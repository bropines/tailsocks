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

type DERPInfo struct {
	Code string `json:"Code"`
	Name string `json:"Name"`
}

type NetcheckResponse struct {
	Report   *netcheck.Report `json:"Report"`
	DERPMeta map[int]DERPInfo `json:"DERPMeta"`
}

// GetNetcheckFromAPI runs a native network test using the DERP map from the daemon.
func GetNetcheckFromAPI() string {
	if !IsRunning() {
		return `{"Error": "` + errNotRunning.Error() + `"}`
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
		Logf:   func(format string, args ...any) { slog.Info("netcheck", "msg", fmt.Sprintf(format, args...)) },
	}

	report, err := c.GetReport(context.Background(), &dm, nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Netcheck failed: %v"}`, err)
	}

	if report == nil {
		return `{"Error": "Netcheck returned nil report"}`
	}

	slog.Info("LocalAPI: Netcheck completed")

	// Map DERP regions information
	derpMeta := make(map[int]DERPInfo)
	for id, reg := range dm.Regions {
		derpMeta[id] = DERPInfo{
			Code: reg.RegionCode,
			Name: reg.RegionName,
		}
	}

	resp := NetcheckResponse{
		Report:   report,
		DERPMeta: derpMeta,
	}

	// 4. Return the JSON report.
	res, err := json.Marshal(resp)
	if err != nil {
		return fmt.Sprintf(`{"Error": "JSON marshal failed: %v"}`, err)
	}
	return string(res)
}
