package appctr

import (
	"bufio"
	"fmt"
	"log/slog"
	"os/exec"
	"path/filepath"
	"sync"
)

type pathControl struct {
	execPath   string
	statePath  string
	socketPath string
	execDir    string
	dataDir    string
}

func newPathControl(execPath, socketPath, statePath string) pathControl {
	return pathControl{
		execPath:   execPath,
		statePath:  statePath,
		socketPath: socketPath,
		execDir:    filepath.Dir(execPath),
		dataDir:    filepath.Dir(socketPath),
	}
}

func (p pathControl) TailscaledSo() string   { return p.execPath }
func (p pathControl) Tailscaled() string      { return filepath.Join(p.dataDir, "tailscaled") }
func (p pathControl) TailscaleCliSo() string  { return filepath.Join(p.execDir, "libtailscale_cli.so") }
func (p pathControl) Tailscale() string       { return filepath.Join(p.dataDir, "tailscale") }
func (p pathControl) Socket() string          { return p.socketPath }
func (p *pathControl) State() string          { return p.statePath }

func (p pathControl) DataDir(s ...string) string {
	if len(s) == 0 {
		return p.dataDir
	}
	return filepath.Join(append([]string{p.dataDir}, s...)...)
}

func rm(path ...string) {
	if len(path) == 0 {
		return
	}
	args := []string{"-rf"}
	args = append(args, path...)
	data, err := exec.Command("/system/bin/rm", args...).CombinedOutput()
	slog.Info("rm", "cmd", args, "output", string(data), "err", err)
}

func ln(src, dst string) {
	c := exec.Command("/system/bin/ln", "-s", src, dst)
	data, err := c.CombinedOutput()
	slog.Info("ln", "cmd", c.String(), "output", string(data), "err", err)
}

func tailscaledCmd(p pathControl, socks5host string, httphost string) error {
	rm(p.Tailscale(), p.Tailscaled())
	ln(p.TailscaleCliSo(), p.Tailscale())
	ln(p.TailscaledSo(), p.Tailscaled())

	c := exec.Command(
		p.Tailscaled(),
		"--tun=userspace-networking",
		"--socks5-server="+socks5host,
		"--outbound-http-proxy-listen="+httphost,
		fmt.Sprintf("--statedir=%s", p.State()),
		fmt.Sprintf("--socket=%s", p.Socket()),
	)
	c.Dir = p.DataDir()
	c.Env = []string{
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
		"TS_NO_LOGS_NO_SUPPORT=true",
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
