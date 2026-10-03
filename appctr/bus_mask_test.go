package appctr

import (
	"strconv"
	"testing"

	"tailscale.com/ipn"
)

// TestBusWatchMask checks the subscription against the daemon's own checks:
// 1.104 refused the old mask=4095 outright, and the listener never connected.
func TestBusWatchMask(t *testing.T) {
	var m ipn.NotifyWatchOpt
	if err := m.UnmarshalText([]byte(strconv.Itoa(busWatchMask))); err != nil {
		t.Fatalf("the daemon cannot parse the mask: %v", err)
	}
	if err := ipn.ValidateNotifyWatchOpt(m); err != nil {
		t.Fatalf("the daemon refuses the mask: %v", err)
	}
	if m&ipn.NotifyInProcessNoDisconnect != 0 {
		t.Fatal("NotifyInProcessNoDisconnect is for in-process watchers only")
	}
	for _, want := range []ipn.NotifyWatchOpt{ipn.NotifyInitialState, ipn.NotifyInitialPrefs, ipn.NotifyInitialHealthState, ipn.NotifyInitialStatus, ipn.NotifyPeerChanges} {
		if m&want == 0 {
			t.Errorf("mask lacks %v", want)
		}
	}
}
