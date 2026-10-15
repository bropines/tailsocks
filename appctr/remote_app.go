package appctr

// remote_app.go — what the app tells a paired device about itself, and what a
// paired device may ask the app to put on the device's screen.
//
// These go through RemoteDelegate.Request, by the names in remoteRequests.
// They are not settings: /v1/settings neither lists nor accepts them.
//
//   - report: the app's own account of what a person setting the device up
//     from afar needs to know and cannot see — which permissions it holds and
//     lacks, where received files go and whether that place can be written,
//     which received files went there, which folders it shares, what the
//     device is. It is JSON the app wrote, and it is not passed on: it is
//     decoded into the types below, cut down to the names remote_policy.go
//     lists and to lengths a screen can show, and encoded again. A field the
//     bridge does not know does not travel, and neither does a permission it
//     does not list.
//   - ask_permission: bring a permission's page in front of whoever is at the
//     device. The answer is what became of the request.
//   - identify: say "managed from <name>" on the screen for a few seconds.
//   - notice: a paired device changed something; say so on the screen while
//     the app is up. Sent after the fact, by the bridge, never by a caller.
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

// The requests the bridge makes of the app through RemoteDelegate.Request.
// What each takes and gives:
//
//	report          ""                              the report, JSON (remoteAppReport as the app fills it)
//	ask_permission  {"permission","from"}           "shown" | "notified" | "waiting"
//	identify        {"from"}                        "shown" | "notified" | "unseen"
//	notice          {"from","op","detail"}          ""
//	storage_list    {"path"}                        a listing, JSON (remote_storage.go)
//	storage_file    {"path"}                        {"path","name","size"} of a file that may be sent
//	share_add       {"name","path"}                 ""
//	share_remove    {"name"}                        ""
//	diag_report     ""                              the diagnostics report, text
//	elevate_ask     {"from","nodeId","seconds"}     "shown" | "notified" | "waiting"
//	elevations      {"elevations":[{"nodeId","name","left","until","seconds","full"}]}   ""
//
// elevate_ask puts a guest's request for full rights in front of the person at
// the device, and elevations tells the app which full rights are in force after
// they changed here (remote_elevate.go).
//
// A refusal is a thrown error whose message is for a person, except two words
// the bridge turns into its own answers: "not_found" and "forbidden".
const (
	remoteRequestReport      = "report"
	remoteRequestAsk         = "ask_permission"
	remoteRequestIdentify    = "identify"
	remoteRequestNotice      = "notice"
	remoteRequestStorageList = "storage_list"
	remoteRequestStorageFile = "storage_file"
	remoteRequestShareAdd    = "share_add"
	remoteRequestShareRemove = "share_remove"
	remoteRequestDiagReport  = "diag_report"
	remoteRequestElevateAsk  = "elevate_ask"
	remoteRequestElevations  = "elevations"
)

// remoteRequests is all of them, for the tests that hold the two halves to
// one list.
var remoteRequests = []string{
	remoteRequestReport, remoteRequestAsk, remoteRequestIdentify, remoteRequestNotice, remoteRequestStorageList,
	remoteRequestStorageFile, remoteRequestShareAdd, remoteRequestShareRemove, remoteRequestDiagReport,
	remoteRequestElevateAsk, remoteRequestElevations,
}

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
	// A node may ask for something to be put on the device's screen — a
	// permission's page, its own name — three times in a row and then once in
	// twenty seconds: a paired device must not be able to keep at it.
	remoteAskRate  = 1.0 / 20
	remoteAskBurst = 3
	// A notice is not worth waiting for.
	remoteNoticeWait   = time.Second
	remoteNoticeAnswer = 2 * time.Second
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

// remoteIdentifyResults is the same for "say whose hands you are in": on the
// app's own screen, in a notification, or not at all.
var remoteIdentifyResults = map[string]bool{"shown": true, "notified": true, "unseen": true}

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
	// Free is the bytes free where received files land, -1 when not known.
	Free int64 `json:"free"`
	// Waiting is how many received files wait inside the app.
	Waiting int `json:"waiting"`
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

// remoteDeviceFacts is what the device is: for telling which television this
// is, and what can be expected of its Android.
type remoteDeviceFacts struct {
	Model   string `json:"model"`
	Android string `json:"android"`
	SDK     int    `json:"sdk"`
	// Build is the app's commit.
	Build string `json:"build"`
	// Mode is "proxy", "tun" or "root"; Engine the TUN engine, "native" or
	// "hev", "" outside TUN mode.
	Mode   string `json:"mode"`
	Engine string `json:"engine"`
	// KeyExpiry is when this node's key runs out, Unix seconds; 0 when it
	// does not. The daemon's, not the app's.
	KeyExpiry int64 `json:"keyExpiry"`
}

