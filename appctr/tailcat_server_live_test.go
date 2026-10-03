package appctr

import (
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestTailcatServerLive serves through the bridge and connects with the
// tailcat CLI, so it runs only when asked, like TestTailcatLive:
//
//	TC_BIN=/path/to/tailcat go test -ldflags=-checklinkname=0 -run TestTailcatServerLive -v .
//
// It covers the persistent address, a port, the exit node, an allowed and a
// refused client key, and stopping. Something must answer HTTP on
// 127.0.0.1:28000.
func TestTailcatServerLive(t *testing.T) {
	bin := os.Getenv("TC_BIN")
	if bin == "" {
		t.Skip("TC_BIN not set")
	}
	dir := t.TempDir()
	keyFile := dir + "/server.json"
	addr, err := TailcatServerCreateKey(keyFile)
	if err != nil {
		t.Fatal(err)
	}
	if got := TailcatServerAddress(keyFile); got != addr {
		t.Fatalf("the address read back differs")
	}

	// Two allowed clients, each with the CLI's saved client key in a home of
	// its own: two clients with one key would knock each other off.
	client := func() (home, pub string) {
		home = t.TempDir()
		gk := exec.Command(bin, "genkey", "--client", "--key=client-default")
		gk.Env = append(os.Environ(), "HOME="+home)
		out, err := gk.Output()
		if err != nil {
			t.Fatalf("genkey: %v", err)
		}
		return home, strings.TrimSpace(string(out))
	}
	home, pub := client()
	home2, pub2 := client()

	if err := TailcatServerStart(keyFile, "28000", true, pub+"\n"+pub2, false); err != nil {
		t.Fatal(err)
	}
	defer TailcatServerStop()
	for i := 0; i < 150 && !strings.Contains(TailcatServerStatusJSON(), `"serving"`); i++ {
		time.Sleep(200 * time.Millisecond)
	}
	if st := TailcatServerStatusJSON(); !strings.Contains(st, `"serving"`) {
		t.Fatalf("not serving: %s\n%s", st, TailcatServerLog())
	}

	run := func(home string, args ...string) *exec.Cmd {
		c := exec.Command(bin, args...)
		c.Env = append(os.Environ(), "HOME="+home)
		if err := c.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { c.Process.Kill(); c.Wait() })
		return c
	}
	// tries: a fresh CLI client needs a moment to bring its tunnel up.
	get := func(url string, proxy string, tries int) string {
		tr := &http.Transport{}
		if proxy != "" {
			tr.Proxy = func(*http.Request) (*urlT, error) { return parseURL(proxy) }
		}
		c := http.Client{Timeout: 20 * time.Second, Transport: tr}
		var last string
		for i := 0; i < tries; i++ {
			r, err := c.Get(url)
			if err == nil {
				b, _ := io.ReadAll(r.Body)
				r.Body.Close()
				return r.Status + " " + strings.TrimSpace(string(b))
			}
			last = "ERR " + err.Error()
			time.Sleep(time.Second)
		}
		return last
	}

	run(home, "forward", addr, "18780:28000")
	if got := get("http://127.0.0.1:18780/", "", 8); !strings.HasPrefix(got, "200 ") {
		t.Errorf("a served port: %s", got)
	}
	run(home2, "socks", "--listen=127.0.0.1:18781", addr)
	for _, u := range []string{"http://127.0.0.1:28000/", "http://example.com/"} {
		if got := get(u, "socks5h://127.0.0.1:18781", 8); !strings.HasPrefix(got, "200 ") {
			t.Errorf("through the exit node to %s: %s", u, got)
		}
	}
	if t.Failed() {
		t.Logf("server log:\n%s", TailcatServerLog())
	}
	if st := TailcatServerStatusJSON(); !strings.Contains(st, `"clients":2`) {
		t.Errorf("two clients should show: %s", st)
	}

	// A key the server does not list: an ephemeral one.
	run(t.TempDir(), "forward", "--key=new", addr, "18782:28000")
	if got := get("http://127.0.0.1:18782/", "", 1); strings.HasPrefix(got, "200 ") {
		t.Errorf("a client key the server does not allow got through")
	}

	TailcatServerStop()
	if st := TailcatServerStatusJSON(); st != "{}" {
		t.Errorf("after stopping: %s", st)
	}
	if !strings.Contains(GetLogs(), "[TAILCAT] server: serving at") {
		t.Errorf("the shared log has no server lines")
	}
}

type urlT = url.URL

func parseURL(s string) (*url.URL, error) { return url.Parse(s) }
