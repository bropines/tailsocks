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
type remotePolicy struct {
	// OwnerNeedsCode: a device of this node's own account still needs the code
	// for its first pairing. With false such a device pairs by asking, with no
	// code and no one at this device — and so does any app on it that can send
	// tailnet packets, which then holds a token of its own.
	OwnerNeedsCode bool
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
	remoteOpLog         = "log"
	remoteOpUnpair      = "unpair"
)

func remoteDefaultPolicy() remotePolicy {
	return remotePolicy{
		OwnerNeedsCode: true,
		AllowGuests:    true,
		CodeTTL:        10 * time.Minute,
		CodeAttempts:   5,
		GuestTTL:       24 * time.Hour,
		OwnerTTL:       0,
		MaxPairings:    8,
		// A guest looks and picks an exit node; what changes how the device
		// behaves afterwards, deletes something or shows its log is the
		// owner's.
		Rights: map[string]remoteClass{
			remoteOpStatus:      remoteGuest,
			remoteOpExitNodes:   remoteGuest,
			remoteOpSetExitNode: remoteGuest,
			remoteOpSettings:    remoteGuest,
			remoteOpFiles:       remoteGuest,
			remoteOpUnpair:      remoteGuest,
			remoteOpSetSetting:  remoteOwner,
			remoteOpAction:      remoteOwner,
			remoteOpDeleteFile:  remoteOwner,
			remoteOpLog:         remoteOwner,
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
// The two facts are read-only: what kind of device this is and which build it
// runs, for the managing phone's screen.
var remoteSettings = []remoteSetting{
	{key: "accept_dns", kind: "bool", writable: true},
	{key: "accept_routes", kind: "bool", writable: true},
	{key: "exit_node_lan_access", kind: "bool", writable: true},
	{key: remoteKeyDeviceClass, kind: "text"},
	{key: remoteKeyAppVersion, kind: "text"},
}

const (
	remoteKeyDeviceClass = "device_class"
	remoteKeyAppVersion  = "app_version"
)

// remoteActions is the whole list of named actions. One: restart the
// connection, the remedy for a device stuck on a dead path. It is a restart,
// never a stop — the service's own restart path, which keeps the user's wish
// to be connected.
var remoteActions = []string{"reconnect"}

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
