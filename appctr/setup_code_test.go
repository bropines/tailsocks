package appctr

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"errors"
	"io"
	"net/netip"
	"slices"
	"strings"
	"testing"
)

// A key in the shape of Tailscale's, and a control server of one's own.
const (
	testSetupKey    = "tskey-auth-kTESTtest1CNTRL-AbCdEfGhIjKlMnOpQrStUvWxYz012345"
	testSetupServer = "https://headscale.example.com"
)

// testRelayRegion is a relay region as the relay map numbers them.
const testRelayRegion = 303

// testCode is a code as a television would draw it, for n addresses, with or
// without a relay.
func testCode(t testing.TB, n int, relay uint16) (text string, raw []byte) {
	t.Helper()
	priv, err := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	secret := make([]byte, setupSecretLen)
	rand.Read(secret)
	all := addrs("192.168.1.50", "10.0.0.2", "169.254.10.20")
	return encodeSetupCode(priv.PublicKey().Bytes(), secret, relay, all[:n])
}

// encodeSetupCodeV1 is the first version's code, which a television no longer
// draws and a phone still reads: the port spelt out where the relay's region
// is now, and at least one address.
func encodeSetupCodeV1(pub, secret []byte, port uint16, addrs []netip.Addr) (text string, raw []byte) {
	text, raw = encodeSetupCode(pub, secret, port, addrs)
	return setupPrefixV1 + strings.TrimPrefix(text, setupPrefix), raw
}

func TestSetupCodeRoundTrip(t *testing.T) {
	all := addrs("192.168.1.50", "10.0.0.2", "169.254.10.20")
	for _, relay := range []uint16{0, 1, testRelayRegion, 0xffff} {
		for n := 0; n <= setupMaxAddrs; n++ {
			if n == 0 && relay == 0 {
				continue
			}
			text, raw := testCode(t, n, relay)
			c, err := parseSetupCode(text)
			if err != nil {
				t.Fatalf("%d addresses, relay %d: %v", n, relay, err)
			}
			if c.version != 2 || !bytes.Equal(c.raw, raw) || !bytes.Equal(c.key, raw[:setupKeyLen]) || c.relay != relay || !slices.Equal(c.addrs, all[:n]) {
				t.Errorf("%d addresses, relay %d: read back as %+v", n, relay, c)
			}
			if !bytes.Equal(c.secret(), raw[setupKeyLen:setupKeyLen+setupSecretLen]) {
				t.Errorf("%d addresses, relay %d: another secret", n, relay)
			}
			// The port is not in the code: it follows from the key, and is
			// there only when an address is.
			want := setupPortOf(c.key)
			if n == 0 {
				want = 0
			}
			if c.port != want || (n > 0 && (c.port < setupPortBase || int(c.port) >= setupPortBase+setupPortCount)) {
				t.Errorf("%d addresses, relay %d: port %d", n, relay, c.port)
			}
			for _, variant := range []string{strings.ToLower(text), "  " + text + "\r\n", "tsp2:" + text[5:]} {
				if again, err := parseSetupCode(variant); err != nil || !bytes.Equal(again.raw, raw) {
					t.Errorf("%q: %v", variant, err)
				}
			}
		}
	}
	// A key that starts with zero bytes keeps its length.
	text, raw := encodeSetupCode(make([]byte, setupKeyLen), make([]byte, setupSecretLen), 0, addrs("10.0.0.1"))
	if c, err := parseSetupCode(text); err != nil || !bytes.Equal(c.raw, raw) || len(text) != len(setupPrefix)+setupDigits[1] {
		t.Errorf("leading zeros: %q, %v", text, err)
	}
}

