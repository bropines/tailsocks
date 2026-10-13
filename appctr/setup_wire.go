package appctr

// setup_wire.go — what a setup session (setup.go) puts on the screen and on
// the network: the code a television draws, the bundle a phone sends, and how
// the one is sealed under the other. Both sides use all of it.

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/hpke"
	"crypto/sha256"
	"encoding/base32"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/netip"
	"net/url"
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ─── The code ───────────────────────────────────────────────────────────────

const (
	// setupPrefix opens a code and carries its version. Capitals, like the
	// rest: a QR code made of capitals, digits and a colon is a third smaller.
	// setupPrefixV1 is the first version's, which a phone still reads.
	setupPrefix    = "TSP2:"
	setupPrefixV1  = "TSP1:"
	setupKeyLen    = 32
	setupSecretLen = 16
	// setupFixedLen is a code without its addresses: key, secret and two
	// bytes more — the relay's region in a TSP2 code, the port in a TSP1 one.
	setupFixedLen = setupKeyLen + setupSecretLen + 2
	// setupMinPort keeps a code from naming a well-known service.
	setupMinPort = 1024
	// setupPortBase and setupPortCount are where a TSP2 session listens on
	// the LAN: the dynamic ports, 49152 to 65535.
	setupPortBase  = 49152
	setupPortCount = 16384
)

var (
	errSetupNotCode   = errors.New("not a setup code")
	errSetupVersion   = errors.New("a setup code of another version")
	errSetupMalformed = errors.New("a damaged setup code")
)

// setupCode is what a code says.
type setupCode struct {
	version int
	raw     []byte // everything after the prefix, decoded: what HPKE's info binds
	key     []byte // the television's one-time public key
	// addrs and port are where it listens on the local network; none, and 0,
	// when it has no address there.
	addrs []netip.Addr
	port  uint16
	// relay is the region of the relay it listens through as well, 0 for none
	// (setup_relay.go).
	relay uint16
}

// setupDigits[n] is how many base-36 digits a code with n addresses has.
var setupDigits = func() (d [setupMaxAddrs + 1]int) {
	for n := range d {
		top := new(big.Int).Lsh(big.NewInt(1), uint(8*(setupFixedLen+4*n)))
		d[n] = len(top.Sub(top, big.NewInt(1)).Text(36))
	}
	return d
}()

// setupPortOf is the LAN port of a TSP2 session: a function of its public
// key, so the code need not spell it out. The two bytes that saves are the
// ones the relay's region takes, which keeps a code with a relay exactly as
// long as one without was — and a code's length is what decides whether a
// camera reads it across a room. A television whose port is taken draws
// another key.
func setupPortOf(pub []byte) uint16 {
	sum := sha256.Sum256(append([]byte("tsp2 port\x00"), pub...))
	return setupPortBase + binary.BigEndian.Uint16(sum[:])%setupPortCount
}

// encodeSetupCode is the text of a code and the bytes it stands for:
//
//	key (32) | secret (16) | relay region (2, 0 for none) | 0 to 3 IPv4 addresses (4 each)
//
// in base 36 after "TSP2:". Base 36 rather than base 64: capitals and digits
// alone let a QR code use its alphanumeric mode, 5.5 bits a character against
// 8, and survive any trim or change of case on the way.
func encodeSetupCode(pub, secret []byte, relay uint16, addrs []netip.Addr) (text string, raw []byte) {
	raw = make([]byte, 0, setupFixedLen+4*len(addrs))
	raw = append(raw, pub...)
	raw = append(raw, secret...)
	raw = binary.BigEndian.AppendUint16(raw, relay)
	for _, a := range addrs {
		b := a.As4()
		raw = append(raw, b[:]...)
	}
	digits := strings.ToUpper(new(big.Int).SetBytes(raw).Text(36))
	return setupPrefix + strings.Repeat("0", setupDigits[len(addrs)]-len(digits)) + digits, raw
}

