package appctr

// remote_app.go — what the app tells a paired device about itself, and the one
// thing a paired device may ask the app to put on the device's screen.
//
// Both go through RemoteDelegate under keys of their own. They are not
// settings: /v1/settings neither lists nor accepts them.
//
//   - remoteKeyReport is read. The app answers with its own account of what a
//     person setting the device up from afar needs to know and cannot see:
//     which permissions it holds and lacks, where received files go and
//     whether that place can be written, which received files went there, and
//     which folders it shares. It is JSON the app wrote, and it is not passed
//     on: it is decoded into the types below, cut down to the names
//     remote_policy.go lists and to lengths a screen can show, and encoded
//     again. A field the bridge does not know does not travel, and neither
//     does a permission it does not list.
//   - remoteKeyAsk is written, then read, in one call into the app: the write
//     asks for a permission's page, the read says what became of the request.
//
// A request for a permission grants nothing, and cannot: Android gives a
// permission to a person who presses a button in the system's own page or
// dialog. What the app can do is bring that page in front of whoever is at the
// device — at once when one of its screens is up, or, on Android 10 and later
// where an app in the background may not start an activity, by saying so in a
// notification and on the screen and opening the page when TailSocks is next
// opened. Which of these happened is the answer; it is never "granted".

import (
	"encoding/json"
	"net/http"
	"time"
)

const (
	remoteKeyReport = "report"
	remoteKeyAsk    = "permission_request"
)

const (
	// The report is reused for remoteReportTTL. A status answer waits
	// remoteReportWait for the app to be free and remoteReportAnswer for what
	// it says, and goes without when either runs out: it must come back while
	// the app is busy with a change, or hung.
	remoteReportTTL    = 3 * time.Second
	remoteReportWait   = 300 * time.Millisecond
	remoteReportAnswer = 2 * time.Second
	// remoteMaxReport bounds what the app may hand over.
	remoteMaxReport = 64 << 10
	// How much of each list a status answer carries.
	remoteMaxSaved   = 10
	remoteMaxFolders = 50
	// A node may ask for a permission's page three times in a row and then
	// once in twenty seconds: every request puts something in front of a
	// person, and a paired device must not be able to keep doing that.
	remoteAskRate  = 1.0 / 20
	remoteAskBurst = 3
)

// remotePermission is one permission of the app as the device's system
// reports it.
type remotePermission struct {
	ID string `json:"id"`
	// State is "granted", "missing", "unknown" — the system does not tell an
	// app — or "na": this device, or this Android, has no such permission.
	State string `json:"state"`
	// Ask: it is not granted, the device has a page or a dialog for it, and
	// this caller may ask for that to be shown.
	Ask bool `json:"ask"`
}

var remotePermissionStates = map[string]bool{"granted": true, "missing": true, "unknown": true, "na": true}

// remoteAsked is a request for a permission that still waits for a person.
type remoteAsked struct {
	ID string `json:"id"`
	// Result is "notified" or "waiting"; see remoteAskResults.
	Result string `json:"result"`
	// Age is the seconds since it was made.
	Age int64 `json:"age"`
}

// remoteAskResults is what the app may answer to a request for a permission's
// page — what happened on the device, none of which is a grant:
//
//   - shown: the system's page or dialog is in front of whoever is there.
//   - notified: TailSocks is not on screen and Android does not let it open
//     a page from the background; a notification and a line on the screen say
//     who asks for what, and the page opens from the notification or when
//     TailSocks is next opened.
//   - waiting: the same, and the device could show nothing either —
//     notifications are off there. Someone has to open TailSocks first.
var remoteAskResults = map[string]bool{"shown": true, "notified": true, "waiting": true}

// remoteReceive is where received Taildrop files go on the device.
type remoteReceive struct {
	// State is "ready": they are saved into Folder; "needs_access": Folder
	// cannot be written until the app is given access to storage;
	// "unavailable": it cannot be written whatever is granted — a drive
	// pulled out, a grant taken back; "inbox": there is no folder, and files
	// wait inside the app; "unknown" when the app said none of these.
	State string `json:"state"`
	// Folder is the folder as the device's user knows it — "Download/Taildrop"
	// — never a full path.
	Folder string `json:"folder,omitempty"`
	// Reset: the folder does not work and is not the device's default, and
	// this caller may send the action that puts the default back.
	Reset bool `json:"reset"`
}

var remoteReceiveStates = map[string]bool{"ready": true, "needs_access": true, "unavailable": true, "inbox": true}

// remoteSavedFile is a received file the receive folder took: it is no longer
// among /v1/files, which lists what waits inside the app.
type remoteSavedFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	// Time is when it arrived, Unix seconds.
	Time   int64  `json:"time"`
	Folder string `json:"folder,omitempty"`
}

// remoteFolder is a folder the device shares with the tailnet (Taildrive).
type remoteFolder struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// remoteAppReport is the app's account of itself as a status answer carries
// it.
type remoteAppReport struct {
	Permissions []remotePermission `json:"permissions"`
	Asked       *remoteAsked       `json:"asked,omitempty"`
	Receive     remoteReceive      `json:"receive"`
	Saved       []remoteSavedFile  `json:"saved"`
	// Folders are the shared folders by name and path, for an owner only;
	// FolderCount is how many there are, for everyone.
	Folders     []remoteFolder `json:"folders,omitempty"`
	FolderCount int            `json:"folderCount"`
}