// A phone still reads the code an earlier build drew: the port in it, no relay.
func TestSetupCodeV1(t *testing.T) {
	priv, _ := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	pub := priv.PublicKey().Bytes()
	secret := bytes.Repeat([]byte{7}, setupSecretLen)
	all := addrs("192.168.1.50", "10.0.0.2", "169.254.10.20")
	for n := 1; n <= setupMaxAddrs; n++ {
		text, raw := encodeSetupCodeV1(pub, secret, 41234, all[:n])
		c, err := parseSetupCode(text)
		if err != nil {
			t.Fatalf("%d addresses: %v", n, err)
		}
		if c.version != 1 || c.port != 41234 || c.relay != 0 || !bytes.Equal(c.raw, raw) || !slices.Equal(c.addrs, all[:n]) {
			t.Errorf("%d addresses: read back as %+v", n, c)
		}
		// The same bytes under the other prefix are another code: the two
		// bytes after the secret mean something else, and so do the keys.
		two, err := parseSetupCode(setupPrefix + text[len(setupPrefixV1):])
		if err != nil || two.version != 2 || two.relay != 41234 || two.port != setupPortOf(pub) {
			t.Errorf("%d addresses, read as TSP2: %+v, %v", n, two, err)
		}
		if bytes.Equal(setupInfo(c.version, c.raw), setupInfo(two.version, two.raw)) {
			t.Error("the two versions seal under the same info")
		}
		// And what a phone seals for it opens as that build opened it: under
		// the first version's label, with everything the code said.
		plain, _ := encodeSetupBundle(setupBundle{key: []byte(testSetupKey)})
		request, phone, err := sealSetupRequest(c, plain)
		if err != nil {
			t.Fatal(err)
		}
		opened, tv, err := openSetupRequest(priv, append([]byte("TailSocks setup v1\x00"), raw...), request[:setupHeaderLen], request[setupHeaderLen:])
		if err != nil || !bytes.Equal(opened, plain) || tv.code != phone.code {
			t.Errorf("%d addresses: a first-version television would not open it: %v", n, err)
		}
	}
	for name, text := range map[string]string{
		"no address":        setupPrefixV1 + strings.Repeat("1", setupDigits[0]),
		"port 0":            first(encodeSetupCodeV1(pub, secret, 0, all[:1])),
		"a well-known port": first(encodeSetupCodeV1(pub, secret, 443, all[:1])),
		"port 1023":         first(encodeSetupCodeV1(pub, secret, 1023, all[:1])),
		"a public address":  first(encodeSetupCodeV1(pub, secret, 41234, addrs("203.0.113.7"))),
	} {
		if c, err := parseSetupCode(text); err == nil {
			t.Errorf("%s: read as %v port %d", name, c.addrs, c.port)
		}
	}
	if _, err := parseSetupCode(first(encodeSetupCodeV1(pub, secret, 1024, all[:1]))); err != nil {
		t.Errorf("port 1024: %v", err)
	}
}

func first(text string, _ []byte) string { return text }

// The port of a session is spread over the dynamic range, and one key's is
// not another's.
func TestSetupPortOf(t *testing.T) {
	seen := map[uint16]bool{}
	for i := range 2000 {
		pub := make([]byte, setupKeyLen)
		pub[0], pub[1] = byte(i), byte(i>>8)
		p := setupPortOf(pub)
		if p < setupPortBase || p < setupMinPort {
			t.Fatalf("key %d: port %d", i, p)
		}
		seen[p] = true
	}
	if len(seen) < 1800 {
		t.Errorf("2000 keys share %d ports", len(seen))
	}
	if setupPortOf(make([]byte, setupKeyLen)) != setupPortOf(make([]byte, setupKeyLen)) {
		t.Error("one key, two ports")
	}
}

// qrAlnumBits is what a QR code of versions 1 to 9 spends on n characters in
// its alphanumeric mode: mode, count, eleven bits a pair and six for an odd
// one. qrDataBits is what a version holds at a level of error correction
// (data codewords × 8, ISO/IEC 18004 table 7).
func qrAlnumBits(n int) int { return 4 + 9 + 11*(n/2) + 6*(n%2) }

var qrDataBits = map[string]int{
	"3-L": 55 * 8, "3-M": 44 * 8,
	"4-L": 80 * 8, "4-M": 64 * 8, "4-Q": 48 * 8,
	"5-L": 108 * 8, "5-M": 86 * 8, "5-Q": 62 * 8,
	"6-Q": 76 * 8,
}

