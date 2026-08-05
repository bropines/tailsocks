package appctr

import (
	"bufio"
	"context"
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

func tailscaledCmd(p pathControl, dnsFallbacks string, socksAddr, httpAddr, socksUser, socksPass, taildropDir, controlProxy string) error {
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
		resolvedProxy := resolveProxyURL(controlProxy)
		if strings.HasPrefix(resolvedProxy, "socks5://") {
			// For SOCKS5: use ALL_PROXY only. Do NOT add HTTP_PROXY or HTTPS_PROXY.
			c.Env = append(c.Env, "ALL_PROXY="+resolvedProxy)
			slog.Info("Proxy: Using SOCKS5 via ALL_PROXY", "url", resolvedProxy)
		} else {
			// For HTTP(S) proxy use the standard environment variables.
			c.Env = append(c.Env,
				"HTTP_PROXY="+resolvedProxy,
				"HTTPS_PROXY="+resolvedProxy,
			)
			slog.Info("Proxy: Using HTTP via HTTP_PROXY", "url", resolvedProxy)
		}
	}
	if taildropDir != "" {
		c.Env = append(c.Env, "TS_TAILDROP_DIR="+taildropDir)
	}
	if socksUser != "" || socksPass != "" {
		c.Env = append(c.Env, "TS_SOCKS5_USER="+socksUser)
		c.Env = append(c.Env, "TS_SOCKS5_PASS="+socksPass)
	}

	stdOut, err := c.StdoutPipe()
	if err != nil {
		return err
	}
	stdErr, err := c.StderrPipe()
	if err != nil {
		return err
	}

	stateMu.Lock()
	cmd = c
	stateMu.Unlock()

	if err := c.Start(); err != nil {
		return err
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		s := bufio.NewScanner(stdOut)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}()
	go func() {
		defer wg.Done()
		s := bufio.NewScanner(stdErr)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}()

	return c.Wait()
}

func resolveProxyURL(rawURL string) string {
	if rawURL == "" {
		return rawURL
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	host := u.Hostname()
	if host == "" {
		return rawURL
	}
	if net.ParseIP(host) != nil {
		return rawURL
	}

	// 1. Try standard Go resolver
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	addrs, err := net.DefaultResolver.LookupHost(ctx, host)
	if err == nil && len(addrs) > 0 {
		resolvedHost := addrs[0]
		if strings.Contains(resolvedHost, ":") {
			resolvedHost = "[" + resolvedHost + "]"
		}
		u.Host = strings.Replace(u.Host, host, resolvedHost, 1)
		slog.Info("Proxy: resolved hostname to IP", "host", host, "ip", addrs[0], "resolvedUrl", u.String())
		return u.String()
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
		resolvedHost := addrs[0]
		if strings.Contains(resolvedHost, ":") {
			resolvedHost = "[" + resolvedHost + "]"
		}
		u.Host = strings.Replace(u.Host, host, resolvedHost, 1)
		slog.Info("Proxy: resolved hostname to IP via 1.1.1.1 fallback", "host", host, "ip", addrs[0], "resolvedUrl", u.String())
		return u.String()
	}

	slog.Warn("Proxy: failed to resolve hostname to IP, using original", "host", host)
	return rawURL
}