// parseSetupCode reads a scanned code of either version. It refuses anything
// a television would not have drawn: an address that is not a LAN address,
// the same address twice, a TSP1 port below 1024, a TSP2 code with neither an
// address nor a relay.
func parseSetupCode(text string) (*setupCode, error) {
	if len(text) > 4*len(setupPrefix)+4*setupDigits[setupMaxAddrs] {
		return nil, errSetupNotCode
	}
	// ASCII only before the case is folded: Unicode's folding would turn a
	// few letters from elsewhere into ones a code is made of.
	text = strings.TrimSpace(text)
	for i := 0; i < len(text); i++ {
		if text[i] >= utf8.RuneSelf {
			return nil, errSetupNotCode
		}
	}
	text = strings.ToUpper(text)
	version := 2
	body, ok := strings.CutPrefix(text, setupPrefix)
	if !ok {
		version = 1
		if body, ok = strings.CutPrefix(text, setupPrefixV1); !ok {
			if setupOtherVersion(text) {
				return nil, errSetupVersion
			}
			return nil, errSetupNotCode
		}
	}
	n := slices.Index(setupDigits[:], len(body))
	if n < 0 || (n == 0 && version == 1) {
		return nil, errSetupMalformed
	}
	// SetString would take a sign as well.
	for i := 0; i < len(body); i++ {
		if c := body[i]; (c < '0' || c > '9') && (c < 'A' || c > 'Z') {
			return nil, errSetupMalformed
		}
	}
	size := setupFixedLen + 4*n
	v, ok := new(big.Int).SetString(body, 36)
	if !ok || v.BitLen() > 8*size {
		return nil, errSetupMalformed
	}
	raw := v.FillBytes(make([]byte, size))
	c := &setupCode{version: version, raw: raw, key: raw[:setupKeyLen]}
	two := binary.BigEndian.Uint16(raw[setupKeyLen+setupSecretLen:])
	switch {
	case version == 1:
		if c.port = two; c.port < setupMinPort {
			return nil, errSetupMalformed
		}
	case n == 0 && two == 0:
		return nil, errSetupMalformed
	default:
		c.relay = two
		if n > 0 {
			c.port = setupPortOf(c.key)
		}
	}
	for at := setupFixedLen; at < size; at += 4 {
		a := netip.AddrFrom4([4]byte(raw[at : at+4]))
		if !setupLANAddr(a) || slices.Contains(c.addrs, a) {
			return nil, errSetupMalformed
		}
		c.addrs = append(c.addrs, a)
	}
	return c, nil
}

// setupOtherVersion reports whether text is shaped like a code of a version
// this build does not know: "TSP", a number, a colon.
func setupOtherVersion(text string) bool {
	rest, ok := strings.CutPrefix(text, "TSP")
	if !ok {
		return false
	}
	digits, _, ok := strings.Cut(rest, ":")
	if !ok || digits == "" || len(digits) > 3 {
		return false
	}
	for i := 0; i < len(digits); i++ {
		if digits[i] < '0' || digits[i] > '9' {
			return false
		}
	}
	return true
}

// setupRelaySeed is what both ends derive the relay's tunnel from: 64 bytes
// out of the code's secret, salted with its key. The relay then costs the
// code two bytes — the region — instead of the hundred a tailcat address has.
// It is one-way: what the tunnel shows a relay's operator says nothing about
// the secret the sealing rests on.
func setupRelaySeed(pub, secret []byte) ([]byte, error) {
	return hkdf.Key(sha256.New, secret, pub, "TailSocks setup v2 relay", 64)
}

// secret is the code's one-time secret.
func (c *setupCode) secret() []byte {
	return c.raw[setupKeyLen : setupKeyLen+setupSecretLen]
}

// setupSessionID names a session without giving anything away: five
// characters from a hash of the television's key. Both screens can show it,
// and the log uses it.
func setupSessionID(pub []byte) string {
	sum := sha256.Sum256(append([]byte("tsp1 id\x00"), pub...))
	return base32.StdEncoding.EncodeToString(sum[:5])[:5]
}

// ─── The bundle ─────────────────────────────────────────────────────────────

