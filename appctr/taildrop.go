package appctr

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// getLastOptions safely retrieves the last StartOptions under stateMu.
func getLastOptions() *StartOptions {
	stateMu.Lock()
	defer stateMu.Unlock()
	return lastOptions
}

// Suffixes the daemon uses for files that are not yet (or no longer) a
// received file. Mirrors feature/taildrop/taildrop.go:37 and :45.
const (
	taildropPartialSuffix = ".partial"
	taildropDeletedSuffix = ".deleted"
)

// isTaildropListable reports whether a directory entry in the Taildrop dir is
// a file the user has received. The daemon writes straight into this
// directory (direct mode), so its work-in-progress is visible next to the
// finished files and has to be hidden the way upstream's inbox listing hides it:
//
//   - regular files only — fsFileOps.ListFiles, feature/taildrop/ext.go:541-543;
//   - no *.partial / *.deleted — isPartialOrDeleted, taildrop.go:150-152,
//     applied by WaitingFiles at retrieve.go:78-80;
//   - no file that has a sibling ".deleted" marker — retrieve.go:81-84.
//
// Dot-files are ours, not upstream's: the removed collector left
// ".taildrop-*.part" temporaries behind, and the daemon never delivers a name
// starting with "." into this directory that a user would want to see
// (validateBaseName, taildrop.go:154-177, rejects "." and ".." but permits
// other dot-names, so this is a deliberate UI choice, not a protocol rule).
func isTaildropListable(dir string, e os.DirEntry) bool {
	if !e.Type().IsRegular() {
		return false
	}
	name := e.Name()
	if strings.HasPrefix(name, ".") {
		return false
	}
	if strings.HasSuffix(name, taildropPartialSuffix) || strings.HasSuffix(name, taildropDeletedSuffix) {
		return false
	}
	if _, err := os.Stat(filepath.Join(dir, name+taildropDeletedSuffix)); err == nil {
		return false
	}
	return true
}

// GetWaitingFiles scans the Taildrop directory and returns a JSON list of
// received files: [{"Name","Size","Path"}], sorted by name like upstream's
// WaitingFiles (retrieve.go:94). In-progress *.partial files, *.deleted markers
// and dot-files are not listed (see isTaildropListable). Returns "[]" when the
// directory cannot be read.
func GetWaitingFiles(dir string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "[]"
	}
	type fileInfo struct {
		Name string `json:"Name"`
		Size int64  `json:"Size"`
		Path string `json:"Path"`
	}
	files := make([]fileInfo, 0, len(entries))
	for _, e := range entries {
		if !isTaildropListable(dir, e) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, fileInfo{
			Name: e.Name(),
			Size: info.Size(),
			Path: filepath.Join(dir, e.Name()),
		})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Name < files[j].Name })
	data, _ := json.Marshal(files)
	return string(data)
}

// There is no inbox collector any more. The daemon is always started with
// TS_TAILDROP_DIR (daemon.go:101-102 for the embedded daemon, RootUtils.kt for
// the root one), which puts it in direct-file mode: it writes received files
// into that directory itself and /localapi/v0/files/ answers "null" forever
// (feature/taildrop/retrieve.go:69-71). The collector that polled that endpoint
// every 5s (and its download/DELETE path) could never see a file and has been
// removed rather than gated on an empty TS_TAILDROP_DIR, because the only thing
// that used to start it was a non-empty StartOptions.TaildropDir — the same
// value that sets TS_TAILDROP_DIR. In direct mode the daemon announces arrivals
// on the IPN bus instead (Notify.IncomingFiles); see SetTaildropListener.

// GetTaildropFilesFromAPI lists the received files of the current run's
// Taildrop directory (see GetWaitingFiles). "[]" when no run is configured.
func GetTaildropFilesFromAPI() string {
	opt := getLastOptions()
	if opt == nil || opt.TaildropDir == "" {
		return "[]"
	}
	return GetWaitingFiles(opt.TaildropDir)
}

// DeleteTaildropFileFromAPI removes one received file by its base name.
func DeleteTaildropFileFromAPI(name string) bool {
	opt := getLastOptions()
	if opt == nil || opt.TaildropDir == "" {
		return false
	}
	path := filepath.Join(opt.TaildropDir, name)
	err := os.Remove(path)
	if err != nil {
		slog.Error("Taildrop: Failed to delete file", "path", path, "err", err)
	}
	return err == nil
}

