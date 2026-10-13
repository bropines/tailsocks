package appctr

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
)

// remoteForwardingDaemon is a daemon that knows the remote-management port
// (patch 22): the fake daemon, plus the one LocalAPI path it lacks.
type remoteForwardingDaemon struct {
	*remoteFakeDaemon

	mu sync.Mutex
	// answer is the body it gives; err is given instead when set.
	answer string
	err    error
	asked  int
}

func (d *remoteForwardingDaemon) get(path string) ([]byte, error) {
	if path != remoteForwardPath {
		return d.remoteFakeDaemon.get(path)
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.asked++
	if d.err != nil {
		return nil, d.err
	}
	return []byte(d.answer), nil
}

func (d *remoteForwardingDaemon) say(answer string, err error) {
	d.mu.Lock()
	d.answer, d.err = answer, err
	d.mu.Unlock()
}

func (d *remoteForwardingDaemon) times() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.asked
}

func remoteForwardAnswer(port int, forwarded bool) string {
	return fmt.Sprintf(`{"Port":%d,"Forwarded":%v}`, port, forwarded)
}

// Whether peers can get in is the daemon's word, never a guess from its mode.
func TestRemoteInbound(t *testing.T) {
	tun, userspace := remoteSelf{NodeID: "nSELFtv", TUN: true}, remoteSelf{NodeID: "nSELFtv"}
	tests := []struct {
		name    string
		self    remoteSelf
		selfErr error
		answer  string
		err     error
		want    string
		// asked: the daemon is asked about the port only on a real TUN.
		asked int
	}{
		{name: "no status", selfErr: ErrDaemonNotRunning, want: remoteInboundUnknown},
		{name: "not logged in", selfErr: errRemoteNoSelf, want: remoteInboundUnknown},
		{name: "userspace", self: userspace, answer: remoteForwardAnswer(RemotePort, false), want: remoteInboundNetstack},
		{name: "tun, port kept", self: tun, answer: remoteForwardAnswer(RemotePort, true), want: remoteInboundNetstack, asked: 1},
		{name: "tun, switch not seen", self: tun, answer: remoteForwardAnswer(RemotePort, false), want: remoteInboundNone, asked: 1},
		// A daemon built for another port forwards nothing this listener gets.
		{name: "tun, another port", self: tun, answer: remoteForwardAnswer(RemotePort+1, true), want: remoteInboundNone, asked: 1},
		// An older daemon — Root Mode's, still running after an update.
		{name: "tun, daemon without the patch", self: tun, err: errors.New("HTTP 404: no such LocalAPI path"), want: remoteInboundNone, asked: 1},
		{name: "tun, nonsense", self: tun, answer: "<html>", want: remoteInboundNone, asked: 1},
		{name: "tun, empty", self: tun, answer: "{}", want: remoteInboundNone, asked: 1},
		{name: "tun, daemon gone meanwhile", self: tun, err: ErrDaemonNotRunning, want: remoteInboundUnknown, asked: 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			d := &remoteForwardingDaemon{remoteFakeDaemon: newRemoteFakeDaemon(), answer: tt.answer, err: tt.err}
			m := newRemoteManager(remoteDefaultPolicy(), newRemoteFakeClock(), d)
			if got := m.inbound(tt.self, tt.selfErr); got != tt.want {
				t.Errorf("inbound = %q, want %q", got, tt.want)
			}
			if got := d.times(); got != tt.asked {
				t.Errorf("the daemon was asked about the port %d times, want %d", got, tt.asked)
			}
		})
	}
}

// The screen's document follows the daemon on a real TUN: reachable while it
// keeps the port, and the moment it stops — the switch — not reachable.
func TestRemoteStateOnATUN(t *testing.T) {
	r := newRemoteRig(t)
	d := &remoteForwardingDaemon{remoteFakeDaemon: r.d, answer: remoteForwardAnswer(RemotePort, true)}
	r.m.api = d
	r.d.set(func(d *remoteFakeDaemon) { d.tun = true })
	r.forgetSelf()

	if st := r.m.state(); st.Inbound != remoteInboundNetstack || !st.Listening {
		t.Errorf("a TUN whose daemon keeps the port: %+v", st)
	}
	// No answer is remembered: the next reading asks again.
	d.say(remoteForwardAnswer(RemotePort, false), nil)
	if st := r.m.state(); st.Inbound != remoteInboundNone {
		t.Errorf("after the daemon let go of the port: %q", st.Inbound)
	}
	d.say(remoteForwardAnswer(RemotePort, true), nil)
	if st := r.m.state(); st.Inbound != remoteInboundNetstack {
		t.Errorf("after the daemon took the port again: %q", st.Inbound)
	}
	if got := d.times(); got != 3 {
		t.Errorf("the daemon was asked %d times in three readings", got)
	}

	// Nothing else depends on the mode: a caller is still the node the proxy
	// map names for a loopback socket, and still needs the code and the token.
	token := r.pair(rtPhone)
	if a := r.call(rtPhone, "GET", "/v1/status", token, nil); a.status != 200 {
		t.Errorf("a paired device on a TUN: %d %s", a.status, a.raw)
	}
	r.want(r.call(rtFriend, "GET", "/v1/status", token, nil), 401, "unauthorized")
}

// The daemon's half of the port is a patch, compiled separately and never
// imported here. These are the three things both halves must spell alike.
func TestRemoteForwardPatchAgrees(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("patches", "22-android-remote-port.patch"))
	if err != nil {
		t.Fatal(err)
	}
	patch := string(raw)
	for what, line := range map[string]string{
		"the port":             "+\tremoteManagementPort = " + strconv.Itoa(RemotePort) + "\n",
		"the LocalAPI path":    "+\tlocalapi.Register(" + strconv.Quote(strings.TrimPrefix(remoteForwardPath, "/localapi/v0/")) + ", serveRemoteManagementPort)\n",
		"the switch directory": "+\tremoteSwitchDir  = " + strconv.Quote(filepath.Base(filepath.Dir(remoteStorePath(&StartOptions{SocketPath: "/d/files/tailscaled.sock", StatePath: "/d/files/states/1"})))) + "\n",
	} {
		if !strings.Contains(patch, line) {
			t.Errorf("the daemon patch does not agree on %s: no line %q", what, line)
		}
	}

	// The switch itself is the app's file; the bridge never opens it.
	kt, err := os.ReadFile(filepath.Join("..", "app", "src", "main", "java", "io", "github", "bropines", "tailscaled", "core", "RemoteManage.kt"))
	if err != nil {
		t.Skipf("the app's sources are not beside the bridge: %v", err)
	}
	for _, name := range []string{"remoteSwitchDir", "remoteSwitchName"} {
		_, rest, ok := strings.Cut(patch, "+\t"+name)
		value, _, _ := strings.Cut(rest, "\n")
		value = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(value), "="))
		if !ok || len(value) < 3 {
			t.Errorf("the daemon patch names no %s", name)
			continue
		}
		if !strings.Contains(string(kt), "= "+value+"\n") {
			t.Errorf("RemoteManage.kt has no constant equal to the patch's %s = %s", name, value)
		}
	}
}
