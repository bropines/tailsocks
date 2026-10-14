package appctr

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"sync"
	"time"
)

// lastErrStr is written by the CLI thread (RunTailscaleArgs) and read-and-
// cleared by the UI thread (GetLastError), so it needs its own lock.
var (
	lastErrMu  sync.Mutex
	lastErrStr string
)

func GetLastError() string {
	lastErrMu.Lock()
	defer lastErrMu.Unlock()
	e := lastErrStr
	lastErrStr = "" // Clear after reading
	return e
}

func ForceRefresh() {
	if !IsRunning() {
		return
	}
	slog.Debug("Manual refresh requested")
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err == nil && len(data) > 0 {
		var res struct {
			BackendState string `json:"BackendState"`
			AuthURL      string `json:"AuthURL"`
		}
		if json.Unmarshal(data, &res) == nil {
			busStateMu.Lock()
			if res.BackendState != "" {
				busState.BackendState = res.BackendState
			}
			busState.AuthURL = res.AuthURL
			busStateMu.Unlock()
		}
	}
	ReUp()
}

func RunTailscaleCmd(commandStr string) string {
	return RunTailscaleArgs(strings.Fields(commandStr)...)
}

func RunTailscaleArgs(parts ...string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
	}
	// The CLI's own netcheck has never worked on Android from an app process
	// (netlink RIB denied, see runNetcheck); the bare command gets the
	// in-process report in the CLI's layout. Flags still go to the CLI.
	if len(parts) == 1 && parts[0] == "netcheck" {
		return netcheckText()
	}
	pc := getPC()
	c := cliCommand(pc, append([]string{"--socket", pc.Socket()}, parts...)...)

	isRoutineCheck := len(parts) > 0 && (parts[0] == "status" || parts[0] == "dns" || parts[0] == "netcheck" || parts[0] == "ping")

	if !isRoutineCheck {
		slog.Info("Running Tailscale CLI", "args", parts)
	}

	output, err := c.CombinedOutput()
	outStr := string(output)

	if strings.Contains(outStr, "http 410") {
		lastErrMu.Lock()
		lastErrStr = "410_GONE"
		lastErrMu.Unlock()
	}

	if err != nil {
		if !isRoutineCheck {
			slog.Error("CLI command failed", "out", outStr, "err", err)
		}
	} else if outStr != "" {
		if !isRoutineCheck {
			slog.Info("CLI command output", "out", outStr)
		}
	}

	return outStr
}

// registerRun/registerBusy keep a single registration attempt alive per daemon
// run. Every app resume calls ForceRefresh → ReUp, which used to spawn another
// polling loop; several of them then raced and flooded the log with the same
// states. The slot is keyed by run rather than being a process-wide flag: an
// attempt left over from a released run exits at its next ctx check, but until
// then it must not make the new daemon's own registration report "already in
// progress" and never happen.
var (
	registerMu   sync.Mutex
	registerRun  uint64
	registerBusy bool
)

