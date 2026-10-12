package appctr

import (
	"slices"
	"testing"
)

// Off sets the upstream knob to false; on leaves the environment alone so
// whatever upstream defaults to (and control grants) applies.
func TestNetmapCacheEnv(t *testing.T) {
	if env := netmapCacheEnv(false); len(env) != 0 {
		t.Errorf("cache on: env = %q, want nothing", env)
	}
	if env := netmapCacheEnv(true); !slices.Equal(env, []string{"TS_USE_CACHED_NETMAP=false"}) {
		t.Errorf("cache off: env = %q", env)
	}
}

// Shapes taken from /localapi/v0/status of tailscaled 1.104 (ipnstate.Status):
// Self.Online is "in a map poll", Self.CapMap the node attributes.
func TestNetmapCacheFromStatus(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status string
		want   netmapCacheReport
	}{
		{
			name:   "granted, live",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"cache-network-maps":null,"https":null}}}`,
			want:   netmapCacheReport{Known: true, Granted: true},
		},
		{
			name:   "granted, started from the saved map, control silent",
			status: `{"BackendState":"Running","Self":{"Online":false,"InNetworkMap":true,"CapMap":{"cache-network-maps":null}}}`,
			want:   netmapCacheReport{Known: true, Granted: true, WithoutControl: true},
		},
		{
			name:   "withheld by the server",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true,"CapMap":{"funnel":null}}}`,
			want:   netmapCacheReport{Known: true},
		},
		{
			name:   "no capabilities at all",
			status: `{"BackendState":"Running","Self":{"Online":true,"InNetworkMap":true}}`,
			want:   netmapCacheReport{Known: true},
		},
		{
			name:   "no network map yet",
			status: `{"BackendState":"NoState","Self":{"Online":false,"InNetworkMap":false}}`,
			want:   netmapCacheReport{},
		},
		{
			name:   "no self",
			status: `{"BackendState":"NeedsLogin"}`,
			want:   netmapCacheReport{},
		},
	} {
		got, err := netmapCacheFromStatus([]byte(tc.status))
		if err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		if got != tc.want {
			t.Errorf("%s: got %+v, want %+v", tc.name, got, tc.want)
		}
	}
	if _, err := netmapCacheFromStatus([]byte("not json")); err == nil {
		t.Error("garbage: no error")
	}
}
