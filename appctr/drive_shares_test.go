package appctr

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"sync"
	"testing"

	"tailscale.com/drive"
	"tailscale.com/drive/driveimpl"
)

// fakeShareDaemon answers drive/shares the way the daemon's LocalAPI does:
// names kept lower case, DELETE with the name as its body, anything under the
// path a 404.
type fakeShareDaemon struct {
	mu      sync.Mutex
	shares  map[string]string // name → path
	deleted []string
	put     []string
}

func (d *fakeShareDaemon) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if r.URL.Path != "/localapi/v0/drive/shares" {
		http.NotFound(w, r)
		return
	}
	switch r.Method {
	case "GET":
		var out []drive.Share
		for n, p := range d.shares {
			out = append(out, drive.Share{Name: n, Path: p})
		}
		json.NewEncoder(w).Encode(out)
	case "PUT":
		var s drive.Share
		json.NewDecoder(r.Body).Decode(&s)
		n, err := drive.NormalizeShareName(s.Name)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		d.shares[n] = s.Path
		d.put = append(d.put, n)
		w.WriteHeader(http.StatusCreated)
	case "DELETE":
		b, _ := io.ReadAll(r.Body)
		n, _ := drive.NormalizeShareName(string(b))
		if _, ok := d.shares[n]; !ok {
			http.NotFound(w, r)
			return
		}
		delete(d.shares, n)
		d.deleted = append(d.deleted, n)
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func serveFakeShareDaemon(t *testing.T, d *fakeShareDaemon) {
	t.Helper()
	dir := t.TempDir()
	sock := filepath.Join(dir, "d.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: d}
	go srv.Serve(ln)
	fs, err := driveimpl.NewFileServer()
	if err != nil {
		t.Fatal(err)
	}
	go fs.Serve()
	stateMu.Lock()
	externalSocketPath = sock
	PC = newPathControl(filepath.Join(dir, "libtailscale.so"), sock, filepath.Join(dir, "state"))
	stateMu.Unlock()
	driveServerMu.Lock()
	driveServer = fs
	driveServerMu.Unlock()
	t.Cleanup(func() {
		driveServerMu.Lock()
		driveServer = nil
		driveServerMu.Unlock()
		fs.Close()
		stateMu.Lock()
		externalSocketPath = ""
		PC = pathControl{}
		stateMu.Unlock()
		closeLocalClient()
		srv.Close()
	})
}

// The POCO's case: the daemon listed a share the app had removed long ago,
// and the app's "Taildrop" was taken off and put back on every apply.
func TestUpdateDriveSharesRemovesWhatIsGoneAndKeepsTheRest(t *testing.T) {
	d := &fakeShareDaemon{shares: map[string]string{
		"downloads": "/storage/emulated/0/Download",
		"sdcard":    "/storage/emulated/0",
		"taildrop":  "/storage/emulated/0/Download/Taildrop",
	}}
	serveFakeShareDaemon(t, d)

	err := UpdateDriveShares(`[{"name":"sdcard","path":"/storage/emulated/0"},{"name":"Taildrop","path":"/storage/emulated/0/Download/Taildrop"}]`)
	if err != nil {
		t.Fatal(err)
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	var names []string
	for n := range d.shares {
		names = append(names, n)
	}
	sort.Strings(names)
	if strings.Join(names, ",") != "sdcard,taildrop" {
		t.Errorf("the daemon shares %v, want sdcard and taildrop", names)
	}
	if !slices.Equal(d.deleted, []string{"downloads"}) {
		t.Errorf("deleted %v, want only the share that is gone", d.deleted)
	}
}

func TestDeleteDriveShareSendsTheNameAsTheBody(t *testing.T) {
	d := &fakeShareDaemon{shares: map[string]string{"old share": "/x"}}
	serveFakeShareDaemon(t, d)
	if err := DeleteDriveShare("Old Share"); err != nil {
		t.Fatal(err)
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.shares) != 0 {
		t.Errorf("still shared: %v", d.shares)
	}
}