// remoteParseReport reads the app's report and keeps of it only what the
// bridge knows how to check: listed permissions in the list's order with a
// known state, a known state of the receive folder, names and labels cut to
// length. nil when it is not a report at all.
func remoteParseReport(raw string) *remoteAppReport {
	if raw == "" || len(raw) > remoteMaxReport {
		return nil
	}
	var in remoteAppReport
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		return nil
	}
	out := &remoteAppReport{Permissions: []remotePermission{}, Saved: []remoteSavedFile{}}
	for _, id := range remotePermissions {
		for _, p := range in.Permissions {
			if p.ID != id {
				continue
			}
			if !remotePermissionStates[p.State] {
				p.State = "unknown"
			}
			// Nothing to ask for where it is held or does not exist, whatever
			// the app says; and never for one that is not on the list.
			p.Ask = p.Ask && remoteAskable[id] && (p.State == "missing" || p.State == "unknown")
			out.Permissions = append(out.Permissions, p)
			break
		}
	}
	if a := in.Asked; a != nil && remoteAskable[a.ID] && remoteAskResults[a.Result] && a.Result != "shown" {
		out.Asked = &remoteAsked{ID: a.ID, Result: a.Result, Age: max(a.Age, 0)}
	}
	out.Receive = remoteReceive{State: "unknown"}
	if remoteReceiveStates[in.Receive.State] {
		out.Receive.State = in.Receive.State
		out.Receive.Folder = remoteClean(in.Receive.Folder, 120)
		out.Receive.Reset = in.Receive.Reset && in.Receive.State != "ready" && in.Receive.State != "inbox"
	}
	for _, f := range in.Saved {
		if len(out.Saved) == remoteMaxSaved {
			break
		}
		if f.Name = remoteClean(f.Name, 255); f.Name == "" {
			continue
		}
		out.Saved = append(out.Saved, remoteSavedFile{Name: f.Name, Size: max(f.Size, 0), Time: max(f.Time, 0), Folder: remoteClean(f.Folder, 120)})
	}
	for _, f := range in.Folders {
		if len(out.Folders) == remoteMaxFolders {
			break
		}
		name, path := remoteClean(f.Name, 64), remoteClean(f.Path, 255)
		if name == "" || path == "" {
			continue
		}
		out.Folders = append(out.Folders, remoteFolder{Name: name, Path: path})
	}
	out.FolderCount = len(out.Folders)
	return out
}

// freshReport returns the app's report, asking the app when the one at hand is
// older than remoteReportTTL; nil when the app has none to give in time.
func (m *remoteManager) freshReport() *remoteAppReport {
	m.mu.Lock()
	rep, at, asked := m.report, m.reportAt, m.delegateGen
	m.mu.Unlock()
	if rep != nil && time.Since(at) < remoteReportTTL {
		return rep
	}
	raw, _, err := m.callDelegateWithin(m.reportWait, m.reportAnswer, func(d RemoteDelegate) (string, error) {
		return d.GetSetting(remoteKeyReport)
	})
	if err != nil {
		return nil
	}
	rep = remoteParseReport(raw)
	if rep == nil {
		return nil
	}
	m.mu.Lock()
	if m.delegateGen == asked {
		m.report, m.reportAt = rep, time.Now()
	}
	m.mu.Unlock()
	return rep
}

// forgetReport makes the next status ask the app again: something was just
// changed there.
func (m *remoteManager) forgetReport() {
	m.mu.Lock()
	m.reportAt = time.Time{}
	m.mu.Unlock()
}

// appReport is the report as a caller of this class may see it: only an owner
// is told that a permission can be asked for or the folder reset, and only an
// owner is given the shared folders' names and paths.
func (m *remoteManager) appReport(class remoteClass) *remoteAppReport {
	rep := m.freshReport()
	if rep == nil {
		return nil
	}
	out := *rep
	mayAsk := m.policy.allows(remoteOpAskPermission, class)
	out.Permissions = make([]remotePermission, len(rep.Permissions))
	for i, p := range rep.Permissions {
		p.Ask = p.Ask && mayAsk
		out.Permissions[i] = p
	}
	out.Receive.Reset = rep.Receive.Reset && m.policy.allows(remoteOpAction, class)
	if !m.policy.allows(remoteOpFolders, class) {
		out.Folders = nil
	}
	return &out
}

// handleAskPermission asks the app to bring a permission's page in front of
// whoever is at the device. The answer is what the app did about it — never
// that anything was granted — and the request is in the action log either way.
func (m *remoteManager) handleAskPermission(rq *remoteRequest) (int, any) {
	var in struct {
		Permission string `json:"permission"`
	}
	if !remoteDecode(rq.body, &in) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	if !remoteAskable[in.Permission] {
		// The app is not asked: a name that is not on the list does not exist.
		m.noteDenied(rq.caller, rq.class, "permission not on the list: "+remoteClean(in.Permission, 40))
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "not a permission that can be asked for"}
	}
	if !m.asks.allow(rq.caller.NodeID) {
		return http.StatusTooManyRequests, remoteError{Error: "slow_down"}
	}
	// Who asks is part of the request: the device says it to the person in
	// front of it. The name is the tailnet's, from whois; nothing the caller
	// sent.
	ask, err := json.Marshal(struct {
		Permission string `json:"permission"`
		From       string `json:"from"`
	}{in.Permission, rq.caller.Name})
	if err != nil {
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	result, status, refusal := m.changeTold(rq, "permission", in.Permission, func(d RemoteDelegate) (string, error) {
		if err := d.SetSetting(remoteKeyAsk, string(ask)); err != nil {
			return "", err
		}
		return d.GetSetting(remoteKeyAsk)
	}, func(v string) (string, bool) {
		return ": " + v, remoteAskResults[v]
	})
	if status != http.StatusOK {
		return status, refusal
	}
	return http.StatusOK, struct {
		Permission string `json:"permission"`
		Result     string `json:"result"`
	}{in.Permission, result}
}
