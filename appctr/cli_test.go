package appctr

import (
	"net"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// The combined binary picks its role from argv[0]: anything but "tailscale"
// starts a daemon. Whichever path the runner execs, the name must be the CLI's.
func TestCLICommandRunsUnderTheCLIName(t *testing.T) {
	dir := t.TempDir()
	lib := filepath.Join(dir, "lib")
	data := filepath.Join(dir, "data")
	for _, d := range []string{lib, data} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	so := filepath.Join(lib, "libtailscale.so")
	if err := os.WriteFile(so, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	pc := newPathControl(so, filepath.Join(data, "tailscaled.sock"), filepath.Join(data, "states", "a"))

	// No link in the data directory: the library itself, still named tailscale.
	c := cliCommand(pc, "--socket", pc.Socket(), "status")
	if c.Path != so {
		t.Errorf("without the link: Path = %q, want the library %q", c.Path, so)
	}
	if c.Args[0] != "tailscale" {
		t.Errorf("without the link: argv[0] = %q, want tailscale", c.Args[0])
	}
	if want := []string{"--socket", pc.Socket(), "status"}; !slices.Equal(c.Args[1:], want) {
		t.Errorf("args = %q, want %q", c.Args[1:], want)
	}

	// With the link: the link, so ps shows the CLI by its name too.
	if err := os.Symlink(so, pc.Tailscale()); err != nil {
		t.Fatal(err)
	}
	c = cliCommand(pc, "version")
	if c.Path != pc.Tailscale() {
		t.Errorf("with the link: Path = %q, want %q", c.Path, pc.Tailscale())
	}
	if c.Args[0] != "tailscale" {
		t.Errorf("with the link: argv[0] = %q, want tailscale", c.Args[0])
	}
}

// fakeCLI points the bridge at a "daemon" whose binary is script, with a live
// socket so IsRunning is true.
func fakeCLI(t *testing.T, script string) {
	t.Helper()
	dir := t.TempDir()
	so := filepath.Join(dir, "libtailscale.so")
	if err := os.WriteFile(so, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	sock := filepath.Join(dir, "d.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	stateMu.Lock()
	externalSocketPath = sock
	PC = newPathControl(so, sock, filepath.Join(dir, "state"))
	stateMu.Unlock()
	t.Cleanup(func() {
		ln.Close()
		stateMu.Lock()
		externalSocketPath = ""
		PC = pathControl{}
		stateMu.Unlock()
	})
}

func shortCLIDeadline(t *testing.T, d time.Duration) {
	oldD, oldW := cliDeadline, cliWaitDelay
	cliDeadline, cliWaitDelay = d, 200*time.Millisecond
	t.Cleanup(func() { cliDeadline, cliWaitDelay = oldD, oldW })
}

// A console command that does not end by itself is stopped at the deadline
// and returns what it printed; it used to block its thread for good.
func TestRunTailscaleArgsStopsAtTheDeadline(t *testing.T) {
	fakeCLI(t, "#!/bin/sh\necho watching\nsleep 30\n")
	shortCLIDeadline(t, 300*time.Millisecond)
	begun := time.Now()
	out := RunTailscaleArgs("debug", "watch-ipn")
	if d := time.Since(begun); d > 3*time.Second {
		t.Fatalf("returned after %v", d)
	}
	if !strings.Contains(out, "watching") || !strings.Contains(out, "stopped after") {
		t.Fatalf("output %q, want what it printed and the stop", out)
	}
}

func TestCancelTailscaleCmdStopsARunningCommand(t *testing.T) {
	fakeCLI(t, "#!/bin/sh\necho pinging\nexec sleep 30\n")
	shortCLIDeadline(t, time.Minute)
	got := make(chan string, 1)
	go func() { got <- RunTailscaleArgs("ping", "--c", "0", "peer") }()
	deadline := time.Now().Add(2 * time.Second)
	for {
		cliRunsMu.Lock()
		n := len(cliRuns)
		cliRunsMu.Unlock()
		if n == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the command never started")
		}
		time.Sleep(10 * time.Millisecond)
	}
	time.Sleep(100 * time.Millisecond)
	CancelTailscaleCmd()
	select {
	case out := <-got:
		if !strings.Contains(out, "[stopped]") {
			t.Errorf("output %q, want the stop", out)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the command was not stopped")
	}
	cliRunsMu.Lock()
	defer cliRunsMu.Unlock()
	if len(cliRuns) != 0 {
		t.Error("a finished command is still tracked")
	}
}

// A command that ends by itself is untouched.
func TestRunTailscaleArgsReturnsAFinishedCommand(t *testing.T) {
	fakeCLI(t, "#!/bin/sh\necho \"$@\"\n")
	shortCLIDeadline(t, time.Minute)
	out := RunTailscaleArgs("version")
	if !strings.HasSuffix(strings.TrimSpace(out), "version") || strings.Contains(out, "stopped") {
		t.Errorf("output %q", out)
	}
}