// registerMachineWithAuthKey waits for the daemon socket to be ready, then
// applies initial authentication and preferences via LocalAPI (CLI-free).
// ctx is the run the options belong to; once it is cancelled no further
// request is sent, because the socket may by then belong to a different daemon.
func registerMachineWithAuthKey(ctx context.Context, opt *StartOptions) {
	if ctx.Err() != nil {
		return
	}
	gen := runGeneration(ctx)
	registerMu.Lock()
	if registerBusy && registerRun == gen {
		registerMu.Unlock()
		slog.Debug("Registration already in progress, skipping duplicate")
		return
	}
	registerBusy, registerRun = true, gen
	registerMu.Unlock()
	defer func() {
		registerMu.Lock()
		// A newer run may have taken the slot over; only release our own.
		if registerRun == gen {
			registerBusy = false
		}
		registerMu.Unlock()
	}()

	// Poll until the LocalAPI answers a real request, not just until the socket
	// file appears — the daemon binds the socket before it can serve traffic.
	apiReady := false
	// Forty seconds, not twenty: this waits for the daemon's LocalAPI to answer,
	// and on a slow or throttled link the first round-trip can take a while. The
	// loop exits the moment it gets an answer, so a fast network pays nothing.
	for i := 1; i <= 40; i++ {
		if stStr, err := GetStatusJSON(false); err == nil && len(stStr) > 0 {
			apiReady = true
			break
		}
		if !sleepCtx(ctx, 1*time.Second) {
			return
		}
	}

	if !apiReady {
		slog.Error("Tailscaled API timeout")
		return
	}
	if ctx.Err() != nil {
		return
	}

	if opt.DoReset {
		slog.Info("LocalAPI: reset requested, logging out existing session")
		Logout()
		// Once. A new profile carries this flag into its first start, and
		// without clearing it here every trip back to the app logged the
		// profile out again, seconds after the browser login had succeeded.
		consumeReset()
		if !sleepCtx(ctx, 500*time.Millisecond) {
			return
		}
		// What the daemon holds now is the client of a profile with no login
		// server of its own, new or just logged out: the default one's.
		if !controlServerFits(opt) {
			slog.Info("LocalAPI: starting the backend for the login server in the settings", "server", controlServerOf(opt.LoginServer))
			if !startBackendFor(ctx, opt) {
				return
			}
		}
		slog.Info("LocalAPI: triggering interactive login for new session")
		_ = LoginInteractive()
		return
	}

	if opt.AuthKey != "" {
		// Both callers send an auth key to Login and only reach this function
		// without one, so this is defensive. It delegates rather than repeating
		// the sequence: the order of prefs, Start and the login request is what
		// makes an auth key register at all, and a second copy of it drifts.
		slog.Info("LocalAPI: authenticating with auth key")
		Login(opt.AuthKey)
		return
	}

	// Let the daemon reach its own verdict instead of guessing from an early state.
	//
	// tailscaled starts its backend by itself as it comes up, from the prefs the
	// profile has stored (cmd/tailscaled: lb.Start once the prefs are valid).
	//
	// A profile with a saved session is then in NoState until the control plane
	// has answered — behind a control-plane proxy that easily takes several
	// seconds. Treating that window as "not logged in" and calling
	// LoginInteractive regenerates the node key and logs the device out of a
	// perfectly good session. A backend found in NoState is sent one bare Start
	// (no UpdatePrefs, so nothing is overwritten), and then the daemon's verdict
	// is waited for.
	//
	// A profile without a session is in NeedsLogin at once — on a control client
	// built for the login server the profile has stored, which on a new profile
	// is the default one whatever the settings say. So NeedsLogin, and a login
	// link that is already out, are trusted only when that server is the one the
	// options name; otherwise the backend is first started again from the
	// options (nextRegisterStep).
	var statusResp struct {
		BackendState string `json:"BackendState"`
		AuthURL      string `json:"AuthURL"`
	}

	// The deadline is for the control plane to hand back an auth URL, which on a
	// throttled network is exactly what takes long — the author hit it, and a
	// second attempt succeeded because the path was warm. An upper bound, not a
	// delay: the loop leaves as soon as the daemon decides.
	const stateSettleTimeout = 120 * time.Second
	deadline := time.Now().Add(stateSettleTimeout)
	needsLogin := false
	started := false
	lastLogged := ""

	for time.Now().Before(deadline) {
		stStr, err := GetStatusJSON(false)
		// The status round-trip can outlive the run; decide nothing on a
		// daemon this registration was not started for.
		if ctx.Err() != nil {
			slog.Info("Registration abandoned, its daemon run was released")
			return
		}
		if err == nil && len(stStr) > 0 && json.Unmarshal([]byte(stStr), &statusResp) == nil {
			// Log transitions, not every poll: this loop runs twice a second and
			// used to bury the rest of the log while waiting for a login.
			if statusResp.BackendState != lastLogged {
				lastLogged = statusResp.BackendState
				slog.Info("Daemon startup state", "backend_state", statusResp.BackendState, "has_auth_url", statusResp.AuthURL != "")
			}

			look := registerLook{
				state:      statusResp.BackendState,
				authURL:    statusResp.AuthURL != "",
				serverFits: true,
				started:    started,
			}
			// Asked only where the answer decides something: of a daemon that
			// waits for a login, before this attempt has started it itself.
			if look.state == "NeedsLogin" && !started {
				look.serverFits = controlServerFits(opt)
				if ctx.Err() != nil {
					return
				}
			}

			switch nextRegisterStep(look) {
			case registerLeave:
				switch look.state {
				case "Running", "Starting":
					slog.Info("Account has an active session, preserving it", "backend_state", look.state)
				case "NeedsLogin":
					slog.Info("Daemon already produced an auth URL, nothing to trigger")
				}
				return
			case registerResume:
				// Logged in but paused. Ask it to run; logging in again would
				// throw away a working node key for no reason.
				slog.Info("Account is logged in but stopped, requesting WantRunning")
				requestWantRunning()
				return
			case registerStart:
				started = true
				if look.state == "NeedsLogin" {
					slog.Info("The daemon waits for a login on another server than the settings name, starting the backend for that one",
						"server", controlServerOf(opt.LoginServer), "had_auth_url", look.authURL)
				} else {
					slog.Info("Backend has not been started yet, sending Start")
				}
				if !startBackendFor(ctx, opt) {
					return
				}
			case registerLogin:
				needsLogin = true
			}
			if needsLogin {
				break
			}
		}
		if !sleepCtx(ctx, registerPollInterval) {
			return
		}
	}

	if !needsLogin {
		// Still undecided after the timeout: the daemon is stuck, not logged out.
		// Forcing a login here would throw away the node key.
		slog.Warn("Daemon did not settle into a known state, not forcing a login",
			"backend_state", statusResp.BackendState)
		return
	}

	if ctx.Err() != nil {
		return
	}
	slog.Info("Account requires authentication (BackendState: " + statusResp.BackendState + "). Triggering interactive login.")
	_ = LoginInteractive()
}

