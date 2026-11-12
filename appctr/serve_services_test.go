package appctr

import (
	"slices"
	"testing"
)

func TestServeServiceNames(t *testing.T) {
	got := serveServiceNames([]byte(`{"etag":"x","TCP":{"443":{"HTTPS":true}},"Services":{"svc:web":{},"svc:db":{},"bogus":{}}}`))
	if want := []string{"svc:db", "svc:web"}; !slices.Equal(got, want) {
		t.Errorf("got %v, want %v", got, want)
	}
	if got := serveServiceNames([]byte(`{}`)); len(got) != 0 {
		t.Errorf("empty config: got %v", got)
	}
}

func TestAdvertiseAfter(t *testing.T) {
	for _, c := range []struct {
		name                      string
		advertised, before, after []string
		want                      []string
		changed                   bool
	}{
		{"created", nil, nil, []string{"svc:web"}, []string{"svc:web"}, true},
		{"removed", []string{"svc:web", "svc:db"}, []string{"svc:web", "svc:db"}, []string{"svc:db"}, []string{"svc:db"}, true},
		{"last one removed", []string{"svc:web"}, []string{"svc:web"}, nil, nil, true},
		{"rule edited", []string{"svc:web"}, []string{"svc:web"}, []string{"svc:web"}, []string{"svc:web"}, false},
		// `tailscale serve drain` took it out; a later write of other rules leaves it out.
		{"drained stays drained", nil, []string{"svc:web"}, []string{"svc:web"}, nil, false},
		{"drained, another created", nil, []string{"svc:web"}, []string{"svc:web", "svc:db"}, []string{"svc:db"}, true},
		{"drained and removed", nil, []string{"svc:web"}, nil, nil, false},
		// Paused in the app (rules out of the config) and resumed.
		{"created again", nil, nil, []string{"svc:web"}, []string{"svc:web"}, true},
		{"advertised without rules is kept", []string{"svc:x"}, nil, []string{"svc:web"}, []string{"svc:x", "svc:web"}, true},
		{"already advertised", []string{"svc:web"}, nil, []string{"svc:web"}, []string{"svc:web"}, false},
	} {
		got, changed := advertiseAfter(c.advertised, c.before, c.after)
		if !slices.Equal(got, c.want) || changed != c.changed {
			t.Errorf("%s: got %v (changed %v), want %v (changed %v)", c.name, got, changed, c.want, c.changed)
		}
	}
}
