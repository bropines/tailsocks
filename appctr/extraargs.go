package appctr

import (
	"fmt"
	"log/slog"
	"sort"
	"strconv"
	"strings"

	"tailscale.com/net/netutil"
	"tailscale.com/tailcfg"
	"tailscale.com/util/dnsname"
)

// The "Extra Arguments" settings field lets the user type `tailscale up`-style
// flags. There is no `tailscale up` in this app: every setting reaches the
// daemon as a PATCH of ipn.MaskedPrefs (see syncSettings). So the flags are
// parsed here and translated into the very same prefs map, applied after the
// app's own keys — that is what makes the field an escape hatch rather than a
// second copy of the settings screen.
//
// The flag → pref table below follows upstream's own mapping in
// cmd/tailscale/cli/up.go (addPrefFlagMapping) and the field names/types in
// ipn/prefs.go; anything Linux-, Windows- or CLI-only is refused by name with
// a reason instead of being silently dropped.

// flagKind tells the argument parser whether a flag takes a value and, if so,
// how a value written as a separate token should be recognised.
type flagKind int

const (
	kindUnknown flagKind = iota
	kindBool
	kindString
)

// upFlag is one parsed `--name[=value]` argument.
type upFlag struct {
	name     string // lower-cased, leading dashes stripped
	value    string
	hasValue bool
	raw      string // as the user typed it, for log messages
}

// supportedUpFlags maps a flag name to the kind of value it takes. Every entry
// is translated into an ipn.MaskedPrefs field by extraArgPrefs.
var supportedUpFlags = map[string]flagKind{
	"accept-routes":              kindBool,   // ipn.Prefs.RouteAll
	"accept-dns":                 kindBool,   // ipn.Prefs.CorpDNS
	"shields-up":                 kindBool,   // ipn.Prefs.ShieldsUp
	"ssh":                        kindBool,   // ipn.Prefs.RunSSH
	"exit-node-allow-lan-access": kindBool,   // ipn.Prefs.ExitNodeAllowLANAccess
	"report-posture":             kindBool,   // ipn.Prefs.PostureChecking
	"advertise-exit-node":        kindBool,   // folded into ipn.Prefs.AdvertiseRoutes
	"advertise-routes":           kindString, // ipn.Prefs.AdvertiseRoutes
	"advertise-tags":             kindString, // ipn.Prefs.AdvertiseTags
	"hostname":                   kindString, // ipn.Prefs.Hostname
	"login-server":               kindString, // ipn.Prefs.ControlURL
}

// refusedUpFlags are real `tailscale up` flags that are deliberately not
// honoured here, each with the reason the user is told.
var refusedUpFlags = map[string]string{
	"exit-node":                     "the exit node is chosen in the app, which owns ExitNodeID",
	"auth-key":                      "use the app's own Auth Key field",
	"audience":                      "workload identity federation is not used by this app",
	"client-id":                     "workload identity federation is not used by this app",
	"client-secret":                 "workload identity federation is not used by this app",
	"id-token":                      "workload identity federation is not used by this app",
	"force-reauth":                  "CLI-only flag with no preference behind it",
	"reset":                         "CLI-only flag with no preference behind it",
	"qr":                            "CLI-only flag with no preference behind it",
	"qr-format":                     "CLI-only flag with no preference behind it",
	"json":                          "CLI-only flag with no preference behind it",
	"timeout":                       "CLI-only flag with no preference behind it",
	"accept-risk":                   "CLI-only flag with no preference behind it",
	"host-routes":                   "CLI-only flag with no preference behind it",
	"operator":                      "Linux-only; there is no unprivileged operator user on Android",
	"snat-subnet-routes":            "Linux-only; a userspace node has no netfilter rules to manage",
	"stateful-filtering":            "Linux-only; a userspace node has no netfilter rules to manage",
	"netfilter-mode":                "Linux-only; a userspace node has no netfilter rules to manage",
	"unattended":                    "Windows-only",
	"auto-update":                   "the Android build does not update itself",
	"update-check":                  "the Android build does not update itself",
	"nickname":                      "upstream `tailscale up` cannot change the profile name either",
	"webclient":                     "the Web UI switch in the app owns RunWebClient",
	"advertise-connector":           "app connectors are not offered by this app",
	"advertise-services":            "service advertisement is not offered by this app",
	"remote-config":                 "hands the tailnet admin full control of this node; not exposed here",
	"relay-server-port":             "the relay server is not offered by this app",
	"relay-server-static-endpoints": "the relay server is not offered by this app",
	"sync":                          "an upstream testing knob, not a user setting",
}

