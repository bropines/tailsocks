package appctr

// tunprobe.go — native-TUN experiment 1: hand a duplicate of the VpnService
// TUN fd to a short-lived tailscaled child and ask what it can do with it
// (TUNGETIFF, SIOCGIFMTU). The child is the same binary, UID and SELinux
// domain the real daemon runs under, so its answer is the daemon's answer.

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"time"
)

// ProbeTunFd runs the probe on fd and returns the child's report, one
// "tunfd-probe: key=value" line per finding, also logged. Takes ownership of
// fd: the caller passes a duplicate and keeps its own for hev.
func ProbeTunFd(fd int32) string {
	f := os.NewFile(uintptr(fd), "vpn-tun")
	if f == nil {
		return "tunfd-probe: invalid fd"
	}
	defer f.Close()

	p := getPC()
	if p.Tailscaled() == "" {
		return "tunfd-probe: daemon path not configured"
	}
	if _, err := os.Stat(p.Tailscaled()); err != nil {
		linkBinaries(p)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c := exec.CommandContext(ctx, p.Tailscaled())
	c.Dir = p.DataDir()
	c.Env = append(os.Environ(), "TS_TUN_FD_PROBE=3")
	c.ExtraFiles = []*os.File{f} // fd 3 in the child
	var out bytes.Buffer
	c.Stdout, c.Stderr = &out, &out
	started := time.Now()
	err := c.Run()

	report := strings.TrimSpace(out.String())
	for _, line := range strings.Split(report, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			slog.Info("TUN fd probe", "child", line)
		}
	}
	if err != nil {
		slog.Warn("TUN fd probe: child did not finish cleanly", "err", err, "ms", time.Since(started).Milliseconds())
		report += fmt.Sprintf("\ntunfd-probe: exit=%v", err)
	} else {
		slog.Info("TUN fd probe finished", "ms", time.Since(started).Milliseconds())
	}
	return report
}
