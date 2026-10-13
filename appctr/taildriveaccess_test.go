package appctr

import "testing"

// Shapes taken from /localapi/v0/status of tailscaled 1.104 (ipnstate.Status):
// Self.InNetworkMap is "the daemon holds a network map", Self.CapMap the
// node attributes of this node.
func TestTaildriveAccessFromStatus(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status string
		want   taildriveAccessReport
	}{
		{
			name:   "both attributes",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"drive:share":null,"drive:access":null,"https":null}}}`,
			want:   taildriveAccessReport{Known: true, CanShare: true, CanAccess: true},
		},
		{
			name:   "may share, may not open",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"drive:share":null}}}`,
			want:   taildriveAccessReport{Known: true, CanShare: true},
		},
		{
			name:   "may open, may not share",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"drive:access":null}}}`,
			want:   taildriveAccessReport{Known: true, CanAccess: true},
		},
		{
			// What the author's second device reported before the policy was changed:
			// the file-sharing and netmap-cache attributes, nothing of Taildrive.
			name:   "neither: the policy gives the device no Taildrive",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"cache-network-maps":null,"https://tailscale.com/cap/file-sharing":null}}}`,
			want:   taildriveAccessReport{Known: true},
		},
		{
			name:   "no capabilities at all",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true}}`,
			want:   taildriveAccessReport{Known: true},
		},
		{
			// A map from the saved copy while control is silent still carries the attributes.
			name:   "started from the saved map, control silent",
			status: `{"BackendState":"Running","Self":{"Online":false,"InNetworkMap":true,"CapMap":{"drive:share":null,"drive:access":null}}}`,
			want:   taildriveAccessReport{Known: true, CanShare: true, CanAccess: true},
		},
		{
			// The names are exact: a look-alike is not the attribute.
			name:   "a name that only resembles it",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"drive":null,"drive:sharer":null,"tailscale.com/cap/drive":null}}}`,
			want:   taildriveAccessReport{Known: true},
		},
		{
			name:   "no network map yet",
			status: `{"BackendState":"NoState","Self":{"Online":false,"InNetworkMap":false}}`,
			want:   taildriveAccessReport{},
		},
		{
			name:   "no self",
			status: `{"BackendState":"NeedsLogin"}`,
			want:   taildriveAccessReport{},
		},
	} {
		got, err := taildriveAccessFromStatus([]byte(tc.status))
		if err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		if got != tc.want {
			t.Errorf("%s: got %+v, want %+v", tc.name, got, tc.want)
		}
	}
	if _, err := taildriveAccessFromStatus([]byte("not json")); err == nil {
		t.Error("garbage: no error")
	}
}