// tokenizeArgs splits a raw argument line into tokens. It understands single
// and double quotes, backslash escapes outside of single quotes, and any run of
// whitespace as a separator.
//
// It is forgiving on purpose: an unterminated quote is treated as if it were
// closed at the end of the line and reported through problems, so one stray
// quote costs the user a warning rather than the whole line.
func tokenizeArgs(s string) (tokens []string, problems []string) {
	var (
		cur     strings.Builder
		have    bool // cur holds a token, even when it is the empty string
		quote   rune // 0, '\'' or '"'
		escaped bool
	)
	flush := func() {
		if have {
			tokens = append(tokens, cur.String())
			cur.Reset()
			have = false
		}
	}
	for _, r := range s {
		switch {
		case escaped:
			cur.WriteRune(r)
			have = true
			escaped = false
		case r == '\\' && quote != '\'':
			escaped = true
		case quote != 0:
			if r == quote {
				quote = 0
			} else {
				cur.WriteRune(r)
			}
			have = true
		case r == '\'' || r == '"':
			quote = r
			have = true
		case r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == '\v' || r == '\f':
			flush()
		default:
			cur.WriteRune(r)
			have = true
		}
	}
	if escaped {
		problems = append(problems, "a trailing backslash was ignored")
	}
	if quote != 0 {
		problems = append(problems, fmt.Sprintf("unterminated %c quote; treated as if closed at the end of the line", quote))
	}
	flush()
	return tokens, problems
}

// kindOfUpFlag reports how a flag name spells its value, resolving the
// `--no-flag` spelling of a boolean to the flag it negates.
func kindOfUpFlag(name string) flagKind {
	if k, ok := supportedUpFlags[name]; ok {
		return k
	}
	if base, ok := strings.CutPrefix(name, "no-"); ok {
		if supportedUpFlags[base] == kindBool {
			return kindBool
		}
	}
	return kindUnknown
}

// parseUpFlags turns tokens into flags. Values may be written as `--flag=value`
// or as a following token; a boolean flag with no value at all means true, and
// `--no-flag` means false.
//
// Unlike Go's flag package a value given as a separate token may not itself
// look like a flag: `--hostname --ssh` is reported as a missing value rather
// than silently naming the node "--ssh".
func parseUpFlags(tokens []string) (flags []upFlag, problems []string) {
	for i := 0; i < len(tokens); i++ {
		tok := tokens[i]
		if tok == "" {
			problems = append(problems, "an empty argument was ignored")
			continue
		}
		if !strings.HasPrefix(tok, "-") {
			problems = append(problems, fmt.Sprintf("%q is not a flag (expected --flag, --flag=value or --flag value); ignored", tok))
			continue
		}
		body := strings.TrimLeft(tok, "-")
		if body == "" {
			problems = append(problems, fmt.Sprintf("%q is not a flag name; ignored", tok))
			continue
		}
		f := upFlag{raw: tok}
		if eq := strings.IndexByte(body, '='); eq >= 0 {
			f.name = strings.ToLower(body[:eq])
			f.value = body[eq+1:]
			f.hasValue = true
			flags = append(flags, f)
			continue
		}
		f.name = strings.ToLower(body)

		switch kindOfUpFlag(f.name) {
		case kindString:
			if i+1 >= len(tokens) {
				problems = append(problems, fmt.Sprintf("%s needs a value; ignored", f.raw))
				continue
			}
			next := tokens[i+1]
			if strings.HasPrefix(next, "-") && len(next) > 1 {
				problems = append(problems, fmt.Sprintf("%s needs a value; ignored", f.raw))
				continue
			}
			f.value, f.hasValue = next, true
			i++
		case kindBool:
			// A bare boolean means true, but `--flag true` is accepted too:
			// only swallow the next token when it really is a boolean.
			if i+1 < len(tokens) {
				if _, err := strconv.ParseBool(tokens[i+1]); err == nil {
					f.value, f.hasValue = tokens[i+1], true
					i++
				}
			}
		}
		flags = append(flags, f)
	}
	return flags, problems
}

