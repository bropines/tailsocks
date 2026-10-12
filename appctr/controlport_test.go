package appctr

import (
	"slices"
	"testing"
)

// Port 443 only is the default and sets upstream's knob; asking for port 80
// first leaves the environment alone, which is upstream's behaviour.
func TestControlPortEnv(t *testing.T) {
	if env := controlPortEnv(false); !slices.Equal(env, []string{"TS_FORCE_NOISE_443=true"}) {
		t.Errorf("port 443 only: env = %q", env)
	}
	if env := controlPortEnv(true); len(env) != 0 {
		t.Errorf("port 80 first: env = %q, want nothing", env)
	}
}
