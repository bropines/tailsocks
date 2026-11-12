package appctr

import "testing"

func TestClearStaleSSH(t *testing.T) {
	stateMu.Lock()
	saved := externalSocketPath
	stateMu.Unlock()
	defer func() {
		stateMu.Lock()
		externalSocketPath = saved
		stateMu.Unlock()
	}()
	set := func(path string) {
		stateMu.Lock()
		externalSocketPath = path
		stateMu.Unlock()
	}

	// The app's own daemon: the switch is turned off, whatever the state holds.
	set("")
	prefs := map[string]any{}
	clearStaleSSH(prefs)
	if prefs["RunSSH"] != false || prefs["RunSSHSet"] != true {
		t.Errorf("own daemon: got %v, want RunSSH off and set", prefs)
	}
	// The node switches come after and would not bring it back: the app sends
	// none for SSH outside Root Mode.
	if client := mergeClientPrefs(prefs, `{"ShieldsUp":true,"ShieldsUpSet":true}`); len(client) != 2 || prefs["RunSSH"] != false {
		t.Errorf("after the node switches: %v", prefs)
	}

	// Root Mode's daemon: left to the profile's switch.
	set("/data/x/tailscaled.sock")
	prefs = map[string]any{}
	clearStaleSSH(prefs)
	if len(prefs) != 0 {
		t.Errorf("root daemon: got %v, want nothing", prefs)
	}
	mergeClientPrefs(prefs, `{"RunSSH":true,"RunSSHSet":true}`)
	if prefs["RunSSH"] != true {
		t.Errorf("root daemon: the profile's switch was lost: %v", prefs)
	}
}
