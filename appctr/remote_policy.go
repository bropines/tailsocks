package appctr

// remote_policy.go — who may do what in remote management, and the lists of
// what can be done at all. Everything a decision about the rights model would
// change is in this file: the knobs in remoteDefaultPolicy, the settings in
// remoteSettings, the actions in remoteActions. remote.go has the threat model
// these are measured against.

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
	// shared in from another tailnet — pair at all, as guests.
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
)

func remoteDefaultPolicy() remotePolicy {
	return remotePolicy{
		AllowGuests:  true,
		CodeTTL:      10 * time.Minute,
		CodeAttempts: 5,
		GuestTTL:     24 * time.Hour,
		OwnerTTL:     0,
		MaxPairings:  8,
		// A guest looks and picks an exit node; what changes how the device
		// behaves afterwards, deletes something, puts a page in front of the
		// person at the device or names a path on it is the owner's.
		Rights: map[string]remoteClass{
			remoteOpStatus:        remoteGuest,
			remoteOpExitNodes:     remoteGuest,
			remoteOpSetExitNode:   remoteGuest,
			remoteOpSettings:      remoteGuest,
			remoteOpFiles:         remoteGuest,
			remoteOpUnpair:        remoteGuest,
			remoteOpSetSetting:    remoteOwner,
			remoteOpAction:        remoteOwner,
			remoteOpDeleteFile:    remoteOwner,
			remoteOpAskPermission: remoteOwner,
			remoteOpFolders:       remoteOwner,
		},
	}
}

// allows reports whether class may use op. An operation Rights does not name
// is the owner's.
func (p remotePolicy) allows(op string, class remoteClass) bool {
	min, ok := p.Rights[op]
	if !ok {
		min = remoteOwner
	}
	return class >= min && class > remoteNobody
}

// remoteSetting is one entry of the settings allow-list.
type remoteSetting struct {
	key      string
	kind     string // "bool" or "text"
	writable bool
	// only is the one value a paired device may write to a switch: "true" for
	// one that may be turned on from afar and never off, "false" for the
	// reverse, "" for either. Reading is not affected.
	only string
}

// remoteSettings is the whole list of what /v1/settings shows and changes.
// Short on purpose. Each switch is one a person helping with a TV actually
// reaches for, takes effect without a dialog on the device, and can be turned
// back the same way because management goes by tailnet address, not by name
// or route:
//
//   - accept_dns: tailnet names and split DNS on the device.
//   - accept_routes: reach what sits behind a subnet router.
//   - exit_node_lan_access: keep the LAN (casting, a NAS) reachable while an
//     exit node is in use.
//
// Three more go one way only, and the way is the one that cannot hurt:
//
//   - auto_reconnect, on only: the device restarts its own connection when it
//     does not come up — the remedy for the one failure nobody can mend from
//     afar, since a dead connection is a dead management channel too. Turning
//     it off is how a device gets stranded, so that stays at the device.
//   - taildrive_enabled and taildrive_proxy_enabled, off only: the device
//     stops sharing its folders with the tailnet, or closes the local port
//     other apps read shares through. Turning either on shows more of the
//     device to a network, and that takes a person at the device.
//
// The two facts are read-only: what kind of device this is and which build it
// runs, for the managing phone's screen.
var remoteSettings = []remoteSetting{
	{key: "accept_dns", kind: "bool", writable: true},
	{key: "accept_routes", kind: "bool", writable: true},
	{key: "exit_node_lan_access", kind: "bool", writable: true},
	{key: "auto_reconnect", kind: "bool", writable: true, only: "true"},
	{key: "taildrive_enabled", kind: "bool", writable: true, only: "false"},
	{key: "taildrive_proxy_enabled", kind: "bool", writable: true, only: "false"},
	{key: remoteKeyDeviceClass, kind: "text"},
	{key: remoteKeyAppVersion, kind: "text"},
}

const (
	remoteKeyDeviceClass = "device_class"
	remoteKeyAppVersion  = "app_version"
)

// remoteActions is the whole list of named actions.
//
//   - reconnect: restart the connection, the remedy for a device stuck on a
//     dead path. It is a restart, never a stop — the service's own restart
//     path, which keeps the user's wish to be connected.
//   - receive_folder_default: forget a receive folder that cannot be written —
//     a drive pulled out, a grant taken back — so that received files go where
//     this kind of device puts them by default. The app refuses while the
//     chosen folder works: a working choice is changed at the device.
var remoteActions = []string{"reconnect", "receive_folder_default"}

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
