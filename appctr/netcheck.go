package appctr

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"tailscale.com/net/netcheck"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tstime"
)

type DERPInfo struct {
	Code string `json:"Code"`
	Name string `json:"Name"`
}

type NetcheckResponse struct {
	Report   *netcheck.Report `json:"Report"`
	DERPMeta map[int]DERPInfo `json:"DERPMeta"`
}

// runNetcheck fetches the daemon's DERP map and measures against it in this
// process. `tailscale netcheck` cannot do this here: the CLI's network monitor
// needs a netlink RIB dump, which Android denies to non-root processes
// ("route ip+net: netlinkrib: permission denied"), and the daemon has no
// netcheck endpoint. A static monitor needs no such dump.
func runNetcheck() (*tailcfg.DERPMap, *netcheck.Report, error) {
	if !IsRunning() {
		return nil, nil, errNotRunning
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/derpmap (for netcheck)")
	data, err := doLocalRequest("GET", "/localapi/v0/derpmap", nil)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to get DERP map: %w", err)
	}

	var dm tailcfg.DERPMap
	if err := json.Unmarshal(data, &dm); err != nil {
		return nil, nil, fmt.Errorf("failed to parse DERP map: %w", err)
	}

	nm := netmon.NewStatic()
	defer nm.Close()

	c := &netcheck.Client{
		NetMon: nm,
		Logf:   func(format string, args ...any) { slog.Info("netcheck", "msg", fmt.Sprintf(format, args...)) },
	}

	report, err := c.GetReport(context.Background(), &dm, nil)
	if err != nil {
		return nil, nil, fmt.Errorf("netcheck failed: %w", err)
	}
	if report == nil {
		return nil, nil, errors.New("netcheck returned nil report")
	}

	slog.Info("LocalAPI: Netcheck completed")
	return &dm, report, nil
}

// GetNetcheckFromAPI runs a native network test using the DERP map from the
// daemon and returns it as JSON for the Netcheck screen.
func GetNetcheckFromAPI() string {
	dm, report, err := runNetcheck()
	if err != nil {
		b, _ := json.Marshal(map[string]string{"Error": err.Error()})
		return string(b)
	}

	// Map DERP regions information
	derpMeta := make(map[int]DERPInfo)
	for id, reg := range dm.Regions {
		derpMeta[id] = DERPInfo{
			Code: reg.RegionCode,
			Name: reg.RegionName,
		}
	}

	res, err := json.Marshal(NetcheckResponse{Report: report, DERPMeta: derpMeta})
	if err != nil {
		b, _ := json.Marshal(map[string]string{"Error": "JSON marshal failed: " + err.Error()})
		return string(b)
	}
	return string(res)
}

// netcheckText is the same report in the layout `tailscale netcheck` prints,
// for the Console; see runNetcheck for why the CLI itself cannot produce it.
func netcheckText() string {
	dm, r, err := runNetcheck()
	if err != nil {
		return "Error: " + err.Error()
	}
	var sb strings.Builder
	sb.WriteString("Report:\n")
	fmt.Fprintf(&sb, "\t* Time: %v\n", r.Now.Local().Format(tstime.DateSpTimeNanoZ))
	fmt.Fprintf(&sb, "\t* UDP: %v\n", r.UDP)
	if r.GlobalV4.IsValid() {
		fmt.Fprintf(&sb, "\t* IPv4: yes, %s\n", r.GlobalV4)
	} else {
		sb.WriteString("\t* IPv4: (no addr found)\n")
	}
	switch {
	case r.GlobalV6.IsValid():
		fmt.Fprintf(&sb, "\t* IPv6: yes, %s\n", r.GlobalV6)
	case r.IPv6:
		sb.WriteString("\t* IPv6: (no addr found)\n")
	case r.OSHasIPv6:
		sb.WriteString("\t* IPv6: no, but OS has support\n")
	default:
		sb.WriteString("\t* IPv6: no, unavailable in OS\n")
	}
	fmt.Fprintf(&sb, "\t* MappingVariesByDestIP: %v\n", r.MappingVariesByDestIP)
	fmt.Fprintf(&sb, "\t* PortMapping: %v\n", portMappingSummary(r))
	if r.CaptivePortal != "" {
		fmt.Fprintf(&sb, "\t* CaptivePortal: %v\n", r.CaptivePortal)
	}

	if len(r.RegionLatency) == 0 {
		sb.WriteString("\t* Nearest DERP: unknown (no response to latency probes)\n")
		return sb.String()
	}
	if r.PreferredDERP != 0 {
		if region, ok := dm.Regions[r.PreferredDERP]; ok {
			fmt.Fprintf(&sb, "\t* Nearest DERP: %v\n", region.RegionName)
		} else {
			fmt.Fprintf(&sb, "\t* Nearest DERP: %v (region not found in map)\n", r.PreferredDERP)
		}
	} else {
		sb.WriteString("\t* Nearest DERP: [none]\n")
	}
	sb.WriteString("\t* DERP latency:\n")
	rids := make([]int, 0, len(dm.Regions))
	for rid := range dm.Regions {
		rids = append(rids, rid)
	}
	sort.Slice(rids, func(i, j int) bool {
		l1, ok1 := r.RegionLatency[rids[i]]
		l2, ok2 := r.RegionLatency[rids[j]]
		if ok1 != ok2 {
			return ok1 // measured regions first
		}
		if !ok1 {
			return rids[i] < rids[j]
		}
		return l1 < l2
	})
	for _, rid := range rids {
		var latency string
		if d, ok := r.RegionLatency[rid]; ok {
			latency = d.Round(time.Millisecond / 10).String()
		}
		region := dm.Regions[rid]
		fmt.Fprintf(&sb, "\t\t- %3s: %-7s (%s)\n", region.RegionCode, latency, region.RegionName)
	}
	return sb.String()
}

func portMappingSummary(r *netcheck.Report) string {
	if !r.AnyPortMappingChecked() {
		return "not checked"
	}
	var got []string
	if r.UPnP.EqualBool(true) {
		got = append(got, "UPnP")
	}
	if r.PMP.EqualBool(true) {
		got = append(got, "NAT-PMP")
	}
	if r.PCP.EqualBool(true) {
		got = append(got, "PCP")
	}
	return strings.Join(got, ", ")
}
