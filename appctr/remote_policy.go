package appctr

// remote_policy.go — who may do what in remote management, and the lists of
// what can be done at all. Everything a decision about the rights model would
// change is in this file: the knobs in remoteDefaultPolicy, the operations in
// remoteOps, the settings in remoteSettings, the actions in remoteActions, the
// permissions in remotePermissions, the capabilities in remoteCaps. remote.go
// has the threat model these are measured against.
//
// Three things decide whether a request is carried out:
//
//   - the caller's class: an owner — an untagged node of this node's own
//     user — or a guest, anyone else (remote_auth.go, classOf);
//   - the device's level, chosen on the device and never from afar: basic,
//     which the feature's switch gives, or full control, a second switch
//     (remote_level.go). Full control is for what shows more of the device to
//     a network or shows what is on it, and is the owner's only;
//   - the name: an operation, a setting, an action or a permission that is not
//     on its list here does not exist.

import "time"

// remoteClass is what a caller is to this device. Order matters: an operation
// names the lowest class that may use it.
type remoteClass int

const (
	remoteNobody remoteClass = iota // resolved by whois, nothing more
	remoteGuest
	remoteOwner
)

func (c remoteClass) String() string {
	switch c {
	case remoteGuest:
		return "guest"
	case remoteOwner:
		return "owner"
	}
	return ""
}

func remoteClassOf(s string) remoteClass {
	switch s {
	case "guest":
		return remoteGuest
	case "owner":
		return remoteOwner
	}
	return remoteNobody
}

// remotePolicy is every knob of the rights model in one place. The author has
// not settled it; change the defaults in remoteDefaultPolicy.
//
// One knob that was asked for is deliberately absent: letting this account's
// own devices pair without the code. Who a caller is rests on whois, and whois
// on a loopback connection is not proof (see remote_auth.go): a process on
// this device can be taken for a peer, and any app on a peer speaks as that
// peer. The code on the screen is the one thing neither of them has, so every
// pairing needs it.
type remotePolicy struct {
	// AllowGuests lets nodes that cannot be owners — another user's, tagged,
	// shared in from another tailnet — pair at all, as guests. The device's
	// own "no guests" switch can only take this away (remote_level.go).
	AllowGuests bool
	// CodeTTL and CodeAttempts bound one pairing code.
	CodeTTL      time.Duration
	CodeAttempts int
	// GuestTTL and OwnerTTL are how long a pairing lasts; 0 is until revoked.
	GuestTTL time.Duration
	OwnerTTL time.Duration
	// MaxPairings caps the list; a full list refuses new devices.
	MaxPairings int
	// Rights names, per operation, the lowest class that may use it.
	Rights map[string]remoteClass
	// Full names the operations that need full control on top of their class.
	Full map[string]bool
	// GuardTTL is how long a guarded change waits for its confirmation before
	// the device puts the old value back; GuardSettle how long after the
	// change a confirmation is not yet taken — it has to come over a channel
	// the change has already had its effect on (remote_guard.go).
	GuardTTL    time.Duration
	GuardSettle time.Duration
}

// The operations Rights speaks of.
const (
	remoteOpStatus      = "status"
	remoteOpExitNodes   = "exit_nodes"
	remoteOpSetExitNode = "exit_node.set"
	remoteOpSettings    = "settings"
	remoteOpSetSetting  = "settings.set"
	remoteOpAction      = "action"
	remoteOpFiles       = "files"
	remoteOpDeleteFile  = "files.delete"
	remoteOpUnpair      = "unpair"
	// Asking for a permission's page to be put in front of whoever is at the
	// device (remote_app.go).
	remoteOpAskPermission = "permission.request"
	// Inside a status answer, the names and paths of the folders the device
	// shares. No route of its own: a status is everyone's, this part is not.
	remoteOpFolders = "folders"
	// Confirming a guarded change; whoever may make one may confirm it.
	remoteOpConfirm = "confirm"
	// "Managed from <name>" on the device's screen, to tell two devices apart.
	remoteOpIdentify = "identify"
	// Taildrive shares: removing one narrows what the device shows, adding one
	// widens it.
	remoteOpSharesRemove = "shares.remove"
	remoteOpSharesAdd    = "shares.add"
	// The device's storage by name, and "send me this file" (remote_storage.go).
	remoteOpStorageList = "storage.list"
	remoteOpStorageSend = "storage.send"
	// The diagnostics report and the tail of the log (remote_diag.go).
	remoteOpDiagReport = "diag.report"
	remoteOpDiagLog    = "diag.log"
)

