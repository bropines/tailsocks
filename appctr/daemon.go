package appctr

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// cmd and stateMu are declared in appctr.go; do not redeclare here.

// errLaunchSuperseded is returned by tailscaledCmd when a Stop() or another
// Start() retired the run before its process was started.
var errLaunchSuperseded = errors.New("daemon launch superseded before the process started")

// tailscaledCmd launches the daemon for the run identified by generation and
// blocks until it exits. Between Start() spawning this and cmd being assigned
// there is filesystem work (rm/ln) during which a Stop() used to find cmd == nil
// and do nothing; the process then started anyway, orphaned, with a supervisor
// that later reported it as a crash. The generation check closes that window.
func tailscaledCmd(p pathControl, generation uint64, dnsFallbacks string, socksAddr, httpAddr, socksUser, socksPass, taildropDir, controlProxy string) error {
	// Cheap early exit before the filesystem work; the authoritative check is
	// the one under stateMu below.
	if !isCurrentGeneration(generation) {
		return errLaunchSuperseded
	}

	rm(p.Tailscale(), p.Tailscaled())
	ln(p.TailscaleCliSo(), p.Tailscale())
	ln(p.TailscaledSo(), p.Tailscaled())

	args := []string{
		"--tun=userspace-networking",
		"--socks5-server=" + socksAddr,
		fmt.Sprintf("--statedir=%s", p.State()),
		fmt.Sprintf("--socket=%s", p.Socket()),
	}

	if httpAddr != "" {
		args = append(args, "--outbound-http-proxy-listen="+httpAddr)
	}

	c := exec.Command(p.Tailscaled(), args...)
	c.Dir = p.DataDir()

	stateMu.Lock()
	netState := latestInterfaceState
	stateMu.Unlock()

	c.Env = append(os.Environ(),
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
		"TS_NO_LOGS_NO_SUPPORT=true",
		"TS_AUTH_ONCE=true",
		"TS_NET_STATE="+netState,
	)
	if dnsFallbacks != "" {
		c.Env = append(c.Env, "TS_DNS_FALLBACK="+dnsFallbacks)
	} else {
		c.Env = append(c.Env, "TS_DNS_FALLBACK=1.1.1.1,8.8.8.8")
	}

	// Proxy configuration (Outbound)
	// We clear TS_SOCKS5_SERVER here to ensure it's only set via flags if needed
	c.Env = append(c.Env, "TS_SOCKS5_SERVER=")

	if controlProxy != "" {
		// Fail closed. tailscaled reads these variables through
		// proxy.FromEnvironment, which silently falls back to a DIRECT
		// connection whenever the URL does not parse — a stray "/" or "?" in
		// a password used to switch the proxy off without a word. A proxy the
		// user configured but that cannot be honoured must stop the start.
		if err := validateProxyURL(controlProxy); err != nil {
			slog.Error("Proxy: control proxy URL is unusable, refusing to start the daemon without it", "err", err)
			return fmt.Errorf("control proxy URL is unusable: %w", err)
		}
		if staticOverride := resolveProxyHostStatic(controlProxy); staticOverride != "" {
			c.Env = append(c.Env, "TS_STATIC_HOSTS="+staticOverride)
			slog.Info("Proxy: Set static DNS override for proxy host", "override", staticOverride)
		}
		if strings.HasPrefix(controlProxy, "socks5://") {
			// For SOCKS5: use ALL_PROXY only. Do NOT add HTTP_PROXY or HTTPS_PROXY.
			c.Env = append(c.Env, "ALL_PROXY="+controlProxy)
			slog.Info("Proxy: Using SOCKS5 via ALL_PROXY", "url", redactProxyURL(controlProxy))
		} else {
			// For HTTP(S) proxy use the standard environment variables.
			c.Env = append(c.Env,
				"HTTP_PROXY="+controlProxy,
				"HTTPS_PROXY="+controlProxy,
			)
			slog.Info("Proxy: Using HTTP via HTTP_PROXY", "url", redactProxyURL(controlProxy))
		}
	}
	if taildropDir != "" {
		c.Env = append(c.Env, "TS_TAILDROP_DIR="+taildropDir)
	}
	if socksUser != "" || socksPass != "" {
		c.Env = append(c.Env, "TS_SOCKS5_USER="+socksUser)
		c.Env = append(c.Env, "TS_SOCKS5_PASS="+socksPass)
	}

	// Check, start and register under one lock so Stop() can never observe a
	// launch it is unable to retire: before this section the generation check
	// abandons the launch, after it cmd is set and Stop() signals the process.
	// The fork/exec takes a few milliseconds and happens once per run.
	stateMu.Lock()
	if generation != daemonGeneration {
		stateMu.Unlock()
		return errLaunchSuperseded
	}
	stdOut, err := c.StdoutPipe()
	if err != nil {
		stateMu.Unlock()
		return err
	}
	stdErr, err := c.StderrPipe()
	if err != nil {
		stateMu.Unlock()
		return err
	}
	if err := c.Start(); err != nil {
		stateMu.Unlock()
		return err
	}
	cmd = c
	stateMu.Unlock()

	// A daemon log line can exceed bufio.Scanner's default 64 KB token, which
	// silently kills the scanner and drops all later output; give it room.
	scan := func(r interface{ Read([]byte) (int, error) }) {
		s := bufio.NewScanner(r)
		s.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); scan(stdOut) }()
	go func() { defer wg.Done(); scan(stdErr) }()

	// Drain both pipes before Wait() closes them, so the last lines before a
	// crash — the interesting ones — are not lost.
	wg.Wait()
	return c.Wait()
}

// validateProxyURL checks that a control-proxy URL will actually be honoured
// by tailscaled's proxy.FromEnvironment: a known scheme, a host, and a port.
func validateProxyURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return err
	}
	switch strings.ToLower(u.Scheme) {
	case "socks5", "socks5h", "http", "https":
	default:
		return fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	if u.Hostname() == "" {
		return errors.New("missing host")
	}
	if u.Port() == "" {
		return errors.New("missing port")
	}
	if u.Path != "" && u.Path != "/" || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("path, query or fragment in proxy URL (unescaped credential characters?)")
	}
	return nil
}

// redactProxyURL hides the credentials of a proxy URL for logging; the log
// buffer is user-visible, copyable and exportable.
func redactProxyURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "<unparseable proxy url>"
	}
	if u.User != nil {
		if _, hasPass := u.User.Password(); hasPass {
			u.User = url.UserPassword(u.User.Username(), "***")
		}
	}
	return u.String()
}

func resolveProxyHostStatic(rawURL string) string {
	if rawURL == "" {
		return ""
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	host := u.Hostname()
	if host == "" || net.ParseIP(host) != nil {
		return ""
	}

	// 1. Try standard Go resolver
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	addrs, err := net.DefaultResolver.LookupHost(ctx, host)
	if err == nil && len(addrs) > 0 {
		return host + "=" + addrs[0]
	}

	// 2. Direct DNS query fallback via 1.1.1.1:53 UDP
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 2 * time.Second}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}
	addrs, err = r.LookupHost(ctx, host)
	if err == nil && len(addrs) > 0 {
		return host + "=" + addrs[0]
	}

	return ""
}