// SendFileFromAPI sends a file to a peer via LocalAPI PUT
// /localapi/v0/file-put/<peerID>/<name>. peerID is the peer's StableNodeID.
//
// The result is decided by the HTTP status the daemon returns — which, once the
// daemon has found the peer, is the peer's own answer proxied back
// (ipn/localapi/localapi.go: rp.ServeHTTP) — never by the body, which on
// success is the peer's "{}\n":
//
//	"OK"                        — 2xx: the peer accepted and stored the file.
//	"Error: HTTP <code>: <body>" — any other status. Typical: 404 "node not
//	                              found" (peer is not a file target), 403
//	                              "Taildrop disabled; no storage directory"
//	                              (peer refuses us or has nowhere to write),
//	                              409 (same name already in flight), 400.
//	"Error: <message>"          — the request never got an answer: daemon not
//	                              running, file unreadable, socket failure.
//
// Every failure starts with "Error", so callers may test that prefix alone.
func SendFileFromAPI(peerID, filePath string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
	}

	f, err := os.Open(filePath)
	if err != nil {
		return "Error: " + err.Error()
	}
	defer f.Close()

	fi, err := f.Stat()
	if err != nil {
		return "Error: " + err.Error()
	}

	name := url.PathEscape(filepath.Base(filePath))
	// PUT on the deadline-free client: the upload takes as long as the tailnet
	// path allows (a few hundred MB over DERP is well past 30s), and the 30s
	// client aborted it half-way with an error to the user. The Content-Length
	// lets the daemon announce the size to the peer, the way the CLI does.
	resp, err := doLocalStreamRaw(context.Background(), "PUT", "/localapi/v0/file-put/"+peerID+"/"+name, f, fi.Size())
	if err != nil {
		return "Error: " + err.Error()
	}
	defer resp.Body.Close()
	// The body is either "{}\n" or a one-line error; cap it so a misbehaving
	// peer cannot make us buffer more.
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return "Error: HTTP " + strconv.Itoa(resp.StatusCode) + ": " + strings.TrimSpace(string(body))
	}
	return "OK"
}

// ─── Incoming transfers (direct mode, from the IPN bus) ─────────────────────

// TaildropListener is implemented on the Kotlin side to be told about incoming
// Taildrop transfers as the daemon reports them on the IPN bus.
type TaildropListener interface {
	// OnIncomingFiles receives the JSON array of BusPartialFile that arrived in
	// one Notify.IncomingFiles — the same shape GetIncomingFilesJSON returns.
	// It is called for every Notify that carries the field, roughly once a
	// second per active transfer, plus a final one in which the finished file
	// has Done=true and FinalPath set (send.go: SendFileNotify after Rename).
	// That final entry is present in exactly one notification: the daemon
	// forgets the transfer right after it, so a caller that only polls
	// GetIncomingFilesJSON can miss it — react to Done here. An empty array
	// would mean no transfer is in progress any more, but the bus does not
	// deliver one under mask=4095 (see busStateSnapshot.IncomingFiles), so do
	// not wait for it.
	//
	// Called from the bus goroutine with no Go locks held; do the UI work
	// elsewhere and return quickly.
	OnIncomingFiles(filesJSON string)
}

var (
	taildropListenerMu sync.Mutex
	taildropListener   TaildropListener
)

// SetTaildropListener installs (or, with nil, removes) the listener that
// receives Notify.IncomingFiles updates. Only one listener is kept; a later
// call replaces the earlier one.
func SetTaildropListener(l TaildropListener) {
	taildropListenerMu.Lock()
	taildropListener = l
	taildropListenerMu.Unlock()
}

// notifyTaildropListener delivers one IncomingFiles update to the listener.
// Called by applyNotify AFTER busStateMu is released: the Java side may call
// back into appctr (GetTaildropFilesFromAPI and the like), and some of those
// take the bus lock.
func notifyTaildropListener(files []BusPartialFile) {
	taildropListenerMu.Lock()
	l := taildropListener
	taildropListenerMu.Unlock()
	if l == nil {
		return
	}
	data, err := json.Marshal(files)
	if err != nil {
		return
	}
	l.OnIncomingFiles(string(data))
}

// GetIncomingFilesJSON returns the most recent non-empty Notify.IncomingFiles
// the bus has seen as a JSON array of BusPartialFile, or "[]" when none has
// been seen since the bridge (re)started. It is a snapshot for a screen that
// opens mid-transfer, and it is stale by design once the transfers end: the
// bus never delivers an empty IncomingFiles under our watch mask (see
// busStateSnapshot.IncomingFiles), so a finished file keeps its Done=true entry
// here until the next transfer or resetBusState(). Callers must treat Done
// entries as history, not as "in flight"; for the Done=true event itself use
// SetTaildropListener (see the note there). No Kotlin caller today.
func GetIncomingFilesJSON() string {
	bs := GetBusState()
	files := bs.IncomingFiles
	if files == nil {
		files = []BusPartialFile{}
	}
	data, err := json.Marshal(files)
	if err != nil {
		return "[]"
	}
	return string(data)
}
