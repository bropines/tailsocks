package appctr

import (
	"errors"
	"io/fs"
	"log/slog"
	"os"
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

// TailscaledSo is the one native binary, libtailscale.so: the daemon, and the
// CLI too since it is built with ts_include_cli — it runs as the CLI when
// invoked under the name "tailscale" (see cliCommand).
func (p pathControl) TailscaledSo() string         { return p.execPath }
func (p pathControl) Tailscaled() string           { return filepath.Join(p.dataDir, "tailscaled") }
func (p pathControl) Tailscale() string            { return filepath.Join(p.dataDir, "tailscale") }
func (p pathControl) Socket() string               { return p.socketPath }
func (p *pathControl) SetSocket(socketPath string) { p.socketPath = socketPath }
func (p *pathControl) State() string               { return p.statePath }

func (p pathControl) DataDir(s ...string) string {
	if len(s) == 0 {
		return p.dataDir
	}
	return filepath.Join(append([]string{p.dataDir}, s...)...)
}

// ensureSymlink makes dst a symbolic link to src, leaving it alone when it
// already is one. Only a failure is logged.
func ensureSymlink(src, dst string) {
	if cur, err := os.Readlink(dst); err == nil && cur == src {
		return
	}
	if err := os.Remove(dst); err != nil && !errors.Is(err, fs.ErrNotExist) {
		slog.Warn("Could not remove a stale link", "path", dst, "err", err)
	}
	if err := os.Symlink(src, dst); err != nil {
		slog.Warn("Could not link the binary", "src", src, "dst", dst, "err", err)
	}
}
