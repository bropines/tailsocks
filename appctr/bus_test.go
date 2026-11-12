package appctr

import (
	"encoding/json"
	"testing"
)

func busNotify(t *testing.T, raw string) *BusNotify {
	t.Helper()
	var n BusNotify
	if err := json.Unmarshal([]byte(raw), &n); err != nil {
		t.Fatalf("notify %s: %v", raw, err)
	}
	return &n
}

func cachedName(name string) string {
	v, ok := nodesCache.Load(name)
	if !ok {
		return ""
	}
	return v.([]string)[0]
}

// TestBusPrunesNodeNames: a peer that is renamed or removed must stop
// resolving under its old names, in the shapes the daemon sends — PeerStatus in
// the initial status (ID is the StableID, NodeID the number), tailcfg.Node in
// PeersChanged (ID is the number), bare numbers in PeersRemoved.
func TestBusPrunesNodeNames(t *testing.T) {
	t.Cleanup(func() { resetBusState(); FlushDNS() })
	FlushDNS()

	applyNotifyLocked(busNotify(t, `{"InitialStatus":{"MagicDNSSuffix":"tail1.ts.net",
		"Self":{"ID":"nSelf","NodeID":1,"DNSName":"phone.tail1.ts.net.","TailscaleIPs":["100.64.0.1"]},
		"Peer":{
			"k2":{"ID":"nTwo","NodeID":2,"DNSName":"laptop.tail1.ts.net.","TailscaleIPs":["100.64.0.2"]},
			"k3":{"ID":"nThree","NodeID":3,"DNSName":"laptop.tailother.ts.net.","TailscaleIPs":["100.64.0.3"]}}}}`))
	if got := cachedName("laptop.tail1.ts.net"); got != "100.64.0.2" {
		t.Fatalf("seeded peer resolves to %q", got)
	}

	// Renamed: the old names go, the new ones resolve.
	applyNotifyLocked(busNotify(t, `{"PeersChanged":[{"ID":2,"StableID":"nTwo","Name":"desk.tail1.ts.net.","Addresses":["100.64.0.2/32"]}]}`))
	if got := cachedName("desk.tail1.ts.net"); got != "100.64.0.2" {
		t.Errorf("renamed peer resolves to %q", got)
	}
	if got := cachedName("laptop.tail1.ts.net"); got != "" {
		t.Errorf("the old name still resolves to %q", got)
	}
	// The short name is still node 3's, which shares it.
	if got := cachedName("laptop"); got == "" {
		t.Error("a short name another node still has was dropped")
	}

	// Removed.
	applyNotifyLocked(busNotify(t, `{"PeersRemoved":[3,2]}`))
	for _, name := range []string{"laptop", "laptop.tailother.ts.net", "desk", "desk.tail1.ts.net"} {
		if got := cachedName(name); got != "" {
			t.Errorf("removed peer's name %q still resolves to %q", name, got)
		}
	}
	if got := cachedName("phone.tail1.ts.net"); got != "100.64.0.1" {
		t.Errorf("this node resolves to %q", got)
	}

	// A new baseline replaces the cache: whoever left meanwhile is gone.
	applyNotifyLocked(busNotify(t, `{"InitialStatus":{"Self":{"ID":"nSelf","NodeID":1,"DNSName":"tablet.tail1.ts.net.","TailscaleIPs":["100.64.0.1"]}}}`))
	if got := cachedName("phone"); got != "" {
		t.Errorf("a name from before the baseline still resolves to %q", got)
	}
	if got := cachedName("tablet"); got != "100.64.0.1" {
		t.Errorf("the baseline's node resolves to %q", got)
	}
}

// TestBusClearsFinishedLoginURL: the link of a login that is over must not
// stay in the snapshot, where it makes PatchPrefsJSON skip every change.
func TestBusClearsFinishedLoginURL(t *testing.T) {
	t.Cleanup(resetBusState)
	authURL := func() string { return GetBusState().AuthURL }

	resetBusState()
	applyNotifyLocked(busNotify(t, `{"State":2,"BrowseToURL":"https://login.example/a/1"}`))
	if authURL() == "" {
		t.Fatal("the login link was not kept")
	}
	applyNotifyLocked(busNotify(t, `{"LoginFinished":{}}`))
	if got := authURL(); got != "" {
		t.Errorf("after LoginFinished the link is still %q", got)
	}

	resetBusState()
	applyNotifyLocked(busNotify(t, `{"State":2,"BrowseToURL":"https://login.example/a/2"}`))
	applyNotifyLocked(busNotify(t, `{"State":5}`))
	if got := authURL(); got != "" {
		t.Errorf("after the move to Starting the link is still %q", got)
	}

	// A login asked of a running node: the state is repeated, the link stays.
	applyNotifyLocked(busNotify(t, `{"State":6}`))
	applyNotifyLocked(busNotify(t, `{"BrowseToURL":"https://login.example/a/3"}`))
	applyNotifyLocked(busNotify(t, `{"State":6}`))
	if authURL() == "" {
		t.Error("a repeated Running dropped the link of a login in progress")
	}
	// One message with the transition and a link keeps the link.
	resetBusState()
	applyNotifyLocked(busNotify(t, `{"State":6,"BrowseToURL":"https://login.example/a/4"}`))
	if authURL() == "" {
		t.Error("a link that came with the state was dropped")
	}
}

func TestPrefsPatchFields(t *testing.T) {
	cases := []struct{ payload, want string }{
		{`{"ExitNodeID":"nABC","ExitNodeIDSet":true}`, "ExitNodeID"},
		{`{"WantRunning":true,"WantRunningSet":true,"CorpDNS":false,"CorpDNSSet":true}`, "CorpDNS,WantRunning"},
		{`{"OfferSet":true}`, "OfferSet"},
		{`not json`, "unreadable payload"},
	}
	for _, c := range cases {
		if got := prefsPatchFields(c.payload); got != c.want {
			t.Errorf("prefsPatchFields(%s) = %q, want %q", c.payload, got, c.want)
		}
	}
}
