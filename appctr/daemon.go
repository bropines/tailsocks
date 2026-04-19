package appctr

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"sync"
)

// Переменные cmd и stateMu удалены, так как они уже есть в appctr.go

func tailscaledCmd(p pathControl, socksAddr, httpAddr, socksUser, socksPass, taildropDir string) error {
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

	c.Env = append(os.Environ(),
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
		"TS_NO_LOGS_NO_SUPPORT=true",
		"TS_AUTH_ONCE=true",
	)
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