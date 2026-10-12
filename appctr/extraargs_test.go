package appctr

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"tailscale.com/ipn"
)

func TestTokenizeArgs(t *testing.T) {
	tests := []struct {
		name     string
		in       string
		want     []string
		problems int
	}{
		{"empty", "", nil, 0},
		{"spaces only", "   \t\n ", nil, 0},
		{"simple", "--ssh --shields-up", []string{"--ssh", "--shields-up"}, 0},
		{"repeated whitespace", "  --a   \t --b\n--c  ", []string{"--a", "--b", "--c"}, 0},
		{"equals", "--hostname=phone", []string{"--hostname=phone"}, 0},
		{"double quotes", `--hostname="my phone"`, []string{"--hostname=my phone"}, 0},
		{"single quotes", `--hostname='my phone'`, []string{"--hostname=my phone"}, 0},
		{"quoted whole token", `"--hostname=my phone"`, []string{"--hostname=my phone"}, 0},
		{"empty quoted value", `--hostname=""`, []string{"--hostname="}, 0},
		{"quotes inside single quotes", `--x='a"b'`, []string{`--x=a"b`}, 0},
		{"backslash escape", `--x=a\ b`, []string{"--x=a b"}, 0},
		{"escaped quote", `--x="a\"b"`, []string{`--x=a"b`}, 0},
		{"no escapes in single quotes", `--x='a\b'`, []string{`--x=a\b`}, 0},
		{"unterminated quote", `--hostname="my phone`, []string{"--hostname=my phone"}, 1},
		{"trailing backslash", `--ssh \`, []string{"--ssh"}, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, problems := tokenizeArgs(tt.in)
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("tokenizeArgs(%q) = %q, want %q", tt.in, got, tt.want)
			}
			if len(problems) != tt.problems {
				t.Errorf("tokenizeArgs(%q) problems = %q, want %d", tt.in, problems, tt.problems)
			}
		})
	}
}

// collect runs extraArgPrefs and returns the prefs plus every warning text.
func collect(t *testing.T, raw string) (map[string]any, []string) {
	t.Helper()
	var warnings []string
	prefs := extraArgPrefs(raw, func(format string, args ...any) {
		warnings = append(warnings, fmt.Sprintf(format, args...))
	})
	return prefs, warnings
}

func TestExtraArgPrefsBooleans(t *testing.T) {
	tests := []struct {
		in    string
		field string
		want  bool
	}{
		{"--shields-up", "ShieldsUp", true},
		{"--shields-up=true", "ShieldsUp", true},
		{"--shields-up=false", "ShieldsUp", false},
		{"--shields-up false", "ShieldsUp", false},
		{"--shields-up 1", "ShieldsUp", true},
		{"--no-shields-up", "ShieldsUp", false},
		{"--accept-routes=false", "RouteAll", false},
		{"--no-accept-routes", "RouteAll", false},
		{"--accept-dns=false", "CorpDNS", false},
		{"--exit-node-allow-lan-access", "ExitNodeAllowLANAccess", true},
		{"--report-posture=true", "PostureChecking", true},
		{"-shields-up", "ShieldsUp", true}, // single dash, like Go's flag package
	}
	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			prefs, warnings := collect(t, tt.in)
			if got := prefs[tt.field]; got != tt.want {
				t.Errorf("extraArgPrefs(%q)[%s] = %v, want %v (warnings: %q)", tt.in, tt.field, got, tt.want, warnings)
			}
			if prefs[tt.field+"Set"] != true {
				t.Errorf("extraArgPrefs(%q) did not set %sSet", tt.in, tt.field)
			}
		})
	}
}

func TestExtraArgPrefsStrings(t *testing.T) {
	prefs, warnings := collect(t, `--hostname my-phone --login-server=https://hs.example.com`)
	if len(warnings) != 0 {
		t.Fatalf("unexpected warnings: %q", warnings)
	}
	if prefs["Hostname"] != "my-phone" || prefs["HostnameSet"] != true {
		t.Errorf("Hostname = %v", prefs["Hostname"])
	}
	if prefs["ControlURL"] != "https://hs.example.com" || prefs["ControlURLSet"] != true {
		t.Errorf("ControlURL = %v", prefs["ControlURL"])
	}
}

func TestExtraArgPrefsTags(t *testing.T) {
	prefs, warnings := collect(t, `--advertise-tags=eng,tag:server`)
	if len(warnings) != 0 {
		t.Fatalf("unexpected warnings: %q", warnings)
	}
	want := []string{"tag:eng", "tag:server"}
	if !reflect.DeepEqual(prefs["AdvertiseTags"], want) {
		t.Errorf("AdvertiseTags = %v, want %v", prefs["AdvertiseTags"], want)
	}

	// One bad tag drops the whole flag, as upstream does.
	prefs, warnings = collect(t, `--advertise-tags=eng,tag:1bad`)
	if _, ok := prefs["AdvertiseTags"]; ok {
		t.Errorf("AdvertiseTags set despite an invalid tag: %v", prefs["AdvertiseTags"])
	}
	if len(warnings) == 0 {
		t.Errorf("invalid tag produced no warning")
	}
}

func TestExtraArgPrefsRoutes(t *testing.T) {
	prefs, warnings := collect(t, `--advertise-routes=10.0.0.0/8,192.168.1.0/24`)
	if len(warnings) != 0 {
		t.Fatalf("unexpected warnings: %q", warnings)
	}
	want := []string{"10.0.0.0/8", "192.168.1.0/24"}
	if !reflect.DeepEqual(prefs["AdvertiseRoutes"], want) {
		t.Errorf("AdvertiseRoutes = %v, want %v", prefs["AdvertiseRoutes"], want)
	}

	// --advertise-exit-node folds the two default routes into the same pref.
	prefs, _ = collect(t, `--advertise-exit-node`)
	want = []string{"0.0.0.0/0", "::/0"}
	if !reflect.DeepEqual(prefs["AdvertiseRoutes"], want) {
		t.Errorf("AdvertiseRoutes = %v, want %v", prefs["AdvertiseRoutes"], want)
	}

	// A lone --advertise-exit-node=false must not clear the app's routes.
	prefs, warnings = collect(t, `--advertise-exit-node=false`)
	if _, ok := prefs["AdvertiseRoutes"]; ok {
		t.Errorf("AdvertiseRoutes written by a lone --advertise-exit-node=false: %v", prefs["AdvertiseRoutes"])
	}
	if len(warnings) == 0 {
		t.Errorf("a lone --advertise-exit-node=false produced no warning")
	}

	// A malformed prefix is reported and leaves the pref alone.
	prefs, warnings = collect(t, `--advertise-routes=10.0.0.1/8`)
	if _, ok := prefs["AdvertiseRoutes"]; ok {
		t.Errorf("AdvertiseRoutes set despite a non-masked prefix")
	}
	if len(warnings) == 0 {
		t.Errorf("non-masked prefix produced no warning")
	}
}

func TestExtraArgPrefsReportsWhatItCannotDo(t *testing.T) {
	tests := []struct {
		in       string
		wantWord string
	}{
		{"--netfilter-mode=off", "Linux-only"},
		{"--exit-node=100.64.0.1", "exit node"},
		{"--frobnicate", "unknown flag"},
		{"--shields-up=maybe", "not a boolean"},
		{"--hostname", "needs a value"},
		{"--hostname --ssh", "needs a value"},
		{`--hostname="bad host name!"`, "not a valid hostname"},
		{"bare-word", "is not a flag"},
		{"--no-shields-up=true", "does not take a value"},
	}
	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			_, warnings := collect(t, tt.in)
			joined := strings.Join(warnings, "\n")
			if !strings.Contains(joined, tt.wantWord) {
				t.Errorf("extraArgPrefs(%q) warnings = %q, want one containing %q", tt.in, warnings, tt.wantWord)
			}
		})
	}
}

