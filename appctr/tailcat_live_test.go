package appctr

import (
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

// TestTailcatLive drives the bridge against a real `tailcat serve` and the
// relays at tailcat.dev, so it runs only when asked:
//
//	TC_BIN=/path/to/tailcat go test -ldflags=-checklinkname=0 -run TestTailcatLive -v .
//
// It covers a server that starts after the client, forwarding, a server
// that restarts under a running client, the SOCKS5
// proxy and its password, the shared log, and a client key the server's
// --allow leaves out. Something must answer HTTP on
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
	if err := TailcatStart("a", "a", addr, priv, "18765:28000", 18767, "u", "p"); err != nil {
		t.Fatal(err)
	}
	defer TailcatStop("a")
	waitState("a", "error", 40*time.Second)

	srv = serve()
	defer func() { srv.Process.Kill() }()
	if a2 := waitAddr(); a2 != addr {
		t.Fatalf("server address changed")
	}
	waitState("a", "forwarding", 45*time.Second)
	if got := get("18765"); !strings.HasPrefix(got, "200 ") {
		t.Errorf("through the tunnel: %s", got)
	}

	// The server restarts with the same key and remembers no client: the
	// next dial registers again (dialRetry) and goes through.
	srv.Process.Kill()
	srv.Wait()
	srv = serve()
	waitAddr()
	time.Sleep(3 * time.Second)
	if got := get("18765"); !strings.HasPrefix(got, "200 ") {
		t.Errorf("after the server restarted: %s", got)
	}
	viaSOCKS := func(user, pass string) string {
		d, err := proxy.SOCKS5("tcp", "127.0.0.1:18767", &proxy.Auth{User: user, Password: pass}, proxy.Direct)
		if err != nil {
			return "ERR " + err.Error()
		}
		c := http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{Dial: d.Dial}}
		r, err := c.Get("http://server.tailcat:28000/")
		if err != nil {
			return "ERR " + err.Error()
		}
		defer r.Body.Close()
		return r.Status
	}
	if got := viaSOCKS("u", "p"); !strings.HasPrefix(got, "200 ") {
		t.Errorf("through SOCKS5: %s", got)
	}
	if got := viaSOCKS("u", "wrong"); strings.HasPrefix(got, "200 ") {
		t.Errorf("SOCKS5 let a wrong password through")
	}
	if st := TailcatStatusJSON(); !strings.Contains(st, `"socks":"127.0.0.1:18767"`) {
		t.Errorf("status without the proxy: %s", st)
	}
	if !strings.Contains(GetLogs(), "[TAILCAT] a: the server answered") {
		t.Errorf("the shared log has no tailcat lines")
	}

	if err := TailcatStart("b", "b", addr, TailcatGenerateClientKey(), "18766:28000", 0, "", ""); err != nil {
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
