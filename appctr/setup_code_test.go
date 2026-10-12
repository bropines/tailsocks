package appctr

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"io"
	"slices"
	"strings"
	"testing"
)

// A key in the shape of Tailscale's, and a control server of one's own.
const (
	testSetupKey    = "tskey-auth-kTESTtest1CNTRL-AbCdEfGhIjKlMnOpQrStUvWxYz012345"
	testSetupServer = "https://headscale.example.com"
)

// testCode is a code as a television would draw it, for n addresses.
func testCode(t testing.TB, n int) (text string, raw []byte) {
	t.Helper()
	priv, err := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	secret := make([]byte, setupSecretLen)
	rand.Read(secret)
	all := addrs("192.168.1.50", "10.0.0.2", "169.254.10.20")
	return encodeSetupCode(priv.PublicKey().Bytes(), secret, 41234, all[:n])
}

func TestSetupCodeRoundTrip(t *testing.T) {
	for n := 1; n <= setupMaxAddrs; n++ {
		text, raw := testCode(t, n)
		c, err := parseSetupCode(text)
		if err != nil {
			t.Fatalf("%d addresses: %v", n, err)
		}
		if !bytes.Equal(c.raw, raw) || !bytes.Equal(c.key, raw[:setupKeyLen]) || c.port != 41234 || len(c.addrs) != n {
			t.Errorf("%d addresses: read back as %+v", n, c)
		}
		if !slices.Equal(c.addrs, addrs("192.168.1.50", "10.0.0.2", "169.254.10.20")[:n]) {
			t.Errorf("%d addresses: %v", n, c.addrs)
		}
		for _, variant := range []string{strings.ToLower(text), "  " + text + "\r\n", "tsp1:" + text[5:]} {
			if again, err := parseSetupCode(variant); err != nil || !bytes.Equal(again.raw, raw) {
				t.Errorf("%q: %v", variant, err)
			}
		}
	}
	// A key that starts with zero bytes keeps its length.
	text, raw := encodeSetupCode(make([]byte, setupKeyLen), make([]byte, setupSecretLen), 2000, addrs("10.0.0.1"))
	if c, err := parseSetupCode(text); err != nil || !bytes.Equal(c.raw, raw) || len(text) != len(setupPrefix)+setupDigits[1] {
		t.Errorf("leading zeros: %q, %v", text, err)
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
// version 4 code — with error correction M when one address is listed.
func TestSetupCodeFitsQR(t *testing.T) {
	const alnum = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
	for n, want := range map[int]struct {
		chars   int
		fits    []string
		doesNot []string
	}{
		1: {chars: 89, fits: []string{"4-M", "4-L"}, doesNot: []string{"3-L", "4-Q"}},
		2: {chars: 95, fits: []string{"4-L", "5-M"}, doesNot: []string{"3-L", "4-M", "5-Q"}},
		3: {chars: 101, fits: []string{"4-L", "5-M", "6-Q"}, doesNot: []string{"3-L", "4-M", "5-Q"}},
	} {
		text, _ := testCode(t, n)
		if len(text) != want.chars {
			t.Errorf("%d addresses: %d characters, want %d", n, len(text), want.chars)
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

func TestParseSetupCodeRefuses(t *testing.T) {
	priv, _ := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	pub := priv.PublicKey().Bytes()
	secret := make([]byte, setupSecretLen)
	code := func(port uint16, as ...string) string {
		text, _ := encodeSetupCode(pub, secret, port, addrs(as...))
		return text
	}
	if _, err := parseSetupCode(code(1024, "192.168.1.50")); err != nil {
		t.Fatalf("a good code: %v", err)
	}
	for name, text := range map[string]string{
		"a public address":         code(41234, "203.0.113.7"),
		"a public one among good":  code(41234, "192.168.1.50", "8.8.8.8"),
		"the tailnet":              code(41234, "100.101.102.103"),
		"this machine":             code(41234, "127.0.0.1"),
		"no address in particular": code(41234, "0.0.0.0"),
		"broadcast":                code(41234, "255.255.255.255"),
		"multicast":                code(41234, "224.0.0.251"),
		"the same address twice":   code(41234, "192.168.1.50", "192.168.1.50"),
		"port 0":                   code(0, "192.168.1.50"),
		"a well-known port":        code(443, "192.168.1.50"),
		"port 1023":                code(1023, "192.168.1.50"),
		"another script":           "ТSP1:" + code(41234, "192.168.1.50")[5:],
		"a folded letter":          "tſp1:" + code(41234, "192.168.1.50")[5:],
		"a digit from elsewhere":   code(41234, "192.168.1.50")[:30] + "٣" + code(41234, "192.168.1.50")[31:],
		"a space inside":           code(41234, "192.168.1.50")[:30] + " " + code(41234, "192.168.1.50")[31:],
		"a link":                   "tailsocks://setup?c=" + code(41234, "192.168.1.50"),
	} {
		if c, err := parseSetupCode(text); err == nil {
			t.Errorf("%s: read as %v port %d", name, c.addrs, c.port)
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
	text, raw := encodeSetupCode(priv.PublicKey().Bytes(), secret, 41234, addrs("192.168.1.50"))
	code, err := parseSetupCode(text)
	if err != nil {
		t.Fatal(err)
	}
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
	opened, tv, err := openSetupRequest(priv, setupInfo(raw), header, sealed)
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
	if _, _, err := openSetupRequest(otherPriv, setupInfo(raw), header, sealed); err == nil {
		t.Error("opened with another key")
	}
	otherRaw := bytes.Clone(raw)
	otherRaw[setupKeyLen+3] ^= 0x80
	if _, _, err := openSetupRequest(priv, setupInfo(otherRaw), header, sealed); err == nil {
		t.Error("opened under another secret")
	}
	otherRaw = bytes.Clone(raw)
	otherRaw[len(otherRaw)-1] ^= 1
	if _, _, err := openSetupRequest(priv, setupInfo(otherRaw), header, sealed); err == nil {
		t.Error("opened under another address list")
	}
	if _, _, err := openSetupRequest(nil, setupInfo(raw), header, sealed); err == nil {
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
	for n := 1; n <= setupMaxAddrs; n++ {
		text, _ := testCode(f, n)
		f.Add(text)
		f.Add(strings.ToLower(text))
	}
	for _, s := range []string{"", "TSP1:", "TSP2:ABC", "tsp1:" + strings.Repeat("0", 84), "TSP1:" + strings.Repeat("Z", 96), "TSP1:-1", "TSP999:", "TSP:", " \n"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		c, err := parseSetupCode(s)
		if err != nil {
			return
		}
		// Whatever is read is a code a television could have drawn, and
		// drawing it again gives the same text.
		if len(c.addrs) < 1 || len(c.addrs) > setupMaxAddrs || c.port < setupMinPort || len(c.key) != setupKeyLen {
			t.Fatalf("%q read as %+v", s, c)
		}
		for i, a := range c.addrs {
			if !setupLANAddr(a) || slices.Contains(c.addrs[:i], a) {
				t.Fatalf("%q names %v", s, c.addrs)
			}
		}
		text, raw := encodeSetupCode(c.key, c.raw[setupKeyLen:setupKeyLen+setupSecretLen], c.port, c.addrs)
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
	_, raw := encodeSetupCode(priv.PublicKey().Bytes(), bytes.Repeat([]byte{9}, setupSecretLen), 41234, addrs("192.168.1.50"))
	code := &setupCode{raw: raw, key: raw[:setupKeyLen], port: 41234, addrs: addrs("192.168.1.50")}
	info := setupInfo(raw)
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