// boolValue resolves a boolean flag, honouring the `--no-` prefix.
func boolValue(f upFlag, warnf func(string, ...any)) (name string, v bool, ok bool) {
	name = f.name
	negated := false
	if _, isKnown := supportedUpFlags[name]; !isKnown {
		if base, cut := strings.CutPrefix(name, "no-"); cut {
			name, negated = base, true
		}
	}
	if negated && f.hasValue {
		warnf("%s: --no-… does not take a value; flag ignored", f.raw)
		return name, false, false
	}
	v = true
	if f.hasValue {
		parsed, err := strconv.ParseBool(f.value)
		if err != nil {
			warnf("%s: %q is not a boolean (use true or false); flag ignored", f.raw, f.value)
			return name, false, false
		}
		v = parsed
	}
	if negated {
		v = !v
	}
	return name, v, true
}

// stringValue resolves a flag that must carry a value.
func stringValue(f upFlag, warnf func(string, ...any)) (string, bool) {
	if !f.hasValue {
		warnf("%s needs a value (use --flag=value); flag ignored", f.raw)
		return "", false
	}
	return f.value, true
}

// extraArgPrefs translates a raw `tailscale up`-style argument line into the
// ipn.MaskedPrefs fields that syncSettings PATCHes onto the daemon.
//
// It is total on purpose: it never returns an error and never panics on
// malformed input. Every flag it cannot honour — unknown, refused, or carrying
// a value it cannot parse — is handed to warnf, which the caller routes to
// slog and therefore to the in-app log screen. A single bad flag costs only
// itself; the rest of the line still applies.
func extraArgPrefs(raw string, warnf func(format string, args ...any)) map[string]any {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	if warnf == nil {
		warnf = func(string, ...any) {}
	}

	tokens, problems := tokenizeArgs(raw)
	flags, moreProblems := parseUpFlags(tokens)
	for _, p := range append(problems, moreProblems...) {
		warnf("%s", p)
	}

	prefs := make(map[string]any)
	set := func(field string, value any) {
		prefs[field] = value
		prefs[field+"Set"] = true
	}

	var (
		advRoutes       string
		haveAdvRoutes   bool
		advExitNode     bool
		haveAdvExitNode bool
	)

	for _, f := range flags {
		name := f.name
		if _, known := supportedUpFlags[name]; !known {
			if base, cut := strings.CutPrefix(name, "no-"); cut && supportedUpFlags[base] == kindBool {
				name = base
			}
		}

		if reason, refused := refusedUpFlags[name]; refused {
			warnf("%s is not supported here and was ignored: %s", f.raw, reason)
			continue
		}
		if _, known := supportedUpFlags[name]; !known {
			warnf("unknown flag %s was ignored", f.raw)
			continue
		}

		switch name {
		case "accept-routes":
			if _, v, ok := boolValue(f, warnf); ok {
				set("RouteAll", v)
			}
		case "accept-dns":
			if _, v, ok := boolValue(f, warnf); ok {
				set("CorpDNS", v)
			}
		case "shields-up":
			if _, v, ok := boolValue(f, warnf); ok {
				set("ShieldsUp", v)
			}
		case "ssh":
			if _, v, ok := boolValue(f, warnf); ok {
				if v {
					warnf("%s: the Tailscale SSH server does not run on Android and the daemon will reject it", f.raw)
				}
				set("RunSSH", v)
			}
		case "exit-node-allow-lan-access":
			if _, v, ok := boolValue(f, warnf); ok {
				set("ExitNodeAllowLANAccess", v)
			}
		case "report-posture":
			if _, v, ok := boolValue(f, warnf); ok {
				set("PostureChecking", v)
			}
		case "advertise-exit-node":
			if _, v, ok := boolValue(f, warnf); ok {
				advExitNode, haveAdvExitNode = v, true
			}
		case "advertise-routes":
			if v, ok := stringValue(f, warnf); ok {
				advRoutes, haveAdvRoutes = v, true
			}
		case "advertise-tags":
			if v, ok := stringValue(f, warnf); ok {
				if tags, ok := parseAdvertiseTags(f, v, warnf); ok {
					set("AdvertiseTags", tags)
				}
			}
		case "hostname":
			if v, ok := stringValue(f, warnf); ok {
				if v != "" {
					if err := dnsname.ValidHostname(v); err != nil {
						warnf("%s: %q is not a valid hostname (%v); flag ignored", f.raw, v, err)
						continue
					}
				}
				set("Hostname", v)
			}
		case "login-server":
			if v, ok := stringValue(f, warnf); ok {
				if v == "" {
					warnf("%s: an empty control server URL is ignored", f.raw)
					continue
				}
				set("ControlURL", v)
			}
		}
	}

	// AdvertiseRoutes carries both --advertise-routes and --advertise-exit-node
	// (upstream folds the two default routes into the same pref, see
	// netutil.CalcAdvertiseRoutes). Only write it when the user asked for
	// routes: a lone --advertise-exit-node=false would otherwise clear the
	// subnet routes configured in the app's own Routes field.
	if haveAdvRoutes || (haveAdvExitNode && advExitNode) {
		routes, err := netutil.CalcAdvertiseRoutes(advRoutes, haveAdvExitNode && advExitNode)
		if err != nil {
			warnf("--advertise-routes: %v; advertised routes left unchanged", err)
		} else {
			list := make([]string, 0, len(routes))
			for _, r := range routes {
				list = append(list, r.String())
			}
			set("AdvertiseRoutes", list)
		}
	} else if haveAdvExitNode && !advExitNode {
		warnf("--advertise-exit-node=false on its own does not clear advertised routes; use the app's Routes field")
	}

	if len(prefs) == 0 {
		return nil
	}
	return prefs
}

