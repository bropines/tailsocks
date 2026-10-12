package appctr

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
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
