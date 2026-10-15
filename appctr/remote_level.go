package appctr

// remote_level.go — the level a device is managed at, and what follows from
// it for one caller.
//
// The feature's switch gives the basic level: looking, the connection's
// settings, received files, permissions seen and asked for. A second switch
// on the device's own page gives full control: adding a share, the storage by
// name with "send me this file", offering routes or an exit node, the
// diagnostics and the scrubbed log — what shows more of the device to a
// network, or shows what is on it. It is the owner's only, and it is switched
// on at the device and nowhere else: the bridge takes it from StartOptions,
// which the app fills from a file of its own, and the one thing a paired
// device can do to it is switch it off (the setting full_control, off only).
//
// A third switch there keeps guests out altogether, and a fourth lets a person
// at the device give one guest an owner's rights for a while
// (remote_elevate.go).

import "strings"

// setAccess records the two switches. Shutting guests out ends the full rights
// a guest was given for a while (remote_elevate.go).
func (m *remoteManager) setAccess(full, noGuests bool) {
	m.mu.Lock()
	if m.full != full || m.noGuests != noGuests {
		m.full, m.noGuests = full, noGuests
		m.rev++
		if noGuests {
			m.expireLocked()
		}
	}
	m.mu.Unlock()
}

func (m *remoteManager) fullControl() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.full
}