// The code is drawn on a television and read from the sofa, so its size is
// part of the design: capitals and digits only, and small enough for a
// version 4 code — with error correction M up to one address. The relay does
// not change that: a code that names it is as long as one that does not.
func TestSetupCodeFitsQR(t *testing.T) {
	const alnum = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
	for n, want := range map[int]struct {
		chars   int
		fits    []string
		doesNot []string
	}{
		0: {chars: 83, fits: []string{"4-M", "4-L"}, doesNot: []string{"3-L", "4-Q"}},
		1: {chars: 89, fits: []string{"4-M", "4-L"}, doesNot: []string{"3-L", "4-Q"}},
		2: {chars: 95, fits: []string{"4-L", "5-M"}, doesNot: []string{"3-L", "4-M", "5-Q"}},
		3: {chars: 101, fits: []string{"4-L", "5-M", "6-Q"}, doesNot: []string{"3-L", "4-M", "5-Q"}},
	} {
		for _, relay := range []uint16{0, testRelayRegion} {
			if n == 0 && relay == 0 {
				continue
			}
			text, _ := testCode(t, n, relay)
			if len(text) != want.chars {
				t.Errorf("%d addresses, relay %d: %d characters, want %d", n, relay, len(text), want.chars)
			}
			for _, r := range text {
				if !strings.ContainsRune(alnum, r) {
					t.Fatalf("%d addresses: %q is outside the QR alphanumeric set", n, r)
				}
			}
			bits := qrAlnumBits(len(text))
			for _, v := range want.fits {
				if bits > qrDataBits[v] {
					t.Errorf("%d addresses: %d bits do not fit a version %s code (%d)", n, bits, v, qrDataBits[v])
				}
			}
			for _, v := range want.doesNot {
				if bits <= qrDataBits[v] {
					t.Errorf("%d addresses: %d bits fit a version %s code (%d); the comment in setup.go says otherwise", n, bits, v, qrDataBits[v])
				}
			}
		}
	}
}

func TestParseSetupCodeRefuses(t *testing.T) {
	priv, _ := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	pub := priv.PublicKey().Bytes()
	secret := make([]byte, setupSecretLen)
	code := func(relay uint16, as ...string) string {
		text, _ := encodeSetupCode(pub, secret, relay, addrs(as...))
		return text
	}
	for _, good := range []string{code(0, "192.168.1.50"), code(testRelayRegion), code(testRelayRegion, "192.168.1.50", "10.0.0.2", "169.254.1.1")} {
		if _, err := parseSetupCode(good); err != nil {
			t.Fatalf("a good code: %v", err)
		}
	}
	for name, text := range map[string]string{
		"neither an address nor a relay": code(0),
		"a public address":               code(0, "203.0.113.7"),
		"a public address with a relay":  code(testRelayRegion, "203.0.113.7"),
		"a public one among good":        code(0, "192.168.1.50", "8.8.8.8"),
		"the tailnet":                    code(testRelayRegion, "100.101.102.103"),
		"this machine":                   code(0, "127.0.0.1"),
		"no address in particular":       code(0, "0.0.0.0"),
		"broadcast":                      code(0, "255.255.255.255"),
		"multicast":                      code(0, "224.0.0.251"),
		"the same address twice":         code(testRelayRegion, "192.168.1.50", "192.168.1.50"),
		"a digit short":                  code(testRelayRegion)[:len(code(testRelayRegion))-1],
		"a digit long":                   code(testRelayRegion) + "0",
		"more than the bytes hold":       setupPrefix + strings.Repeat("Z", setupDigits[0]),
		"another script":                 "ТSP2:" + code(0, "192.168.1.50")[5:],
		"a folded letter":                "tſp2:" + code(0, "192.168.1.50")[5:],
		"a digit from elsewhere":         code(0, "192.168.1.50")[:30] + "٣" + code(0, "192.168.1.50")[31:],
		"a space inside":                 code(0, "192.168.1.50")[:30] + " " + code(0, "192.168.1.50")[31:],
		"a link":                         "tailsocks://setup?c=" + code(0, "192.168.1.50"),
	} {
		if c, err := parseSetupCode(text); err == nil {
			t.Errorf("%s: read as %v port %d relay %d", name, c.addrs, c.port, c.relay)
		}
	}
	// A code of a version to come is told apart from a damaged one.
	for _, text := range []string{"TSP3:" + code(0, "192.168.1.50")[5:], "tsp10:whatever", "TSP0:1"} {
		if _, err := parseSetupCode(text); !errors.Is(err, errSetupVersion) {
			t.Errorf("%q: %v", text, err)
		}
	}
}