// registerPollInterval is how often the keyless registration looks at the
// daemon while it waits for it to decide. A variable for the tests.
var registerPollInterval = 500 * time.Millisecond

// defaultControlURL is the coordination server of a profile that names none
// (ipn.DefaultControlURL).
const defaultControlURL = "https://controlplane.tailscale.com"

// controlServerOf is the control server a login-server value stands for, in a
// form two of them can be compared in: the default for an empty value and for
// upstream's other name of it (ipn.IsLoginServerSynonym), the URL without
// surrounding space or a closing slash otherwise. For comparing only — the
// daemon is sent the value as it was typed.
func controlServerOf(loginServer string) string {
	s := strings.TrimRight(strings.TrimSpace(loginServer), "/")
	if s == "" || s == "https://login.tailscale.com" {
		return defaultControlURL
	}
	return s
}

// controlServerFits reports whether the daemon's ControlURL pref names the
// server opt asks for. A new profile's is empty, a logged-out one's is empty
// again, and a profile whose login server was changed in the settings still
// holds the old one — in each case the control client the daemon built at its
// own start is for a server the user did not ask for. Prefs that cannot be read
// answer true: the caller restarts the backend on a false, and that is not done
// on a guess.
func controlServerFits(opt *StartOptions) bool {
	raw, err := GetPrefsJSON()
	if err != nil || len(raw) == 0 {
		return true
	}
	var prefs struct {
		ControlURL string `json:"ControlURL"`
	}
	if json.Unmarshal([]byte(raw), &prefs) != nil {
		return true
	}
	return controlServerOf(prefs.ControlURL) == controlServerOf(opt.LoginServer)
}

// registerStep is what the keyless registration does after one look at the
// daemon.
type registerStep int

const (
	registerWait   registerStep = iota // the daemon has not decided: look again
	registerLeave                      // a session is alive, or a login link is out: nothing to do
	registerResume                     // logged in but paused: ask it to run
	registerStart                      // start the backend from the options, then look again
	registerLogin                      // ask for the interactive login
)

// registerLook is what one look at the daemon shows.
type registerLook struct {
	state   string // BackendState
	authURL bool   // a login link is out
	// serverFits: the daemon's ControlURL pref names the server the options ask
	// for (controlServerFits). It decides only while the daemon waits for a
	// login.
	serverFits bool
	// started: this attempt has sent its Start, so the control client is the
	// one built from the options.
	started bool
}

// nextRegisterStep decides the next step of the keyless registration.
//
// A session — Running, Starting, Stopped — is never started over and never
// logged in again, whatever server it is on: both would cost a working node
// key, and moving a signed-in profile to another server is not this routine's
// business. A daemon that waits for a login is first put on the server the
// options name, once per attempt; only then is its login link trusted or a
// login asked for, so neither can come from the server the daemon happened to
// start on.
func nextRegisterStep(l registerLook) registerStep {
	switch l.state {
	case "Running", "Starting":
		return registerLeave
	case "Stopped":
		return registerResume
	case "NeedsLogin":
		if !l.serverFits && !l.started {
			return registerStart
		}
		if l.authURL {
			return registerLeave
		}
		return registerLogin
	case "NoState":
		// The backend was never started, or is still on its way to the control
		// plane: one Start, then the daemon's own verdict.
		if !l.started {
			return registerStart
		}
	}
	if l.authURL {
		return registerLeave
	}
	return registerWait
}

