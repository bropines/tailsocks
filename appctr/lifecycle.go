package appctr

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

// startMu makes Start one at a time. It spends seconds between retiring the
// old daemon and launching the new one, and a second Start in that window (a
// settings change during a native TUN relaunch) used to run the same steps in
// between: the first then killed the leftover daemons and removed the socket —
// those of the daemon the second had just launched.
var startMu sync.Mutex

func Start(opt *StartOptions) {
	startMu.Lock()
	defer startMu.Unlock()

	stateMu.Lock()
	var old *os.Process
	if cmd != nil {
		old = cmd.Process
	}
	stateMu.Unlock()
	Stop()
	// The successor binds the same SOCKS/HTTP ports. A fixed one-second pause
	// was not always enough for the predecessor to let go of them (a relaunch
	// mid-login died with "address already in use"), so wait for the process
	// to be gone — Stop() kills it after two seconds at the latest.
	waitProcessGone(old, 4*time.Second)
	time.Sleep(300 * time.Millisecond)

	// Default the SOCKS address before opt is published: lastOptions readers,
	// GConfig (DNS-over-SOCKS, DoH, Taildrive proxy) and the daemon command
	// line must all see the same address. Defaulting it after GConfig.update
	// left GConfig with "" for the whole run, silently disabling every
	// SOCKS-routed consumer, and mutating opt after it was stored under
	// stateMu raced with its readers.
	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	pc := PC
	lastOptions = opt
	dnsRescueOff.Store(opt != nil && opt.DnsRescueOff)
	daemonStartTime = time.Now()
	ctx := newDaemonRunLocked()
	generation := daemonGeneration
	stateMu.Unlock()

	slog.Info("========================================")
	slog.Info("=== TAILSOCKS GO CORE STARTING ===", "version", coreVersion, "do_reset", opt.DoReset, "has_authkey", opt.AuthKey != "")
	slog.Info("========================================")
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)
	remoteApply(opt)
	reportProcessHandles()

	killLeftoverDaemons()

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	go func() {
		err := tailscaledCmd(pc, generation, opt.DnsFallbacks, opt.Socks5Server, opt.HttpProxy, opt.Socks5User, opt.Socks5Pass, opt.TaildropDir, opt.ControlProxy, hostinfoReport{
			honest:      opt.HonestHostinfo,
			osVersion:   opt.OsVersion,
			deviceModel: opt.DeviceModel,
			pkg:         opt.InstallSource,
		}, append(append(netmapCacheEnv(opt.NetmapCacheOff), controlPortEnv(opt.ControlPort80)...), dnsRescueEnv(opt.DnsRescueOff)...))
		if errors.Is(err, errLaunchSuperseded) {
			// A Stop() or another Start() retired this run before its process
			// existed; there is nothing to tear down and nothing to report.
			slog.Info("Daemon launch abandoned, the run was stopped before the process started", "generation", generation)
			return
		}
		if err != nil {
			slog.Error("tailscaled cmd crashed", "err", err)
		}

		// Only the supervisor of the daemon that is still current may tear
		// things down. A restart leaves the previous supervisor parked in
		// Wait(); when its daemon finally exits it used to call Stop(), which
		// by then pointed at the freshly started process — killing the daemon
		// the restart had just brought up and reporting the service as dead.
		if !isCurrentGeneration(generation) {
			slog.Info("Previous daemon exited after a restart, leaving the current one alone", "generation", generation)
			return
		}

		Stop()
		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	go runPostStart(ctx, opt, "Daemon socket never became ready after start")

	if opt.DnsProxy != "" {
		RestartDNS()
	}
}