func TestSetupBundleRoundTrip(t *testing.T) {
	for _, b := range []setupBundle{
		{key: []byte("k")},
		{key: []byte(testSetupKey)},
		{key: []byte(testSetupKey), server: testSetupServer, name: "Pixel 9"},
		{key: []byte("tskey-client-kABC123CNTRL-secretsecret?ephemeral=false&preauthorized=true&tags=tag:tv"), server: "http://192.168.1.2:8080", name: "Телефон Маши 📱"},
		{key: bytes.Repeat([]byte("k"), setupMaxKey), server: "https://" + strings.Repeat("a", setupMaxServer-12) + ".com", name: strings.Repeat("я", setupMaxNameBytes/2)},
	} {
		plain, err := encodeSetupBundle(b)
		if err != nil {
			t.Fatalf("%q: %v", b.name, err)
		}
		if len(plain)%setupPadBlock != 0 || len(plain) > setupMaxPlain {
			t.Errorf("%q: %d bytes", b.name, len(plain))
		}
		got, err := parseSetupBundle(plain)
		if err != nil {
			t.Fatalf("%q: %v", b.name, err)
		}
		if !bytes.Equal(got.key, b.key) || got.server != b.server || got.name != setupCleanName(b.name) {
			t.Errorf("read back %q %q %q", got.key, got.server, got.name)
		}
	}
	// What cannot be sealed is not sent.
	for name, b := range map[string]setupBundle{
		"no key":            {},
		"a key too long":    {key: bytes.Repeat([]byte("k"), setupMaxKey+1)},
		"a key with spaces": {key: []byte("a b")},
		"a bad server":      {key: []byte("k"), server: "hs.example.com"},
		"a server too long": {key: []byte("k"), server: "https://" + strings.Repeat("a", setupMaxServer) + ".com"},
		"a name too long":   {key: []byte("k"), name: strings.Repeat("n", setupMaxNameBytes+1)},
	} {
		if _, err := encodeSetupBundle(b); err == nil {
			t.Errorf("%s: sealed", name)
		}
	}
	// The same size for a bare Tailscale key and for one with a server and a
	// name: the length on the wire says nothing about either.
	bare, _ := encodeSetupBundle(setupBundle{key: []byte(testSetupKey)})
	full, _ := encodeSetupBundle(setupBundle{key: []byte(testSetupKey), server: testSetupServer, name: "Pixel 9"})
	if len(bare) != len(full) || len(bare) != setupPadBlock {
		t.Errorf("bundles of %d and %d bytes", len(bare), len(full))
	}
}

