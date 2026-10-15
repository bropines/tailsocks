package appctr

// remote_diag.go — the diagnostics report and the tail of the log, for an
// owner, with full control and the device's network access to files on
// (remote_files.go).
//
// The report is the app's own "report a problem": versions, device,
// permissions, network, mode. It is written to be handed to a stranger and
// names no node, no address, no key and no account (core/Diagnostics.kt).
//
// The log is another matter. It quotes auth keys, sign-in links, proxy
// credentials, account names and paths, and the app's own export of it hides
// none of that: on the device, the person exporting it is the one it belongs
// to. So what leaves here is scrubbed first, by remoteScrub below, which
// replaces everything it can recognise as a secret or as a name of somebody —
// and a great deal that merely looks like one. A filter is still a filter: it
// knows the forms it was taught. That is why the log is behind the second
// switch, which a person turns on at the device after being told so, and why
// it is the owner's only.

import (
	"net/http"
	"regexp"
	"strings"
)

const (
	// remoteLogLines is how much of the log a request gets: the tail.
	remoteLogLines = 300
	// remoteLogLineMax cuts one line; remoteMaxDiagReport the report.
	remoteLogLineMax    = 600
	remoteMaxDiagReport = 64 << 10
)

// remoteScrubRule is one form of secret and what stands in for it.
type remoteScrubRule struct {
	re   *regexp.Regexp
	with string
}

// remoteScrubRules, in order. Each leaves a mark where it took something out,
// so a reader sees that a value was there.
var remoteScrubRules = []remoteScrubRule{
	// Tailscale's own keys, whatever follows the prefix.
	{regexp.MustCompile(`tskey-[A-Za-z0-9_-]+`), "[key]"},
	// Keys the daemon prints by kind.
	{regexp.MustCompile(`\b(?:privkey|nodekey|discokey|mkey|nlpriv|nlpub|chalpriv|chalpub|client-secret):[0-9A-Za-z+/=_-]+`), "[key]"},
	// A name that says "secret", and what it is set to: --auth-key=…,
	// "password": "…", Authorization: Bearer …, token=…; and the same as a
	// flag with its value after a space.
	{regexp.MustCompile(`(?i)((?:auth[-_ ]?key|authkey|pass(?:word|wd)?|secret|token|bearer|authorization|cookie|api[-_ ]?key|credential)s?"?\s*[=:]\s*"?(?:bearer\s+|basic\s+)?)[^\s"',;&]+`), "$1[…]"},
	{regexp.MustCompile(`(?i)(--?(?:auth[-_]?key|authkey|pass(?:word)?|secret|token)\s+)[^\s"',;&\]]+`), "$1[…]"},
	// user:password@ in an address.
	{regexp.MustCompile(`://[^/\s:@"']+:[^/\s@"']+@`), "://[…]@"},
	// Everything after the host of a link: a sign-in link is nothing but its
	// path, and a query carries tokens.
	{regexp.MustCompile(`(https?://[^/\s"'?#]+)[/?#][^\s"']*`), "$1/[…]"},
	// Account names.
	{regexp.MustCompile(`[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+`), "[account]"},
	// The app's private directory.
	{regexp.MustCompile(`/data/(?:user(?:_de)?/\d+|data)/[^\s"',:;]+`), "/data/[…]"},
	// Anything long and opaque: base64 and hex of 32 characters and more.
	{regexp.MustCompile(`[A-Za-z0-9+/_=-]{32,}`), "[…]"},
}

// remoteScrub takes out of text what must not leave the device and reports
// how many places it changed.
func remoteScrub(text string) (string, int) {
	n := 0
	for _, rule := range remoteScrubRules {
		text = rule.re.ReplaceAllStringFunc(text, func(match string) string {
			n++
			return rule.re.ReplaceAllString(match, rule.with)
		})
	}
	return text, n
}

// noteDiag writes one line for a look at the diagnostics, unless the newest
// line already says the same of the same device.
func (m *remoteManager) noteDiag(rq *remoteRequest, what string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return
	}
	if a := m.store.data.Audit; len(a) > 0 {
		if last := a[len(a)-1]; last.Op == "diag" && last.Detail == what && last.NodeID == rq.caller.NodeID {
			return
		}
	}
	m.auditLocked(remoteEvent{NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(), Op: "diag", Detail: what, OK: true})
	m.saveLocked()
}

func (m *remoteManager) handleDiagReport(rq *remoteRequest) (int, any) {
	raw, status, refusal := m.query(remoteRequestDiagReport, "")
	if status != http.StatusOK {
		return status, refusal
	}
	if len(raw) > remoteMaxDiagReport {
		raw = raw[:remoteMaxDiagReport]
	}
	// The report promises to hold nothing secret. It is scrubbed all the same.
	text, _ := remoteScrub(strings.ToValidUTF8(raw, ""))
	m.noteDiag(rq, "report")
	return http.StatusOK, struct {
		Text string `json:"text"`
	}{text}
}

func (m *remoteManager) handleDiagLog(rq *remoteRequest) (int, any) {
	lines := strings.Split(strings.TrimRight(m.logText(), "\n"), "\n")
	if len(lines) > remoteLogLines {
		lines = lines[len(lines)-remoteLogLines:]
	}
	out, scrubbed := []string{}, 0
	for _, line := range lines {
		line = strings.ToValidUTF8(strings.TrimRight(line, "\r"), "")
		if line == "" {
			continue
		}
		line, n := remoteScrub(line)
		scrubbed += n
		if r := []rune(line); len(r) > remoteLogLineMax {
			line = string(r[:remoteLogLineMax]) + "…"
		}
		out = append(out, line)
	}
	m.noteDiag(rq, "log")
	return http.StatusOK, struct {
		Lines    []string `json:"lines"`
		Scrubbed int      `json:"scrubbed"`
	}{out, scrubbed}
}
