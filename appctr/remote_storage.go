package appctr

// remote_storage.go — the device's storage by name, "send me this file", and
// the folders the device shares. All of it but removing a share takes full
// control, and all of it is the owner's.
//
// No file's bytes travel over the management channel, in either direction. A
// listing is names, sizes and dates. "Send me this file" makes the device send
// the file to the node that asked with its own Taildrop — the daemon's
// transfer to that node's identity, with the tailnet's own verdict on whether
// the two may exchange files — and it arrives there like any other.
//
// What may be named at all is decided twice. The app knows the roots: the
// storage volumes it can read (core/FileBrowse.kt), never its own private
// directory. It resolves a path, refuses one that leaves the roots by ".." or
// through a link, and hands back the resolved one. The bridge then holds that
// answer to its own rule — under one of remoteStorageRoots, no link anywhere
// in it, a regular file — before anything is opened: the pairings, the keys
// and the state live under /data, and no answer of the app can name them.

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	remoteMaxPath    = 1024
	remoteMaxEntries = 500
	remoteMaxRoots   = 16
	remoteMaxFetches = 64
	// Looking around is logged, once in a while rather than per folder.
	remoteBrowseLogEvery = time.Hour
)

// remoteStorageRoots is where a file the device sends may be: shared storage
// and removable volumes, as Android mounts them. Tests name their own.
var remoteStorageRoots = []string{"/storage/", "/mnt/"}

// remoteStoragePathOK reports whether p can be a path of a request at all:
// absolute, already clean, nothing a listing would not have printed.
func remoteStoragePathOK(p string) bool {
	if p == "" || len(p) > remoteMaxPath || !utf8.ValidString(p) || !filepath.IsAbs(p) {
		return false
	}
	for _, r := range p {
		if r == 0 || unicode.IsControl(r) || r == '\\' {
			return false
		}
	}
	if filepath.Clean(p) != p {
		return false
	}
	for _, part := range strings.Split(p, "/") {
		if part == ".." {
			return false
		}
	}
	return true
}

// remoteUnderRoots reports whether p is inside one of the roots, with no link
// on the way: what the path says is where the file is. missing is for a path
// that is inside them, by a folder that is, and names nothing.
func remoteUnderRoots(p string, roots []string) (ok, missing bool) {
	inside := false
	for _, root := range roots {
		inside = inside || strings.HasPrefix(p, root)
	}
	if !inside {
		return false, false
	}
	real, err := filepath.EvalSymlinks(p)
	if err == nil {
		return real == p, false
	}
	if !os.IsNotExist(err) {
		return false, false
	}
	// Nothing there. Said only of a place the roots really hold: that a name
	// is free behind a link is nobody's to learn.
	dir := filepath.Dir(p)
	real, err = filepath.EvalSymlinks(dir)
	return false, err == nil && real == dir
}

// remoteEntryNameOK reports whether name is one element of a listing a person
// would be shown: one path element, nothing hidden.
func remoteEntryNameOK(name string) bool {
	if name == "" || len(name) > 255 || !utf8.ValidString(name) || strings.HasPrefix(name, ".") {
		return false
	}
	for _, r := range name {
		if r == '/' || r == 0 || unicode.IsControl(r) {
			return false
		}
	}
	return true
}

type remoteStorageRoot struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	Free  int64  `json:"free"`
	Total int64  `json:"total"`
}

type remoteStorageEntry struct {
	Name string `json:"name"`
	Dir  bool   `json:"dir"`
	Size int64  `json:"size"`
	// Time is the modification time, Unix seconds.
	Time int64 `json:"time"`
}

// remoteStorageListing is what the app answers to storage_list and, checked,
// what the caller gets: the roots for an empty path, a folder otherwise.
type remoteStorageListing struct {
	Path   string              `json:"path"`
	Parent string              `json:"parent,omitempty"`
	Roots  []remoteStorageRoot `json:"roots,omitempty"`
	// Entries are folders first, then files, by name.
	Entries []remoteStorageEntry `json:"entries"`
	// Truncated: there were more than remoteMaxEntries. Limited: the app has
	// no access to storage, and sees only its own files here.
	Truncated bool `json:"truncated"`
	Limited   bool `json:"limited"`
}

