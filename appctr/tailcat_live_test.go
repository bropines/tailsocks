package appctr

import (
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestTailcatLive drives the bridge against a real `tailcat serve` and the
// relays at tailcat.dev, so it runs only when asked:
//
//	TC_BIN=/path/to/tailcat go test -ldflags=-checklinkname=0 -run TestTailcatLive -v .
//
// It covers a server that starts after the client, forwarding, and a client
// key the server's --allow leaves out. Something must answer HTTP on
// 127.0.0.1:28000 (python3 -m http.server 28000 --bind 127.0.0.1).
func TestTailcatLive(t *testing.T) {
	bin := os.Getenv("TC_BIN")
	if bin == "" {
		t.Skip("TC_BIN not set")
	}
	priv := TailcatGenerateClientKey()
	pub := TailcatPublicKey(priv)
	home := t.TempDir()
	addrFile := home + "/addr"
	gk := exec.Command(bin, "genkey", "--key=default", "--fixed-region")
	gk.Env = append(os.Environ(), "HOME="+home)
	if out, err := gk.CombinedOutput(); err != nil {
		t.Fatalf("genkey: %v %s", err, out)
	}
	serve := func() *exec.Cmd {
		os.Remove(addrFile)
		srv := exec.Command(bin, "serve", "--key=default", "--allow="+pub, "28000")
		srv.Env = append(os.Environ(), "TAILCAT_ADDR_FILE="+addrFile, "HOME="+home)
		if err := srv.Start(); err != nil {
			t.Fatal(err)
		}
		return srv
	}
	waitAddr := func() string {
		for i := 0; i < 100; i++ {
			b, _ := os.ReadFile(addrFile)
			if a := strings.TrimSpace(string(b)); a != "" {
				return a
			}
			time.Sleep(200 * time.Millisecond)
		}
		t.Fatal("no address")
		return ""
	}
	get := func(port string) string {
		c := http.Client{Timeout: 15 * time.Second}
		r, err := c.Get("http://127.0.0.1:" + port + "/")
		if err != nil {
			return "ERR " + err.Error()
		}
		defer r.Body.Close()
		b, _ := io.ReadAll(r.Body)
		s := strings.TrimSpace(string(b))
		return r.Status + " " + s[:min(60, len(s))]
	}
	waitState := func(id, want string, d time.Duration) {
		start := time.Now()
		for time.Since(start) < d {
			if strings.Contains(TailcatStatusJSON(), `"`+id+`":{"state":"`+want+`"`) {
				t.Logf("%s %s after %v", id, want, time.Since(start).Round(time.Millisecond))
				return
			}
			time.Sleep(200 * time.Millisecond)
		}
		t.Fatalf("%s not %s after %v: %s", id, want, d, TailcatStatusJSON())
	}

	srv := serve()
	addr := waitAddr()
	srv.Process.Kill()
	srv.Wait()

	SetTailcatCacheDir(t.TempDir())
	if err := TailcatStart("a", addr, priv, "18765:28000"); err != nil {
		t.Fatal(err)
	}
	defer TailcatStop("a")
	waitState("a", "error", 40*time.Second)

	srv = serve()
	defer srv.Process.Kill()
	if a2 := waitAddr(); a2 != addr {
		t.Fatalf("server address changed")
	}
	waitState("a", "forwarding", 45*time.Second)
	if got := get("18765"); !strings.HasPrefix(got, "200 ") {
		t.Errorf("through the tunnel: %s", got)
	}

	if err := TailcatStart("b", addr, TailcatGenerateClientKey(), "18766:28000"); err != nil {
		t.Fatal(err)
	}
	waitState("b", "error", 30*time.Second)
	if got := get("18766"); strings.HasPrefix(got, "200 ") {
		t.Errorf("a key the server does not allow got through")
	}
	if st := TailcatStatusJSON(); strings.Contains(st, "deadline") {
		t.Errorf("a server that never answered should leave the error to the app: %s", st)
	}
	TailcatStop("b")
	TailcatStop("a")
	if st := TailcatStatusJSON(); st != "{}" {
		t.Errorf("after stopping: %s", st)
	}
}