const (
	setupBundleVersion = 1
	// setupPadBlock is what a bundle is padded to, so its sealed length does
	// not give away the kind of key or whether a server came with it.
	setupPadBlock = 256
	// setupMaxPlain holds the longest bundle the limits below allow.
	setupMaxPlain     = 4 * setupPadBlock
	setupMaxKey       = 512
	setupMaxServer    = 255
	setupMaxNameRunes = 32
	setupMaxNameBytes = 96
)

var (
	errSetupBundle        = errors.New("a bundle that cannot be used")
	errSetupBundleVersion = errors.New("a bundle of another version")
)

// setupBundle is what a phone sends.
type setupBundle struct {
	key    []byte // the auth key
	server string // its control server, "" to leave the device's as it is
	name   string // what the phone calls itself
}

// encodeSetupBundle lays a bundle out for sealing:
//
//	version (1) | key length (2) | key | server length (1) | server |
//	name length (1) | name | zeros up to a multiple of setupPadBlock
//
// Lengths rather than JSON: the key stays bytes from end to end, and the
// reader has nothing to interpret.
func encodeSetupBundle(b setupBundle) ([]byte, error) {
	if !setupKeyOK(b.key) || !setupServerOK(b.server) || len(b.name) > setupMaxNameBytes {
		return nil, errSetupBundle
	}
	n := 1 + 2 + len(b.key) + 1 + len(b.server) + 1 + len(b.name)
	padded := (n + setupPadBlock - 1) / setupPadBlock * setupPadBlock
	out := make([]byte, 0, padded)
	out = append(out, setupBundleVersion)
	out = binary.BigEndian.AppendUint16(out, uint16(len(b.key)))
	out = append(out, b.key...)
	out = append(out, byte(len(b.server)))
	out = append(out, b.server...)
	out = append(out, byte(len(b.name)))
	out = append(out, b.name...)
	return out[:padded], nil
}

// parseSetupBundle reads what encodeSetupBundle wrote, and trusts none of it:
// every length is checked against what is left, the padding has to be zeros,
// and the fields have to pass the checks the phone applied before sending.
func parseSetupBundle(plain []byte) (setupBundle, error) {
	if len(plain) == 0 || len(plain) > setupMaxPlain || len(plain)%setupPadBlock != 0 {
		return setupBundle{}, errSetupBundle
	}
	if plain[0] != setupBundleVersion {
		return setupBundle{}, errSetupBundleVersion
	}
	rest := plain[1:]
	take := func(lenBytes int) ([]byte, bool) {
		if len(rest) < lenBytes {
			return nil, false
		}
		n := int(rest[0])
		if lenBytes == 2 {
			n = int(binary.BigEndian.Uint16(rest))
		}
		rest = rest[lenBytes:]
		if len(rest) < n {
			return nil, false
		}
		field := rest[:n]
		rest = rest[n:]
		return field, true
	}
	key, ok1 := take(2)
	server, ok2 := take(1)
	name, ok3 := take(1)
	if !ok1 || !ok2 || !ok3 || !setupKeyOK(key) || !setupServerOK(string(server)) {
		return setupBundle{}, errSetupBundle
	}
	if len(name) > setupMaxNameBytes || !utf8.Valid(name) {
		return setupBundle{}, errSetupBundle
	}
	for _, b := range rest {
		if b != 0 {
			return setupBundle{}, errSetupBundle
		}
	}
	return setupBundle{
		key:    bytes.Clone(key),
		server: string(server),
		// Cleaned again: it goes on this device's screen.
		name: setupCleanName(string(name)),
	}, nil
}

// setupKeyOK reports whether key could be an auth key: 1 to setupMaxKey
// printable ASCII characters, no spaces. Tailscale's and Headscale's keys
// are, the "?ephemeral=…" tail an OAuth client secret may carry included.
func setupKeyOK(key []byte) bool {
	if len(key) == 0 || len(key) > setupMaxKey {
		return false
	}
	for _, c := range key {
		if c < 0x21 || c > 0x7e {
			return false
		}
	}
	return true
}