func TestExtraArgPrefsOneBadFlagDoesNotAbortTheRest(t *testing.T) {
	prefs, warnings := collect(t, `--frobnicate --shields-up --netfilter-mode=off --accept-dns=false`)
	if prefs["ShieldsUp"] != true {
		t.Errorf("ShieldsUp not applied alongside refused flags: %v", prefs)
	}
	if prefs["CorpDNS"] != false || prefs["CorpDNSSet"] != true {
		t.Errorf("CorpDNS not applied alongside refused flags: %v", prefs)
	}
	if len(warnings) != 2 {
		t.Errorf("warnings = %q, want 2", warnings)
	}
}

func TestExtraArgPrefsEmptyAndNilSafe(t *testing.T) {
	if got := extraArgPrefs("", nil); got != nil {
		t.Errorf("extraArgPrefs(\"\") = %v, want nil", got)
	}
	if got := extraArgPrefs("   ", nil); got != nil {
		t.Errorf("extraArgPrefs(spaces) = %v, want nil", got)
	}
	// A nil warn function must not panic, and a line with nothing usable in it
	// must not produce an empty PATCH body.
	if got := extraArgPrefs(`--frobnicate "unterminated`, nil); got != nil {
		t.Errorf("extraArgPrefs(garbage) = %v, want nil", got)
	}
}

