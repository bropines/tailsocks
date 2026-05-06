package appctr

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
)

// Переменные cmd и stateMu удалены, так как они уже есть в appctr.go

func tailscaledCmd(p pathControl, socksAddr, httpAddr, socksUser, socksPass, taildropDir, controlProxy string) error {
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

	// Proxy configuration
	if controlProxy != "" {
		if strings.HasPrefix(controlProxy, "socks5://") {
			// Tailscale поддерживает нативный SOCKS5 через TS_SOCKS5_SERVER
			proxyAddr := strings.TrimPrefix(controlProxy, "socks5://")
			cmd.Env = append(cmd.Env, "TS_SOCKS5_SERVER="+proxyAddr)
			slog.Info("Proxy: Using TS_SOCKS5_SERVER", "addr", proxyAddr)
		} else if strings.HasPrefix(controlProxy, "http://") {
			// Для HTTP используем TS_HTTP_PROXY
			cmd.Env = append(cmd.Env, "TS_HTTP_PROXY="+controlProxy)
			slog.Info("Proxy: Using TS_HTTP_PROXY", "url", controlProxy)
		} else {
			// Fallback для совместимости
			cmd.Env = append(cmd.Env, "HTTP_PROXY="+controlProxy, "HTTPS_PROXY="+controlProxy)
			slog.Info("Proxy: Using standard HTTP_PROXY", "url", controlProxy)
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