// A name goes on the television's screen: what prints, and not much of it.
func TestSetupCleanName(t *testing.T) {
	for in, want := range map[string]string{
		"Pixel 9":                       "Pixel 9",
		"  Pixel 9\n":                   "Pixel 9",
		"Pixel\x00 9\x1b[31m":           "Pixel 9[31m",
		"Телефон":                       "Телефон",
		"evil‮gnp.exe":                  "evilgnp.exe",
		"tab\there":                     "tabhere",
		"zero​width":                    "zerowidth",
		strings.Repeat("a", 100):        strings.Repeat("a", setupMaxNameRunes),
		strings.Repeat("😀", 100):        strings.Repeat("😀", setupMaxNameBytes/4),
		"":                              "",
		"\n\t ":                         "",
		"bad\xffutf8":                   "badutf8",
		strings.Repeat("a", 31) + " b":  strings.Repeat("a", 31),
		strings.Repeat("я", 40) + "end": strings.Repeat("я", setupMaxNameRunes),
	} {
		if got := setupCleanName(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestSetupKeyPrefix(t *testing.T) {
	for key, want := range map[string]string{
		testSetupKey:                   "tskey-auth",
		"tskey-client-kABC-secret":     "tskey-client",
		"hskey-auth-abcdef-0123456789": "hskey-auth",
		"tskey-api-kABC-secret":        "",
		"tskey-auth":                   "",
		"0123456789abcdef0123456789abcdef0123456789abcdef": "",
	} {
		if got := setupKeyPrefix([]byte(key)); got != want {
			t.Errorf("%q: %q, want %q", key, got, want)
		}
	}
}

func TestSetupServerOK(t *testing.T) {
	for _, s := range []string{"", "https://hs.example.com", "http://192.168.1.2:8080", "https://hs.example.com/base/", "https://[fd00::1]:8443", "https://xn--e1afmkfd.example"} {
		if !setupServerOK(s) {
			t.Errorf("%q: refused", s)
		}
	}
	for _, s := range []string{
		"hs.example.com", "//hs.example.com", "ftp://hs.example.com", "https://", "https:///path", "https:hs.example.com",
		"https://user@hs.example.com", "https://user:pw@hs.example.com", "https://hs.example.com?x=1", "https://hs.example.com?",
		"https://hs.example.com#frag", "https://hs.example.com/a b", "https://hs.example.com\n", "https://пример.example",
		"javascript:alert(1)", "https://" + strings.Repeat("a", setupMaxServer) + ".com", "https://hs.example.com:port",
	} {
		if setupServerOK(s) {
			t.Errorf("%q: accepted", s)
		}
	}
}

// The two ends derive the same answers and the same check code from one
// request, and different ones from any other.
func TestSetupExchangeDerivation(t *testing.T) {
	kem := hpke.DHKEM(ecdh.X25519())
	priv, err := kem.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	secret := make([]byte, setupSecretLen)
	rand.Read(secret)
	text, raw := encodeSetupCode(priv.PublicKey().Bytes(), secret, testRelayRegion, addrs("192.168.1.50"))
	code, err := parseSetupCode(text)
	if err != nil {
		t.Fatal(err)
	}
	info := setupInfo(code.version, raw)
	plain, _ := encodeSetupBundle(setupBundle{key: []byte(testSetupKey)})

	request, phone, err := sealSetupRequest(code, plain)
	if err != nil {
		t.Fatal(err)
	}
	if len(request) != setupHeaderLen+setupPadBlock+setupSealOverhead {
		t.Errorf("a request of %d bytes", len(request))
	}
	if bytes.Contains(request, []byte(testSetupKey)) || bytes.Contains(request, secret) || bytes.Contains(request, raw[:setupKeyLen]) {
		t.Error("the request carries the key, the secret or the television's public key in the clear")
	}
	header, sealed, got, err := readSetupRequest(bytes.NewReader(request))
	if err != nil || !got {
		t.Fatal(err)
	}
	opened, tv, err := openSetupRequest(priv, info, header, sealed)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(opened, plain) || tv.code != phone.code || len(tv.code) != 6 {
		t.Fatalf("television %q, phone %q", tv.code, phone.code)
	}
	for _, kind := range []byte{setupSaysHeld, setupSaysTaken, setupSaysUnsupported, setupSaysClosed, setupSaysConfirmed, setupSaysRejected, setupSaysExpired, setupSaysCancelled} {
		reply := tv.reply(kind)
		if got, ok := phone.check(reply, kind); !ok || got != kind || len(reply) != setupReplyLen {
			t.Errorf("answer %#x did not verify", kind)
		}
		if _, ok := phone.check(reply, kind^0x10); ok {
			t.Errorf("answer %#x passed where another kind was due", kind)
		}
		// The kind is under the tag: one answer cannot be turned into another.
		forged := bytes.Clone(reply)
		forged[0] = kind ^ 0x10
		if _, ok := phone.check(forged, forged[0]); ok {
			t.Errorf("answer %#x relabelled as %#x verified", kind, forged[0])
		}
		for i := 1; i < len(reply); i++ {
			broken := bytes.Clone(reply)
			broken[i] ^= 1
			if _, ok := phone.check(broken, kind); ok {
				t.Errorf("answer %#x verified with byte %d changed", kind, i)
			}
		}
		if _, ok := phone.check(reply[:setupReplyLen-1], kind); ok {
			t.Error("a short answer verified")
		}
	}

	// Another request for the same code: other keys, another check code
	// (one time in a million the same; not twice running).
	same := 0
	for range 2 {
		_, other, err := sealSetupRequest(code, plain)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := other.check(tv.reply(setupSaysHeld), setupSaysHeld); ok {
			t.Error("an answer to one request verified for another")
		}
		if other.code == phone.code {
			same++
		}
	}
	if same == 2 {
		t.Error("the check code does not depend on the request")
	}

	// The television's side with anything else than the session's own: no.
	otherPriv, _ := kem.GenerateKey()
	if _, _, err := openSetupRequest(otherPriv, info, header, sealed); err == nil {
		t.Error("opened with another key")
	}
	otherRaw := bytes.Clone(raw)
	otherRaw[setupKeyLen+3] ^= 0x80
	if _, _, err := openSetupRequest(priv, setupInfo(2, otherRaw), header, sealed); err == nil {
		t.Error("opened under another secret")
	}
	otherRaw = bytes.Clone(raw)
	otherRaw[len(otherRaw)-1] ^= 1
	if _, _, err := openSetupRequest(priv, setupInfo(2, otherRaw), header, sealed); err == nil {
		t.Error("opened under another address list")
	}
	otherRaw = bytes.Clone(raw)
	otherRaw[setupKeyLen+setupSecretLen+1] ^= 1
	if _, _, err := openSetupRequest(priv, setupInfo(2, otherRaw), header, sealed); err == nil {
		t.Error("opened under another relay region")
	}
	if _, _, err := openSetupRequest(priv, setupInfo(1, raw), header, sealed); err == nil {
		t.Error("opened as a code of the other version")
	}
	if _, _, err := openSetupRequest(nil, info, header, sealed); err == nil {
		t.Error("opened without a key")
	}
}

// countingReader records how much was asked of it.
type countingReader struct {
	r    io.Reader
	read int
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.read += n
	return n, err
}

func FuzzParseSetupCode(f *testing.F) {
	for n := 0; n <= setupMaxAddrs; n++ {
		text, _ := testCode(f, n, testRelayRegion)
		f.Add(text)
		f.Add(strings.ToLower(text))
		if n > 0 {
			text, _ = testCode(f, n, 0)
			f.Add(text)
			f.Add(setupPrefixV1 + text[len(setupPrefix):])
		}
	}
	for _, s := range []string{"", "TSP1:", "TSP2:", "TSP3:ABC", "tsp2:" + strings.Repeat("0", 78), "tsp1:" + strings.Repeat("0", 84), "TSP2:" + strings.Repeat("Z", 96), "TSP2:-1", "TSP999:", "TSP:", " \n"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		c, err := parseSetupCode(s)
		if err != nil {
			return
		}
		// Whatever is read is a code a television could have drawn, and
		// drawing it again gives the same text.
		if len(c.addrs) > setupMaxAddrs || len(c.key) != setupKeyLen || (len(c.addrs) == 0) != (c.port == 0) {
			t.Fatalf("%q read as %+v", s, c)
		}
		if len(c.addrs) == 0 && c.relay == 0 {
			t.Fatalf("%q read as a code for nowhere", s)
		}
		if len(c.addrs) > 0 && c.port < setupMinPort {
			t.Fatalf("%q names port %d", s, c.port)
		}
		for i, a := range c.addrs {
			if !setupLANAddr(a) || slices.Contains(c.addrs[:i], a) {
				t.Fatalf("%q names %v", s, c.addrs)
			}
		}
		var text string
		var raw []byte
		switch c.version {
		case 1:
			if c.relay != 0 {
				t.Fatalf("%q: a first-version code with a relay", s)
			}
			text, raw = encodeSetupCodeV1(c.key, c.secret(), c.port, c.addrs)
		case 2:
			text, raw = encodeSetupCode(c.key, c.secret(), c.relay, c.addrs)
		default:
			t.Fatalf("%q: version %d", s, c.version)
		}
		if text != strings.ToUpper(strings.TrimSpace(s)) || !bytes.Equal(raw, c.raw) {
			t.Fatalf("%q reads back as %q", s, text)
		}
	})
}

func FuzzParseSetupBundle(f *testing.F) {
	for _, b := range []setupBundle{
		{key: []byte("k")},
		{key: []byte(testSetupKey), server: testSetupServer, name: "Pixel 9"},
		{key: bytes.Repeat([]byte("k"), setupMaxKey), name: "я"},
	} {
		plain, _ := encodeSetupBundle(b)
		f.Add(plain)
	}
	f.Add([]byte{})
	f.Add(make([]byte, setupPadBlock))
	f.Add(bytes.Repeat([]byte{0xff}, setupPadBlock))
	f.Fuzz(func(t *testing.T, plain []byte) {
		b, err := parseSetupBundle(plain)
		if err != nil {
			return
		}
		if !setupKeyOK(b.key) || !setupServerOK(b.server) || b.name != setupCleanName(b.name) {
			t.Fatalf("read %q %q %q", b.key, b.server, b.name)
		}
		// One layout for one content: sealing what was read gives bytes that
		// read the same.
		again, err := encodeSetupBundle(b)
		if err != nil {
			t.Fatalf("what was read cannot be sealed: %v", err)
		}
		back, err := parseSetupBundle(again)
		if err != nil || !bytes.Equal(back.key, b.key) || back.server != b.server || back.name != b.name {
			t.Fatalf("read back %+v, %v", back, err)
		}
	})
}

// Anything off the network, against one session's key. Nothing may
// panic, nothing may be read past the announced length, and what opens has to
// be one of the requests sealed for this session.
func FuzzSetupRequest(f *testing.F) {
	kem := hpke.DHKEM(ecdh.X25519())
	// A key of this process alone: the fuzzer's workers are other processes,
	// and a request the coordinator sealed must not open in them.
	priv, err := kem.GenerateKey()
	if err != nil {
		f.Fatal(err)
	}
	text, raw := encodeSetupCode(priv.PublicKey().Bytes(), bytes.Repeat([]byte{9}, setupSecretLen), testRelayRegion, addrs("192.168.1.50"))
	code, err := parseSetupCode(text)
	if err != nil {
		f.Fatal(err)
	}
	info := setupInfo(code.version, raw)
	sealedHere := map[string]bool{}
	for _, key := range []string{"k", testSetupKey} {
		plain, _ := encodeSetupBundle(setupBundle{key: []byte(key), server: testSetupServer})
		request, _, err := sealSetupRequest(code, plain)
		if err != nil {
			f.Fatal(err)
		}
		sealedHere[string(request)] = true
		f.Add(request)
		f.Add(request[:len(request)-1])
		f.Add(append(bytes.Clone(request), 0))
	}
	f.Add([]byte("TSP1"))
	f.Add([]byte("GET / HTTP/1.1\r\n\r\n"))
	f.Add(append([]byte("TSP1"), make([]byte, setupKeyLen+2+setupPadBlock+setupSealOverhead)...))
	f.Fuzz(func(t *testing.T, data []byte) {
		r := &countingReader{r: bytes.NewReader(data)}
		header, sealed, got, err := readSetupRequest(r)
		if got != (len(data) > 0) {
			t.Fatalf("got=%v for %d bytes", got, len(data))
		}
		if r.read > setupHeaderLen+setupMaxPlain+setupSealOverhead {
			t.Fatalf("%d bytes read", r.read)
		}
		if err != nil {
			return
		}
		if len(header) != setupHeaderLen || r.read != len(header)+len(sealed) || (len(sealed)-setupSealOverhead)%setupPadBlock != 0 {
			t.Fatalf("header %d, sealed %d, read %d", len(header), len(sealed), r.read)
		}
		plain, x, err := openSetupRequest(priv, info, header, sealed)
		if err != nil {
			return
		}
		if !sealedHere[string(data[:r.read])] {
			t.Fatalf("a request nobody sealed opened: %d bytes, code %s", len(plain), x.code)
		}
	})
}