func TestApplyExtraArgsOverridesAppKeys(t *testing.T) {
	prefs := map[string]any{"RouteAll": false, "RouteAllSet": true}
	extra := applyExtraArgs(prefs, "--accept-routes")
	if prefs["RouteAll"] != true {
		t.Errorf("extra args did not override the app's own key: %v", prefs)
	}
	if extra["RouteAll"] != true {
		t.Errorf("applyExtraArgs did not report what it added: %v", extra)
	}
	if got := applyExtraArgs(prefs, ""); got != nil {
		t.Errorf("applyExtraArgs with no args = %v, want nil", got)
	}
}

func TestExtraArgPrefsAppConnector(t *testing.T) {
	for _, tt := range []struct {
		in   string
		want bool
	}{
		{"--advertise-connector", true},
		{"--advertise-connector=false", false},
		{"--no-advertise-connector", false},
	} {
		prefs, warnings := collect(t, tt.in)
		if len(warnings) != 0 {
			t.Fatalf("%s: unexpected warnings: %q", tt.in, warnings)
		}
		want := map[string]any{"Advertise": tt.want}
		if !reflect.DeepEqual(prefs["AppConnector"], want) || prefs["AppConnectorSet"] != true {
			t.Errorf("%s: AppConnector = %v (set %v), want %v", tt.in, prefs["AppConnector"], prefs["AppConnectorSet"], want)
		}
	}
}

func TestExtraArgPrefsRelayServer(t *testing.T) {
	prefs, warnings := collect(t, `--relay-server-port=40000 --relay-server-static-endpoints=203.0.113.7:40000,[2001:db8::1]:40000,203.0.113.7:40000`)
	if len(warnings) != 0 {
		t.Fatalf("unexpected warnings: %q", warnings)
	}
	if prefs["RelayServerPort"] != uint16(40000) || prefs["RelayServerPortSet"] != true {
		t.Errorf("RelayServerPort = %#v", prefs["RelayServerPort"])
	}
	wantEps := []string{"203.0.113.7:40000", "[2001:db8::1]:40000"}
	if !reflect.DeepEqual(prefs["RelayServerStaticEndpoints"], wantEps) {
		t.Errorf("RelayServerStaticEndpoints = %v, want %v", prefs["RelayServerStaticEndpoints"], wantEps)
	}

	// Zero asks for any free port; an empty value turns the relay off.
	prefs, _ = collect(t, `--relay-server-port 0`)
	if prefs["RelayServerPort"] != uint16(0) {
		t.Errorf("--relay-server-port 0: RelayServerPort = %#v", prefs["RelayServerPort"])
	}
	prefs, _ = collect(t, `--relay-server-port=`)
	if v, ok := prefs["RelayServerPort"]; !ok || v != nil || prefs["RelayServerPortSet"] != true {
		t.Errorf("--relay-server-port=: RelayServerPort = %#v (present %v), want an explicit nil", v, ok)
	}
	if got := ExtraArgsPrefsJSON(`--relay-server-port=`); got != `{"RelayServerPort":null,"RelayServerPortSet":true}` {
		t.Errorf("ExtraArgsPrefsJSON = %s", got)
	}

	for _, bad := range []string{`--relay-server-port=70000`, `--relay-server-port=abc`, `--relay-server-static-endpoints=203.0.113.7`} {
		prefs, warnings := collect(t, bad)
		if len(prefs) != 0 || len(warnings) == 0 {
			t.Errorf("%s: prefs %v, warnings %q; want nothing applied and a warning", bad, prefs, warnings)
		}
	}
}