// startBackendFor has the daemon build its control client anew from opt, in the
// order Login keeps and for its reasons: the prefs the client is made from,
// then Start, then WantRunning. It reports false when the run was released on
// the way.
func startBackendFor(ctx context.Context, opt *StartOptions) bool {
	applyPreStartPrefs(opt)
	if ctx.Err() != nil {
		return false
	}
	if err := StartDaemon("{}"); err != nil {
		slog.Warn("Could not start the backend", "err", err)
	} else {
		// Start dropped the previous client and its login link with it. The
		// bus does not say so, and the link kept here would go on being shown
		// — and would turn the WantRunning below into a silent no-op.
		forgetAuthURL()
	}
	if ctx.Err() != nil {
		return false
	}
	requestWantRunning()
	return ctx.Err() == nil
}

// forgetAuthURL drops the login link from the bus snapshot.
func forgetAuthURL() {
	busStateMu.Lock()
	busState.AuthURL = ""
	busStateMu.Unlock()
}

// applyPreStartPrefs pushes the options the daemon has to hold *before* Start
// through the masked PATCH /prefs endpoint. Start's UpdatePrefs replaces the
// entire prefs object, so it must never be used to carry a partial set.
//
// Everything the control client is built from belongs here. Start reads
// ControlURL and Hostname out of the prefs as they stand at that moment and
// hands them to a newly created control client; changing them afterwards does
// not move an existing client to another control server, it only takes effect
// at the next Start. A custom login server applied after Start would send the
// auth key to the default coordination server instead.
//
// The control server is always in it, the default one by name when the options
// have none: the profile may hold a server the user has since cleared, and a
// Start would build the client for that one.
//
// It goes straight to the daemon, past PatchPrefsJSON's guard. That guard keeps
// a settings sync from disturbing a login in progress; what follows this patch
// is a Start, which ends that login anyway — and a skipped patch would start
// the new client on the old server.
//
// WantRunning is deliberately not in this set — see requestWantRunning.
func applyPreStartPrefs(opt *StartOptions) {
	controlURL := opt.LoginServer
	if controlURL == "" {
		controlURL = defaultControlURL
	}
	prefs := map[string]interface{}{
		"RouteAll":      opt.AcceptRoutes,
		"RouteAllSet":   true,
		"CorpDNS":       opt.AcceptDNS,
		"CorpDNSSet":    true,
		"ControlURL":    controlURL,
		"ControlURLSet": true,
	}
	if opt.Hostname != "" {
		prefs["Hostname"] = opt.Hostname
		prefs["HostnameSet"] = true
	}
	payload, _ := json.Marshal(prefs)
	if _, err := doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(string(payload))); err != nil {
		slog.Warn("Could not apply startup preferences", "err", err)
	}
}

// requestWantRunning asks the daemon to run. It is the other half of
// applyPreStartPrefs and goes *after* the Start that carried the auth key: the
// daemon logs in when WantRunning goes from false to true, and it does so on
// whichever control client exists at that moment. Sent before Start, that login
// runs on the previous, keyless client, while the client Start then builds with
// the key is never asked to log in — a new profile's stored prefs are LoggedOut,
// which is exactly what stops Start from logging in by itself.
func requestWantRunning() {
	if err := PatchPrefsJSON(`{"WantRunning":true,"WantRunningSet":true}`); err != nil {
		slog.Warn("Could not request WantRunning", "err", err)
	}
}

// profileNeedsRegistration reports whether the daemon holds no node key for the
// active profile, meaning it has never registered. A status it cannot read
// answers false: the caller decides from this whether to force a login, and
// forcing one on a node that may already hold a key throws that key away.
func profileNeedsRegistration() bool {
	stStr, err := GetStatusJSON(false)
	if err != nil || len(stStr) == 0 {
		return false
	}
	var st struct {
		HaveNodeKey bool `json:"HaveNodeKey"`
	}
	if json.Unmarshal([]byte(stStr), &st) != nil {
		return false
	}
	return !st.HaveNodeKey
}