// remoteOps is every operation, in the order a status answer lists the ones a
// caller may use.
var remoteOps = []string{
	remoteOpStatus, remoteOpExitNodes, remoteOpSetExitNode, remoteOpSettings, remoteOpSetSetting, remoteOpAction,
	remoteOpFiles, remoteOpDeleteFile, remoteOpUnpair, remoteOpAskPermission, remoteOpFolders, remoteOpConfirm,
	remoteOpIdentify, remoteOpSharesRemove, remoteOpSharesAdd, remoteOpStorageList, remoteOpStorageSend,
	remoteOpDiagReport, remoteOpDiagLog,
}

func remoteDefaultPolicy() remotePolicy {
	return remotePolicy{
		AllowGuests:  true,
		CodeTTL:      10 * time.Minute,
		CodeAttempts: 5,
		GuestTTL:     24 * time.Hour,
		OwnerTTL:     0,
		MaxPairings:  8,
		// A guest looks, picks an exit node and can make the device say whose
		// hands it is in; what changes how the device behaves afterwards,
		// deletes something, puts a page in front of the person at the device
		// or names a path on it is the owner's.
		Rights: map[string]remoteClass{
			remoteOpStatus:        remoteGuest,
			remoteOpExitNodes:     remoteGuest,
			remoteOpSetExitNode:   remoteGuest,
			remoteOpSettings:      remoteGuest,
			remoteOpFiles:         remoteGuest,
			remoteOpUnpair:        remoteGuest,
			remoteOpConfirm:       remoteGuest,
			remoteOpIdentify:      remoteGuest,
			remoteOpSetSetting:    remoteOwner,
			remoteOpAction:        remoteOwner,
			remoteOpDeleteFile:    remoteOwner,
			remoteOpAskPermission: remoteOwner,
			remoteOpFolders:       remoteOwner,
			remoteOpSharesRemove:  remoteOwner,
			remoteOpSharesAdd:     remoteOwner,
			remoteOpStorageList:   remoteOwner,
			remoteOpStorageSend:   remoteOwner,
			remoteOpDiagReport:    remoteOwner,
			remoteOpDiagLog:       remoteOwner,
		},
		// What shows more of the device to a network, or shows what is on it.
		Full: map[string]bool{
			remoteOpSharesAdd:   true,
			remoteOpStorageList: true,
			remoteOpStorageSend: true,
			remoteOpDiagReport:  true,
			remoteOpDiagLog:     true,
		},
		GuardTTL:    60 * time.Second,
		GuardSettle: 5 * time.Second,
	}
}

// allows reports whether class may use op, the level aside. An operation
// Rights does not name is the owner's.
func (p remotePolicy) allows(op string, class remoteClass) bool {
	min, ok := p.Rights[op]
	if !ok {
		min = remoteOwner
	}
	return class >= min && class > remoteNobody
}

// remoteSetting is one entry of the settings allow-list.
type remoteSetting struct {
	key string
	// kind is "bool" — "true" or "false"; "cidrs" — a comma-separated list of
	// prefixes, "" for none; or "text", which nothing writable is.
	kind     string
	writable bool
	// only is the one value a paired device may ever write to a switch: "true"
	// for one that may be turned on from afar and never off, "false" for the
	// reverse, "" for either. Reading is not affected.
	only string
	// full is the value that takes full control to write: "true" for a switch
	// that anyone allowed may turn off and only full control turns on, "*" for
	// a list that anyone allowed may clear and only full control fills.
	full string
	// guard is "optional" when a change of it is held for a confirmation if
	// the caller asks for that, "always" when it is whatever the caller asks
	// (remote_guard.go).
	guard string
}

// remoteSettings is the whole list of what /v1/settings shows and changes.
// Short on purpose. Each is something a person helping with a TV actually
// reaches for and that takes effect without a dialog on the device.
//
// The connection, either way. Management goes by tailnet address, not by name
// or route, so these can normally be turned back the same way; where one does
// cut the channel, a guarded change undoes itself:
//
//   - accept_dns: tailnet names and split DNS on the device.
//   - accept_routes: reach what sits behind a subnet router.
//   - exit_node_lan_access: keep the LAN (casting, a NAS) reachable while an
//     exit node is in use.
//   - tun_mode: proxy or TUN. Always guarded: behind the device's own tunnel
//     this endpoint may be out of reach (remote.go), and then the device comes
//     back by itself. The app refuses it without the VPN consent, which only a
//     person at the device gives, and in Root Mode.
//
// What keeps the device reachable, on only — turning it off is how a device
// gets stranded, so that stays at the device:
//
//   - auto_reconnect: the device restarts its own connection when it does not
//     come up, the remedy for the one failure nobody can mend from afar.
//   - keep_awake: the app's "keep the connection awake", which is also what
//     starts it again after a reboot.
//
// What shows more of the device to a network: off for any owner, on only with
// full control:
//
//   - taildrive_enabled: sharing the device's folders with the tailnet.
//   - taildrive_proxy_enabled: the local port other apps read shares through.
//   - advertise_exit_node: offering the device as an exit node.
//   - advertise_routes: the subnets it offers to route; an empty list clears.
//
// And full_control itself, off only: a paired owner can give the level up and
// never take it.
//
// The two facts are read-only: what kind of device this is and which build it
// runs, for the managing phone's screen.
var remoteSettings = []remoteSetting{
	{key: "accept_dns", kind: "bool", writable: true, guard: "optional"},
	{key: "accept_routes", kind: "bool", writable: true, guard: "optional"},
	{key: "exit_node_lan_access", kind: "bool", writable: true, guard: "optional"},
	{key: "tun_mode", kind: "bool", writable: true, guard: "always"},
	{key: "auto_reconnect", kind: "bool", writable: true, only: "true"},
	{key: "keep_awake", kind: "bool", writable: true, only: "true"},
	{key: "taildrive_enabled", kind: "bool", writable: true, full: "true"},
	{key: "taildrive_proxy_enabled", kind: "bool", writable: true, full: "true"},
	{key: "advertise_exit_node", kind: "bool", writable: true, full: "true"},
	{key: "advertise_routes", kind: "cidrs", writable: true, full: "*"},
	{key: remoteKeyFullControl, kind: "bool", writable: true, only: "false"},
	{key: remoteKeyDeviceClass, kind: "text"},
	{key: remoteKeyAppVersion, kind: "text"},
}