// setupServerOK reports whether s can be shown and used as a control server:
// empty, or an http(s) URL with a host, in printable ASCII, without
// credentials, query or fragment. ASCII only, so the address a person reads
// before confirming cannot be a look-alike.
func setupServerOK(s string) bool {
	if s == "" {
		return true
	}
	if len(s) > setupMaxServer {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < 0x21 || s[i] > 0x7e {
			return false
		}
	}
	u, err := url.Parse(s)
	if err != nil {
		return false
	}
	return (u.Scheme == "http" || u.Scheme == "https") && u.Hostname() != "" &&
		u.User == nil && u.Opaque == "" && u.RawQuery == "" && !u.ForceQuery && u.Fragment == ""
}

// setupCleanName makes a device name fit for a line on a screen: what prints,
// nothing that steers the text around it, at most setupMaxNameRunes
// characters.
func setupCleanName(name string) string {
	var sb strings.Builder
	runes := 0
	for _, r := range strings.TrimSpace(name) {
		if r == utf8.RuneError || !unicode.IsPrint(r) {
			continue
		}
		if runes == setupMaxNameRunes || sb.Len()+utf8.RuneLen(r) > setupMaxNameBytes {
			break
		}
		sb.WriteRune(r)
		runes++
	}
	return strings.TrimSpace(sb.String())
}

// setupKeyPrefix is the part of an auth key that says what kind it is and
// nothing else, for the screen that asks whether to use it; "" when the key
// does not start like one this app knows.
func setupKeyPrefix(key []byte) string {
	for _, p := range []string{"tskey-auth", "tskey-client", "hskey-auth"} {
		if bytes.HasPrefix(key, []byte(p+"-")) {
			return p
		}
	}
	return ""
}

// ─── Sealing ────────────────────────────────────────────────────────────────

const (
	// setupMagic opens a request; with the length it is also the sealed
	// bundle's associated data.
	setupMagic     = "TSP1"
	setupHeaderLen = len(setupMagic) + setupKeyLen + 2
	// setupSealOverhead is what ChaCha20-Poly1305 adds to a bundle.
	setupSealOverhead = 16
	setupTagLen       = 16
	setupReplyLen     = 1 + setupTagLen
)

// What the television tells a phone. The first four answer a request, the
// rest say how it ended; a phone takes each only where it belongs, so one
// cannot be played back as the other.
const (
	setupSaysHeld        byte = 0x01 // the bundle waits for the person
	setupSaysTaken       byte = 0x02 // another bundle got there first
	setupSaysUnsupported byte = 0x03 // sealed properly, but not a bundle this build can use
	setupSaysClosed      byte = 0x04 // the session ended while the request was on its way
	setupSaysConfirmed   byte = 0x11
	setupSaysRejected    byte = 0x12
	setupSaysExpired     byte = 0x13
	setupSaysCancelled   byte = 0x14 // the session ended some other way
)

var errSetupRequest = errors.New("not a setup request")

// setupInfo is HPKE's info for a code: a label with its version and
// everything the code said. The secret is in there, which is what makes
// every key of the exchange depend on it.
func setupInfo(version int, raw []byte) []byte {
	return append(fmt.Appendf(nil, "TailSocks setup v%d\x00", version), raw...)
}

// setupExchange is what the two ends of one request share beyond the bundle.
type setupExchange struct {
	replyKey []byte
	digest   [sha256.Size]byte // of the request as it went over the network
	code     string            // the check code
}

// newSetupExchange derives it from an HPKE context — the sender's or the
// recipient's, they export the same — and the request.
func newSetupExchange(ctx interface {
	Export(string, int) ([]byte, error)
}, header, sealed []byte) (*setupExchange, error) {
	replyKey, err := ctx.Export("tsp1 reply", sha256.Size)
	if err != nil {
		return nil, err
	}
	codeKey, err := ctx.Export("tsp1 check code", sha256.Size)
	if err != nil {
		return nil, err
	}
	x := &setupExchange{replyKey: replyKey}
	h := sha256.New()
	h.Write(header)
	h.Write(sealed)
	h.Sum(x.digest[:0])
	mac := hmac.New(sha256.New, codeKey)
	mac.Write(x.digest[:])
	// Six digits out of 64 bits: the bias is far below anything a person
	// comparing two screens could meet.
	x.code = fmt.Sprintf("%06d", binary.BigEndian.Uint64(mac.Sum(nil))%1_000_000)
	return x, nil
}