// query asks the app one of remoteRequests that changes nothing. A call that
// was not made or not answered is all one to a caller who only wanted to
// read: unavailable.
func (m *remoteManager) query(name, args string) (string, int, any) {
	v, _, err := m.callDelegate(func(d RemoteDelegate) (string, error) { return d.Request(name, args) })
	if err == nil {
		return v, http.StatusOK, nil
	}
	status, body := remoteDelegateRefusal(err)
	if status == http.StatusGatewayTimeout {
		status, body = http.StatusServiceUnavailable, remoteError{Error: "unavailable", Message: "the app is not answering"}
	}
	return "", status, body
}

func remotePathArgs(path string) string {
	b, _ := json.Marshal(struct {
		Path string `json:"path"`
	}{path})
	return string(b)
}

func (m *remoteManager) handleStorageList(rq *remoteRequest) (int, any) {
	var in struct {
		Path string `json:"path"`
	}
	if !remoteDecode(rq.body, &in) || (in.Path != "" && !remoteStoragePathOK(in.Path)) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	raw, status, refusal := m.query(remoteRequestStorageList, remotePathArgs(in.Path))
	if status != http.StatusOK {
		return status, refusal
	}
	var got remoteStorageListing
	if len(raw) > remoteMaxAnswer/2 || json.Unmarshal([]byte(raw), &got) != nil {
		return http.StatusBadGateway, remoteError{Error: "failed", Message: "the app's listing could not be read"}
	}
	m.noteBrowse(rq)
	out := remoteStorageListing{Entries: []remoteStorageEntry{}, Limited: got.Limited}
	if in.Path == "" {
		for _, r := range got.Roots {
			if len(out.Roots) == remoteMaxRoots {
				break
			}
			if !remoteStoragePathOK(r.Path) {
				continue
			}
			out.Roots = append(out.Roots, remoteStorageRoot{Name: remoteClean(r.Name, 64), Path: r.Path, Free: max(r.Free, 0), Total: max(r.Total, 0)})
		}
		return http.StatusOK, struct {
			Path  string              `json:"path"`
			Roots []remoteStorageRoot `json:"roots"`
		}{"", append([]remoteStorageRoot{}, out.Roots...)}
	}
	// The app names the folder it listed — the path with its links resolved —
	// and that is the one the caller goes on from.
	if !remoteStoragePathOK(got.Path) {
		return http.StatusBadGateway, remoteError{Error: "failed", Message: "the app's listing could not be read"}
	}
	out.Path = got.Path
	if got.Parent != "" && remoteStoragePathOK(got.Parent) {
		out.Parent = got.Parent
	}
	for _, e := range got.Entries {
		if !remoteEntryNameOK(e.Name) {
			continue
		}
		if len(out.Entries) == remoteMaxEntries {
			out.Truncated = true
			break
		}
		if e.Dir {
			e.Size = 0
		}
		out.Entries = append(out.Entries, remoteStorageEntry{Name: e.Name, Dir: e.Dir, Size: max(e.Size, 0), Time: max(e.Time, 0)})
	}
	out.Truncated = out.Truncated || got.Truncated
	sort.SliceStable(out.Entries, func(i, j int) bool {
		a, b := out.Entries[i], out.Entries[j]
		if a.Dir != b.Dir {
			return a.Dir
		}
		return strings.ToLower(a.Name) < strings.ToLower(b.Name)
	})
	return http.StatusOK, out
}

// noteBrowse writes one line to the action log when a device starts looking
// at the storage, and no more for a while: the log is short, and a line per
// folder would push everything else out of it.
func (m *remoteManager) noteBrowse(rq *remoteRequest) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		return
	}
	now := m.clock.Now().Unix()
	a := m.store.data.Audit
	for i := len(a) - 1; i >= 0; i-- {
		if a[i].Op == "browse" && a[i].NodeID == rq.caller.NodeID {
			if d := now - a[i].Time; d >= 0 && d < int64(remoteBrowseLogEvery/time.Second) {
				return
			}
			break
		}
	}
	m.auditLocked(remoteEvent{NodeID: rq.caller.NodeID, Name: rq.caller.Name, Class: rq.class.String(), Op: "browse", OK: true})
	m.saveLocked()
}

// remoteFetch is a file on its way to the device that asked for it.
type remoteFetch struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Size int64  `json:"size"`
	// State is "sending", "sent" or "failed"; Error says why it failed.
	State string `json:"state"`
	Error string `json:"error,omitempty"`
}

// fetchFor is the calling node's last transfer, for its status.
func (m *remoteManager) fetchFor(nodeID string) *remoteFetch {
	m.mu.Lock()
	defer m.mu.Unlock()
	f := m.fetches[nodeID]
	if f == nil {
		return nil
	}
	cp := *f
	return &cp
}