const (
	remoteKeyDeviceClass = "device_class"
	remoteKeyAppVersion  = "app_version"
	remoteKeyFullControl = "full_control"
)

// remoteMaxRoutes bounds a list of advertised routes.
const remoteMaxRoutes = 16

// remoteActions is the whole list of named actions.
//
//   - reconnect: restart the connection, the remedy for a device stuck on a
//     dead path. It is a restart, never a stop — the service's own restart
//     path, which keeps the user's wish to be connected.
//   - reconnect_relays: dial the relay servers again without restarting
//     anything; the bridge's own (recovery.go), the app is not asked.
//   - receive_folder_default: forget a receive folder that cannot be written —
//     a drive pulled out, a grant taken back — so that received files go where
//     this kind of device puts them by default. The app refuses while the
//     chosen folder works: a working choice is changed at the device.
//   - save_received: move the files that wait inside the app into the receive
//     folder, as the device's own "Save" does.
var remoteActions = []string{"reconnect", "reconnect_relays", "receive_folder_default", "save_received"}

// remoteActionRelays is the one action the bridge carries out itself.
const remoteActionRelays = "reconnect_relays"

// remotePermissions is every permission of the app a status answer may speak
// of, by the name the app and the managing phone know it under; the app's
// report is cut down to these (remote_app.go).
var remotePermissions = []string{"storage", "notifications", "battery", "vpn", "alarms", "install", "autostart"}

// remoteAskable is which of them a paired owner may ask for. Asking grants
// nothing: the app brings the system's own page or dialog in front of whoever
// is at the device, or says that it could not (remote_app.go). All but one:
// the VPN consent is what lets a tunnel start, and behind a tunnel this
// endpoint may be out of reach (remote.go) — so it is reported and never asked
// for from afar.
var remoteAskable = map[string]bool{
	"storage":       true,
	"notifications": true,
	"battery":       true,
	"alarms":        true,
	"install":       true,
	"autostart":     true,
}

// remoteCaps is what this build can do, by the names a status answer lists
// for a manager that may be older or newer than this device: it offers only
// what it finds here. A build that sends no list at all is the first one. The
// level and the caller's class are not in it — see remoteOps and
// remoteManager.can.
var remoteCaps = []string{
	"report",      // status.app: permissions, the receive folder, saved files, shared folders
	"permissions", // POST /v1/permissions/request
	"oneway",      // settings that go one way: "only", "needs"
	"suggest",     // the exit node the device's own daemon would pick
	"can",         // status.level and status.you.can
	"device",      // status.device, status.app.receive.free
	"guard",       // guarded changes and POST /v1/confirm
	"mode",        // the tun_mode setting
	"identify",    // POST /v1/identify
	"relays",      // the action reconnect_relays
	"save",        // the action save_received
	"storage",     // POST /v1/storage/list, POST /v1/storage/send, status.fetch
	"shares",      // POST /v1/shares/add, POST /v1/shares/remove
	"advertise",   // advertise_exit_node, advertise_routes, status.advertise
	"diag",        // GET /v1/diag/report, GET /v1/diag/log
}

func remoteSettingFor(key string) (remoteSetting, bool) {
	for _, s := range remoteSettings {
		if s.key == key {
			return s, true
		}
	}
	return remoteSetting{}, false
}

func remoteActionKnown(name string) bool {
	for _, a := range remoteActions {
		if a == name {
			return true
		}
	}
	return false
}

// remoteDeviceClasses is what device_class may say; anything else is "".
var remoteDeviceClasses = map[string]bool{"tv": true, "phone": true, "tablet": true}
