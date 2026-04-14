package appctr

import (
	"log/slog"
	"os/exec"
	"path/filepath"
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