// classOf is the policy's verdict on a caller with the device's own "no
// guests" switch applied: with it on, who cannot be an owner is nobody.
func (m *remoteManager) classOf(c remoteCaller, self remoteSelf) remoteClass {
	class := m.policy.classOf(c, self)
	if class != remoteGuest {
		return class
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.noGuests {
		return remoteNobody
	}
	return class
}

// guestsAllowed is what the device's page shows of it.
func (m *remoteManager) guestsAllowedLocked() bool {
	return m.policy.AllowGuests && !m.noGuests
}

// may reports whether a caller of this class, with the full-control tier open
// to it or not, may use op right now; when it may not, needsFull says that
// full control is all that is missing. The tier is open where the device's
// switch is on, and to a guest whose full rights include it
// (remote_elevate.go).
func (m *remoteManager) may(op string, class remoteClass, full bool) (ok, needsFull bool) {
	if !m.policy.allows(op, class) {
		return false, false
	}
	if m.policy.Full[op] && !full {
		return false, true
	}
	return true, false
}

// can lists the operations a caller of this class may use right now, in the
// order of remoteOps.
func (m *remoteManager) can(class remoteClass, full bool) []string {
	out := []string{}
	for _, op := range remoteOps {
		if m.policy.allows(op, class) && (full || !m.policy.Full[op]) {
			out = append(out, op)
		}
	}
	return out
}

// remoteNeedsFull is the refusal of something that is the caller's at the
// full level and not at this one.
var remoteNeedsFull = remoteError{Error: "needs_full", Message: "full control is off on this device"}

// remoteLevel is the level a caller is at: "full" where the full-control tier
// is open to it, "basic" otherwise.
func remoteLevel(full bool) string {
	if full {
		return "full"
	}
	return "basic"
}

// RemoteAccessJSON says what a paired device may do on this device right now,
// from the lists the endpoint itself enforces — for the device's own page, so
// that what it tells its user cannot drift from what is done:
//
//	{"level":"basic"|"full","guests":true,"elevate":true,
//	 "guest":{"ops":[…]},
//	 "owner":{"ops":[…],"settings":[{"key","only","needs","guard"}],"actions":[…],"permissions":[…]},
//	 "full":{"ops":[…],"settings":[…]},
//	 "never":[…]}
//
// "guest" and "owner" are at the current level; "full" is what switching full
// control on would add for an owner (empty while it is on). A setting's "only"
// is the one value that may be written from afar, "needs":"full" marks one
// whose other value takes full control, "guard" one that is put back unless
// confirmed. "never" names what no level and no class reaches. "elevate" says
// that a guest may be given full rights for a while here (remote_elevate.go);
// asking for them is among a guest's operations then, and never an owner's,
// who has nothing to ask for.
func RemoteAccessJSON() string { return remoteReply(remote.access()) }

type remoteAccessSetting struct {
	Key   string `json:"key"`
	Only  string `json:"only,omitempty"`
	Needs string `json:"needs,omitempty"`
	Guard string `json:"guard,omitempty"`
}

type remoteAccessClass struct {
	Ops         []string              `json:"ops"`
	Settings    []remoteAccessSetting `json:"settings,omitempty"`
	Actions     []string              `json:"actions,omitempty"`
	Permissions []string              `json:"permissions,omitempty"`
}

type remoteAccessView struct {
	Level   string            `json:"level"`
	Guests  bool              `json:"guests"`
	Elevate bool              `json:"elevate"`
	Guest   remoteAccessClass `json:"guest"`
	Owner   remoteAccessClass `json:"owner"`
	Full    remoteAccessClass `json:"full"`
	Never   []string          `json:"never"`
}

// remoteNever is what stays on the device whatever the level and the class,
// by the names the device's page has words for.
var remoteNever = []string{"stop", "sign_out", "account", "credentials", "control_proxy", "root_mode", "grant_permission", "full_control_on"}

func (m *remoteManager) access() remoteAccessView {
	m.mu.Lock()
	full, guests, elevate := m.full, m.guestsAllowedLocked(), m.elevationAllowedLocked()
	m.mu.Unlock()
	v := remoteAccessView{Level: remoteLevel(full), Guests: guests, Elevate: elevate, Never: remoteNever}
	v.Guest.Ops, v.Owner.Ops, v.Full.Ops = []string{}, []string{}, []string{}
	for _, op := range remoteOps {
		switch {
		case op == remoteOpElevateAsk:
			if elevate {
				v.Guest.Ops = append(v.Guest.Ops, op)
			}
		case m.policy.Full[op] && !full:
			if m.policy.allows(op, remoteOwner) {
				v.Full.Ops = append(v.Full.Ops, op)
			}
		default:
			if m.policy.allows(op, remoteGuest) {
				v.Guest.Ops = append(v.Guest.Ops, op)
			}
			if m.policy.allows(op, remoteOwner) {
				v.Owner.Ops = append(v.Owner.Ops, op)
			}
		}
	}
	if m.policy.allows(remoteOpSetSetting, remoteOwner) {
		for _, s := range remoteSettings {
			if !s.writable {
				continue
			}
			view := remoteSettingViewOf(s, true, full)
			v.Owner.Settings = append(v.Owner.Settings, remoteAccessSetting{Key: s.key, Only: view.Only, Needs: view.Needs, Guard: s.guard})
			if view.Needs != "" {
				v.Full.Settings = append(v.Full.Settings, remoteAccessSetting{Key: s.key})
			}
		}
	}
	if m.policy.allows(remoteOpAction, remoteOwner) {
		v.Owner.Actions = append(v.Owner.Actions, remoteActions...)
	}
	if m.policy.allows(remoteOpAskPermission, remoteOwner) {
		for _, id := range remotePermissions {
			if remoteAskable[id] {
				v.Owner.Permissions = append(v.Owner.Permissions, id)
			}
		}
	}
	return v
}

// remoteSettingViewOf is a setting as a caller sees it, the value aside:
// whether it may write it, and which way.
func remoteSettingViewOf(s remoteSetting, mayWrite, full bool) remoteSettingView {
	v := remoteSettingView{Key: s.key, Kind: s.kind, Writable: s.writable && mayWrite, Only: s.only, Guard: s.guard}
	if s.full != "" && !full {
		v.Needs = "full"
		if s.full == "true" {
			v.Only = "false"
		}
	}
	return v
}

// remoteSettingRefusal checks a value a caller wants to write against the way
// the setting goes: "" when it may be written, "only" when that way round is
// done at the device, "full" when it takes full control.
func remoteSettingRefusal(s remoteSetting, value string, full bool) string {
	if s.only != "" && value != s.only {
		return "only"
	}
	if full {
		return ""
	}
	switch s.full {
	case "true":
		if value == "true" {
			return "full"
		}
	case "*":
		if strings.TrimSpace(value) != "" {
			return "full"
		}
	}
	return ""
}