// reply is the television's answer of one kind: the kind and its tag.
func (x *setupExchange) reply(kind byte) []byte {
	mac := hmac.New(sha256.New, x.replyKey)
	mac.Write([]byte{kind})
	mac.Write(x.digest[:])
	return append([]byte{kind}, mac.Sum(nil)[:setupTagLen]...)
}

// check reads an answer on the phone: the kind it carries, if its tag is the
// television's and the kind is one that may come at this point. The tags are
// compared in constant time.
func (x *setupExchange) check(reply []byte, allowed ...byte) (byte, bool) {
	if len(reply) != setupReplyLen {
		return 0, false
	}
	if !hmac.Equal(reply[1:], x.reply(reply[0])[1:]) {
		return 0, false
	}
	return reply[0], slices.Contains(allowed, reply[0])
}

// sealSetupRequest is the phone's request for a code and a laid-out bundle.
func sealSetupRequest(c *setupCode, plain []byte) ([]byte, *setupExchange, error) {
	pk, err := hpke.DHKEM(ecdh.X25519()).NewPublicKey(c.key)
	if err != nil {
		return nil, nil, err
	}
	enc, sender, err := hpke.NewSender(pk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), setupInfo(c.version, c.raw))
	if err != nil {
		return nil, nil, err
	}
	header := make([]byte, 0, setupHeaderLen+len(plain)+setupSealOverhead)
	header = append(header, setupMagic...)
	header = append(header, enc...)
	header = binary.BigEndian.AppendUint16(header, uint16(len(plain)+setupSealOverhead))
	if len(header) != setupHeaderLen {
		return nil, nil, errSetupRequest
	}
	sealed, err := sender.Seal(header, plain)
	if err != nil {
		return nil, nil, err
	}
	x, err := newSetupExchange(sender, header, sealed)
	if err != nil {
		return nil, nil, err
	}
	return append(header, sealed...), x, nil
}

// readSetupRequest reads one request and not a byte past it. The length it
// announces is checked before anything of that length is read, so the most a
// peer can make this allocate is setupMaxPlain and a tag. got says whether
// the peer sent anything at all.
func readSetupRequest(r io.Reader) (header, sealed []byte, got bool, err error) {
	header = make([]byte, setupHeaderLen)
	n, err := io.ReadFull(r, header)
	if err != nil {
		return nil, nil, n > 0, err
	}
	if string(header[:len(setupMagic)]) != setupMagic {
		return nil, nil, true, errSetupRequest
	}
	size := int(binary.BigEndian.Uint16(header[setupHeaderLen-2:])) - setupSealOverhead
	if size < setupPadBlock || size > setupMaxPlain || size%setupPadBlock != 0 {
		return nil, nil, true, errSetupRequest
	}
	sealed = make([]byte, size+setupSealOverhead)
	if _, err := io.ReadFull(r, sealed); err != nil {
		return nil, nil, true, err
	}
	return header, sealed, true, nil
}

// openSetupRequest opens a request with the session's key. It fails for a
// request sealed to another key, under another secret or another list of
// addresses, and for one that was altered on the way.
func openSetupRequest(priv hpke.PrivateKey, info, header, sealed []byte) ([]byte, *setupExchange, error) {
	if priv == nil || len(header) != setupHeaderLen {
		return nil, nil, errSetupRequest
	}
	enc := header[len(setupMagic) : len(setupMagic)+setupKeyLen]
	r, err := hpke.NewRecipient(enc, priv, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), info)
	if err != nil {
		return nil, nil, err
	}
	plain, err := r.Open(header, sealed)
	if err != nil {
		return nil, nil, err
	}
	x, err := newSetupExchange(r, header, sealed)
	if err != nil {
		clear(plain)
		return nil, nil, err
	}
	return plain, x, nil
}