// parseAdvertiseTags normalises a comma-separated tag list the way
// cmd/tailscale/cli/up.go does: a bare name gets the "tag:" prefix, and every
// tag is validated with tailcfg.CheckTag. One bad tag drops the whole flag,
// as upstream does, so a typo cannot half-apply an ACL identity.
func parseAdvertiseTags(f upFlag, v string, warnf func(string, ...any)) ([]string, bool) {
	tags := []string{}
	if strings.TrimSpace(v) == "" {
		return tags, true // an explicit empty value clears the tags
	}
	for _, tag := range strings.Split(v, ",") {
		tag = strings.TrimSpace(tag)
		if tag == "" {
			continue
		}
		if !strings.Contains(tag, ":") {
			tag = "tag:" + tag
		}
		if err := tailcfg.CheckTag(tag); err != nil {
			warnf("%s: %q is not a valid tag (%v); flag ignored", f.raw, tag, err)
			return nil, false
		}
		tags = append(tags, tag)
	}
	return tags, true
}

// applyExtraArgs folds the user's "Extra Arguments" into prefs, on top of the
// app's own keys so the escape hatch can override them. It returns the fields
// it added, so the caller can retry without them if the daemon rejects the
// batch. Warnings go through slog, which the log screen mirrors.
func applyExtraArgs(prefs map[string]any, raw string) map[string]any {
	extra := extraArgPrefs(raw, func(format string, args ...any) {
		slog.Warn("Extra arguments: " + fmt.Sprintf(format, args...))
	})
	if len(extra) == 0 {
		return nil
	}
	names := make([]string, 0, len(extra)/2)
	for k, v := range extra {
		prefs[k] = v
		if !strings.HasSuffix(k, "Set") {
			names = append(names, k)
		}
	}
	sort.Strings(names)
	slog.Info("Extra arguments applied", "prefs", strings.Join(names, ","))
	return extra
}
