package appctr

import (
	"encoding/json"
)

// The node attributes with which the coordination server lets a node use
// Taildrive (tailcfg/nodecap TaildriveShare and TaildriveAccess). Without
// drive:share the daemon's LocalAPI refuses every share call with 403
// (ipn/localapi/localapi_drive.go) and the PeerAPI answers 404; without
// drive:access it closes the connection to 100.100.100.100:8080 without a
// word (ipn/ipnlocal/netstack.go handleDriveConn) and lists no remotes.
const (
	driveShareCap  = "drive:share"
	driveAccessCap = "drive:access"
)

// taildriveAccessReport is what the Taildrive screen shows beside its switches.
type taildriveAccessReport struct {
	// Known is false when there is no daemon or it holds no network map yet,
	// so nothing can be said about the server's decision.
	Known bool `json:"known"`
	// CanShare: the self node carries drive:share, so the daemon takes its
	// shares and other devices may read them (given a grant).
	CanShare bool `json:"canShare"`
	// CanAccess: the self node carries drive:access, so the daemon answers on
	// 100.100.100.100:8080 and lists the other devices' shares.
	CanAccess bool `json:"canAccess"`
}

// GetTaildriveAccessJSON reports, from the daemon's own status, whether the
// coordination server lets this node share folders and open the folders of
// other nodes. "" when the status cannot be read.
func GetTaildriveAccessJSON() string {
	st, err := GetStatusJSON(false)
	if err != nil || st == "" {
		return ""
	}
	r, err := taildriveAccessFromStatus([]byte(st))
	if err != nil {
		return ""
	}
	out, _ := json.Marshal(r)
	return string(out)
}

func taildriveAccessFromStatus(status []byte) (taildriveAccessReport, error) {
	var st struct {
		Self *struct {
			InNetworkMap bool
			CapMap       map[string]json.RawMessage
		}
	}
	if err := json.Unmarshal(status, &st); err != nil {
		return taildriveAccessReport{}, err
	}
	if st.Self == nil || !st.Self.InNetworkMap {
		return taildriveAccessReport{}, nil
	}
	_, share := st.Self.CapMap[driveShareCap]
	_, access := st.Self.CapMap[driveAccessCap]
	return taildriveAccessReport{Known: true, CanShare: share, CanAccess: access}, nil
}