var (
	remoteModes   = map[string]bool{"proxy": true, "tun": true, "root": true}
	remoteEngines = map[string]bool{"native": true, "hev": true}
)

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
	// Device travels in the app's report and is a part of the status, not of
	// its "app".
	Device *remoteDeviceFacts `json:"device,omitempty"`
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
	out.Receive = remoteReceive{State: "unknown", Free: -1}
	if remoteReceiveStates[in.Receive.State] {
		out.Receive.State = in.Receive.State
		out.Receive.Folder = remoteClean(in.Receive.Folder, 120)
		out.Receive.Reset = in.Receive.Reset && in.Receive.State != "ready" && in.Receive.State != "inbox"
		out.Receive.Free = max(in.Receive.Free, -1)
		out.Receive.Waiting = min(max(in.Receive.Waiting, 0), remoteMaxFiles)
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
	if d := in.Device; d != nil {
		facts := &remoteDeviceFacts{
			Model:   remoteClean(d.Model, 64),
			Android: remoteClean(d.Android, 16),
			Build:   remoteClean(d.Build, 40),
		}
		if d.SDK > 0 && d.SDK < 1000 {
			facts.SDK = d.SDK
		}
		if remoteModes[d.Mode] {
			facts.Mode = d.Mode
		}
		if facts.Mode == "tun" && remoteEngines[d.Engine] {
			facts.Engine = d.Engine
		}
		out.Device = facts
	}
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
		return d.Request(remoteRequestReport, "")
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

// appReport is the report as a caller of this class may see it — only an
// owner is told that a permission can be asked for or the folder reset, and
// only an owner is given the shared folders' names and paths — and, apart
// from it, what the app said the device is.
func (m *remoteManager) appReport(class remoteClass) (*remoteAppReport, *remoteDeviceFacts) {
	rep := m.freshReport()
	if rep == nil {
		return nil, nil
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
	out.Device = nil
	return &out, rep.Device
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
		m.noteDenied(rq.caller, rq.class, remoteDeniedPermission, remoteClean(in.Permission, 40))
		return http.StatusForbidden, remoteError{Error: "forbidden", Message: "not a permission that can be asked for"}
	}
	if !m.asks.allow(rq.caller.NodeID) {
		return http.StatusTooManyRequests, remoteError{Error: "slow_down"}
	}
	// Who asks is part of the request: the device says it to the person in
	// front of it. The name is the tailnet's, from whois; nothing the caller
	// sent.
	args, err := json.Marshal(struct {
		Permission string `json:"permission"`
		From       string `json:"from"`
	}{in.Permission, rq.caller.Name})
	if err != nil {
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	result, status, refusal := m.changeTold(rq, "permission", in.Permission, func(d RemoteDelegate) (string, error) {
		return d.Request(remoteRequestAsk, string(args))
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

// handleIdentify makes the device say whose hands it is in — "managed from
// <name>", for a few seconds — so that a person with two of them knows which
// one the phone is talking to. A guest may ask: it shows nothing but the
// caller's own name, as the tailnet has it.
func (m *remoteManager) handleIdentify(rq *remoteRequest) (int, any) {
	var in struct{}
	if !remoteDecode(rq.body, &in) {
		return http.StatusBadRequest, remoteError{Error: "bad_request"}
	}
	if !m.asks.allow(rq.caller.NodeID) {
		return http.StatusTooManyRequests, remoteError{Error: "slow_down"}
	}
	args, err := json.Marshal(struct {
		From string `json:"from"`
	}{rq.caller.Name})
	if err != nil {
		return http.StatusInternalServerError, remoteError{Error: "failed"}
	}
	result, status, refusal := m.changeTold(rq, "identify", "", func(d RemoteDelegate) (string, error) {
		return d.Request(remoteRequestIdentify, string(args))
	}, func(v string) (string, bool) {
		return v, remoteIdentifyResults[v]
	})
	if status != http.StatusOK {
		return status, refusal
	}
	return http.StatusOK, struct {
		Result string `json:"result"`
	}{result}
}

// remoteNoticeOps are the lines of the action log the device also says on its
// screen while the app is up: what a paired device changed. A request for a
// permission and "identify" are on the screen by themselves.
var remoteNoticeOps = map[string]bool{
	"exit_node": true, "setting": true, "action": true, "file_deleted": true, "reverted": true,
	"share_added": true, "share_removed": true, "file_sent": true,
}

// notice tells the app that a paired device changed something, on a thread of
// its own and without waiting: the change is made and logged whether or not
// the app gets to say it.
func (m *remoteManager) notice(from, op, detail string) {
	if !remoteNoticeOps[op] {
		return
	}
	args, err := json.Marshal(struct {
		From   string `json:"from"`
		Op     string `json:"op"`
		Detail string `json:"detail"`
	}{from, op, remoteClean(detail, 160)})
	if err != nil {
		return
	}
	go func() {
		_, _, _ = m.callDelegateWithin(remoteNoticeWait, remoteNoticeAnswer, func(d RemoteDelegate) (string, error) {
			return d.Request(remoteRequestNotice, string(args))
		})
	}()
}