// waitProcessGone polls until p no longer exists or timeout passes. Signal 0
// probes without delivering anything; once the supervisor's Wait has reaped
// the process it reports "already finished".
func waitProcessGone(p *os.Process, timeout time.Duration) {
	if p == nil {
		return
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if err := p.Signal(syscall.Signal(0)); err != nil {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	slog.Warn("Previous daemon still alive after the grace period, launching the successor anyway", "pid", p.Pid)
}

func AttachExternal(opt *StartOptions) {
	// A re-attach without an intervening DetachExternal (the root daemon died
	// and a START intent relaunched it) must retire the previous run — its
	// DNS proxy, bus listener and post-start goroutines —
	// instead of running them alongside the new ones. On a first attach every
	// step is a no-op, so this mirrors Start()'s Stop() prologue.
	releaseGoResources()

	stateMu.Lock()
	externalSocketPath = opt.SocketPath
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	lastOptions = opt
	dnsRescueOff.Store(opt != nil && opt.DnsRescueOff)
	daemonStartTime = time.Now()
	ctx := newDaemonRunLocked()
	pc := PC
	stateMu.Unlock()

	// The root daemon is already running; only the CLI needs its link renewed.
	linkBinaries(pc)

	// The root daemon reads the default route from the same file a child
	// daemon does (TS_NETROUTE_FILE in its env file), so SetDefaultRouteInterface
	// must keep writing it. It is not our child, though: the app wakes it
	// through su (RootUtils.wakeRootDaemon), since a signal from here is refused.
	seedDefaultRoute(pc.DataDir() + "/netroute")

	slog.Info("========================================")
	slog.Info("=== TAILSOCKS GO CORE ATTACHING (ROOT) ===", "version", coreVersion, "do_reset", opt.DoReset, "has_authkey", opt.AuthKey != "")
	slog.Info("========================================")
	GConfig.update(opt.Socks5Server, opt.Socks5User, opt.Socks5Pass, opt.DnsProxy)
	remoteApply(opt)

	go runPostStart(ctx, opt, "Root daemon socket never became ready, aborting attach")

	if opt.DnsProxy != "" {
		RestartDNS()
	}
}

// runPostStart waits for the daemon's LocalAPI and then performs the one-time
// setup of a run: bus listener, authentication and settings sync. Every step
// re-checks ctx: the readiness wait can outlive the run it was started for,
// and waking up on the successor daemon's socket must not apply this run's
// options to it.
func runPostStart(ctx context.Context, opt *StartOptions, notReadyMsg string) {
	if !waitForLocalAPI(ctx, 20*time.Second) {
		if ctx.Err() == nil {
			slog.Error(notReadyMsg)
		}
		return
	}
	if ctx.Err() != nil {
		return
	}
	EnsureIPNBusListener()
	if ctx.Err() != nil {
		return
	}
	if opt.AuthKey != "" {
		Login(opt.AuthKey)
	} else {
		registerMachineWithAuthKey(ctx, opt)
	}
	if ctx.Err() != nil {
		return
	}
	syncSettings(ctx, opt)
}

func RestartDNS() {
	stateMu.Lock()
	opt := lastOptions
	if dnsProxyCancel != nil {
		dnsProxyCancel()
		dnsProxyCancel = nil
	}
	stateMu.Unlock()

	if opt == nil || opt.DnsProxy == "" {
		return
	}

	// Register the cancel synchronously, before the goroutine sleeps. The old
	// code slept first, so two quick RestartDNS calls left two listeners
	// fighting for the port, and a Stop() during the sleep window could not
	// cancel a proxy that had not registered yet — it then started after Stop
	// with no owner.
	ctx, cancel := context.WithCancel(context.Background())
	stateMu.Lock()
	dnsProxyCancel = cancel
	stateMu.Unlock()

	go func() {
		select {
		case <-ctx.Done():
			return
		case <-time.After(500 * time.Millisecond):
		}

		fallbacks := []string{"8.8.8.8:53", "1.1.1.1:53"}
		if opt.DnsFallbacks != "" {
			fallbacks = strings.Split(opt.DnsFallbacks, ",")
		}

		doh := opt.DohFallback
		if doh == "" {
			doh = "https://1.1.1.1/dns-query"
		}

		slog.Info("Starting DNS proxy", "addr", opt.DnsProxy)
		if err := startDNSProxy(ctx, opt.DnsProxy, fallbacks, doh); err != nil {
			slog.Error("DNS proxy error", "err", err)
		}
	}()
}

func Stop() {
	releaseGoResources()

	stateMu.Lock()
	defer stateMu.Unlock()
	// Intentional stop: retire the supervisor parked in Wait() for this
	// process. Otherwise it treats the exit as a crash and reports it through
	// CloseCallBack, which tears the service down in the middle of a restart
	// (RESTART_ACTION, auto-reconnect) and flips desired_running to false.
	// The bump happens even when cmd is still nil: a launch that has not
	// registered its process yet checks the generation before starting it, so
	// a Stop() in that window abandons the launch instead of orphaning a
	// daemon the app believes is already stopped. Start() bumps the generation
	// again for the run it launches.
	daemonGeneration++
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		go func(p *os.Process) {
			time.Sleep(2 * time.Second)
			_ = p.Kill()
		}(cmd.Process)
		cmd = nil
	}
}

// DetachExternal releases every Go-side resource bound to an externally managed
// (root) daemon without terminating the daemon process itself. Used when the
// service stops but the user asked the root daemon to keep running.
func DetachExternal() {
	releaseGoResources()
	slog.Info("Detached from external daemon")
}

// releaseGoResources tears down everything the bridge owns — bus listener, DNS
// proxy, Taildrive server and proxy, Web UI, the run
// context of the post-start goroutines — and clears the socket path so no
// further LocalAPI request is attempted.
func releaseGoResources() {
	// A setup session that is still listening ends with the daemon (setup.go).
	setupDaemonReleased()
	StopWebUI()
	StopDriveServer()
	// The Taildrive reverse proxy dials the SOCKS port of the daemon being
	// released. Kotlin stops it on its own shutdown path only, not when the
	// system destroys the service or the supervisor reports a crash, so the
	// bridge has to be self-sufficient here.
	_ = StopDriveProxy()
	// The remote-management listener answers through the daemon being released.
	remoteRelease()
	StopIPNBusListener()
	FlushDNS()
	// After the listener is gone, so a late notification from the old stream
	// cannot repopulate the snapshot with the previous daemon's state. Without
	// the reset GetBackendState/GetLoginURL kept answering from the old run
	// ("Running" for a daemon still in NoState, a dead AuthURL that made
	// SetPrefs a silent no-op) until the new bus stream happened to overwrite it.
	resetBusState()
	// Drop the pooled LocalAPI connection so nothing is left holding the
	// daemon's socket after the bridge has let go of it, and the DoH pool that
	// tunnels through its SOCKS port.
	closeLocalClient()
	closeDoHClient()

	stateMu.Lock()
	defer stateMu.Unlock()
	externalSocketPath = ""
	daemonStartTime = time.Time{}
	PC.SetSocket("")

	cancelDaemonRunLocked()
	if dnsProxyCancel != nil {
		dnsProxyCancel()
		dnsProxyCancel = nil
	}
}

// killLeftoverDaemons removes any tailscaled a previous process left behind.
// Only the userspace (embedded) mode reaches this, where killing a stray
// daemon is the intent.
func killLeftoverDaemons() {
	_ = exec.Command("/system/bin/killall", "tailscaled").Run()
}
