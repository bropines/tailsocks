package appctr

import "testing"

func TestTcParseMappings(t *testing.T) {
	good := map[string][]tcMapping{
		"8080":                     {{listen: "127.0.0.1:8080", port: 8080}},
		"18080:8080":               {{listen: "127.0.0.1:18080", port: 8080}},
		"0:8080":                   {{listen: "127.0.0.1:0", port: 8080}},
		"8080, 9000:22\n443":       {{listen: "127.0.0.1:8080", port: 8080}, {listen: "127.0.0.1:9000", port: 22}, {listen: "127.0.0.1:443", port: 443}},
		"3001:192.168.1.5:3001":    {{listen: "127.0.0.1:3001"}},
		"5555:[fd7a:115c::1]:5555": {{listen: "127.0.0.1:5555"}},
	}
	for in, want := range good {
		got, err := tcParseMappings(in)
		if err != nil {
			t.Errorf("%q: %v", in, err)
			continue
		}
		if len(got) != len(want) {
			t.Errorf("%q: %d mappings, want %d", in, len(got), len(want))
			continue
		}
		for i := range got {
			if got[i].listen != want[i].listen || (want[i].port != 0 && got[i].port != want[i].port) {
				t.Errorf("%q[%d] = %+v, want %+v", in, i, got[i], want[i])
			}
			if want[i].port == 0 && !got[i].target.IsValid() {
				t.Errorf("%q[%d]: no target address", in, i)
			}
		}
	}
	for _, in := range []string{"", "  ", "abc", "0", "70000", "8080:", ":8080", "1:host.example:22", "1:192.168.1.5", "1:192.168.1.5:0"} {
		if _, err := tcParseMappings(in); err == nil {
			t.Errorf("%q: accepted", in)
		}
	}
}

func TestTailcatKeys(t *testing.T) {
	priv := TailcatGenerateClientKey()
	pub := TailcatPublicKey(priv)
	if len(pub) < len("nodekey:") || pub[:8] != "nodekey:" {
		t.Fatalf("public key of %q = %q", priv, pub)
	}
	if TailcatPublicKey("nonsense") != "" {
		t.Error("a malformed key gave a public key")
	}
}