func TestExtraArgsPrefsJSON(t *testing.T) {
	if got := ExtraArgsPrefsJSON(""); got != "{}" {
		t.Errorf("ExtraArgsPrefsJSON(\"\") = %s", got)
	}
	if got := ExtraArgsPrefsJSON("--frobnicate"); got != "{}" {
		t.Errorf("ExtraArgsPrefsJSON(unknown) = %s", got)
	}
	if got := ExtraArgsPrefsJSON("--shields-up --report-posture=false"); got != `{"PostureChecking":false,"PostureCheckingSet":true,"ShieldsUp":true,"ShieldsUpSet":true}` {
		t.Errorf("ExtraArgsPrefsJSON = %s", got)
	}
}

func TestMergeClientPrefs(t *testing.T) {
	prefs := map[string]any{"RouteAll": true, "RouteAllSet": true}
	client := mergeClientPrefs(prefs, `{"ShieldsUp":true,"ShieldsUpSet":true,"RelayServerPort":null,"RelayServerPortSet":true}`)
	if prefs["ShieldsUp"] != true || prefs["RouteAll"] != true || len(client) != 4 {
		t.Errorf("merged prefs = %v, client = %v", prefs, client)
	}
	if v, ok := prefs["RelayServerPort"]; !ok || v != nil {
		t.Errorf("an explicit null was lost: %#v", v)
	}
	// The extra arguments come after the switches and win.
	applyExtraArgs(prefs, "--no-shields-up")
	if prefs["ShieldsUp"] != false {
		t.Errorf("extra arguments did not override a switch: %v", prefs)
	}
	for _, raw := range []string{"", "  ", "not json", "[1,2]"} {
		before := len(prefs)
		if got := mergeClientPrefs(prefs, raw); got != nil || len(prefs) != before {
			t.Errorf("mergeClientPrefs(%q) = %v, prefs grew to %d", raw, got, len(prefs))
		}
	}
}

// The names are only strings here; this holds them to the daemon's own type, so
// a field upstream renames fails a test instead of a PATCH.
func TestExtraArgPrefsDecodeAsMaskedPrefs(t *testing.T) {
	prefs, warnings := collect(t, `--shields-up --exit-node-allow-lan-access --report-posture --advertise-connector `+
		`--relay-server-port=40000 --relay-server-static-endpoints=203.0.113.7:40000`)
	if len(warnings) != 0 {
		t.Fatalf("unexpected warnings: %q", warnings)
	}
	b, err := json.Marshal(prefs)
	if err != nil {
		t.Fatal(err)
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	var mp ipn.MaskedPrefs
	if err := dec.Decode(&mp); err != nil {
		t.Fatalf("%s does not decode as ipn.MaskedPrefs: %v", b, err)
	}
	if !mp.ShieldsUpSet || !mp.ShieldsUp || !mp.ExitNodeAllowLANAccessSet || !mp.ExitNodeAllowLANAccess ||
		!mp.PostureCheckingSet || !mp.PostureChecking || !mp.AppConnectorSet || !mp.AppConnector.Advertise ||
		!mp.RelayServerPortSet || mp.RelayServerPort == nil || *mp.RelayServerPort != 40000 ||
		!mp.RelayServerStaticEndpointsSet || len(mp.RelayServerStaticEndpoints) != 1 {
		t.Errorf("decoded %+v from %s", mp, b)
	}

	// Turning the relay off is an explicit null, which must clear the port.
	var off ipn.MaskedPrefs
	if err := json.Unmarshal([]byte(ExtraArgsPrefsJSON("--relay-server-port=")), &off); err != nil {
		t.Fatal(err)
	}
	if !off.RelayServerPortSet || off.RelayServerPort != nil {
		t.Errorf("relay off decoded as set=%v port=%v", off.RelayServerPortSet, off.RelayServerPort)
	}
}