func (m *remoteManager) handleStorageSend(rq *remoteRequest) (int, any) {
	var in struct {
		Path string `json:"path"`
	}
	if !remoteDecode(rq.body, &in) || !remoteStoragePathOK(in.Path) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	node := rq.caller.NodeID
	m.mu.Lock()
	busy := m.fetches[node] != nil && m.fetches[node].State == "sending"
	m.mu.Unlock()
	if busy {
		return http.StatusConflict, remoteError{Error: "refused", Message: "a file is already on its way to this device"}
	}
	raw, status, refusal := m.query(remoteRequestStorageFile, remotePathArgs(in.Path))
	if status != http.StatusOK {
		return status, refusal
	}
	var got struct {
		Path string `json:"path"`
	}
	if json.Unmarshal([]byte(raw), &got) != nil || !remoteStoragePathOK(got.Path) {
		return http.StatusBadGateway, remoteError{Error: "failed", Message: "the app's answer could not be read"}
	}
	// The app's word on where the file is, held to the bridge's own rule.
	if ok, missing := remoteUnderRoots(got.Path, remoteStorageRoots); !ok {
		if missing {
			return http.StatusNotFound, remoteError{Error: "not_found"}
		}
		m.noteDenied(rq.caller, rq.class, "a file outside the storage: "+remoteClean(filepath.Base(got.Path), 60))
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "not a file on this device's storage"}
	}
	fi, err := os.Lstat(got.Path)
	if err != nil {
		return http.StatusNotFound, remoteError{Error: "not_found"}
	}
	if !fi.Mode().IsRegular() {
		return http.StatusConflict, remoteError{Error: "refused", Message: "not a file that can be sent"}
	}
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	f := &remoteFetch{ID: "t" + hex.EncodeToString(b[:]), Name: filepath.Base(got.Path), Size: fi.Size(), State: "sending"}
	m.mu.Lock()
	if cur := m.fetches[node]; cur != nil && cur.State == "sending" {
		m.mu.Unlock()
		return http.StatusConflict, remoteError{Error: "refused", Message: "a file is already on its way to this device"}
	}
	if m.fetches == nil || len(m.fetches) >= remoteMaxFetches {
		kept := map[string]*remoteFetch{}
		for k, v := range m.fetches {
			if v.State == "sending" {
				kept[k] = v
			}
		}
		m.fetches = kept
	}
	m.fetches[node] = f
	send := m.sendFile
	m.mu.Unlock()

	answer := *f
	go func() {
		// The daemon's own transfer to the asking node: Taildrop decides
		// whether the two may exchange files at all.
		res := send(node, got.Path)
		ok := res == "OK"
		m.mu.Lock()
		if ok {
			f.State = "sent"
		} else {
			f.State = "failed"
			f.Error = remoteClean(strings.TrimPrefix(res, "Error: "), 200)
		}
		m.mu.Unlock()
		m.record(rq, "file_sent", f.Name, ok)
	}()
	return http.StatusOK, struct {
		ID   string `json:"id"`
		Name string `json:"name"`
		Size int64  `json:"size"`
	}{answer.ID, answer.Name, answer.Size}
}

// ─── Shared folders ─────────────────────────────────────────────────────────

// remoteShareName is what Taildrive takes for a share's name.
var remoteShareName = regexp.MustCompile(`^[A-Za-z0-9_]{1,40}$`)

func (m *remoteManager) handleShareAdd(rq *remoteRequest) (int, any) {
	var in struct {
		Name string `json:"name"`
		Path string `json:"path"`
	}
	if !remoteDecode(rq.body, &in) || !remoteShareName.MatchString(in.Name) || !remoteStoragePathOK(in.Path) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	args, _ := json.Marshal(in)
	_, status, v := m.changeTold(rq, "share_added", in.Name, func(d RemoteDelegate) (string, error) {
		return d.Request(remoteRequestShareAdd, string(args))
	}, nil)
	return status, v
}

func (m *remoteManager) handleShareRemove(rq *remoteRequest) (int, any) {
	var in struct {
		Name string `json:"name"`
	}
	if !remoteDecode(rq.body, &in) || !remoteShareName.MatchString(in.Name) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	args, _ := json.Marshal(in)
	_, status, v := m.changeTold(rq, "share_removed", in.Name, func(d RemoteDelegate) (string, error) {
		return d.Request(remoteRequestShareRemove, string(args))
	}, nil)
	return status, v
}
