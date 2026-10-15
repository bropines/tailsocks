package appctr

// remote_files.go — the device's network access to files: a switch on the
// device's own page, off unless a person turned it on there, and a ceiling
// over every level and every class.
//
// It covers what lets a paired device at the device's files and at what the
// device writes down about itself: the storage by name and "send me this
// file", the shared folders — their names and paths, adding and removing one,
// Taildrive itself and its local proxy — the diagnostics report and the log
// (remotePolicy.Files, and the settings marked files in remoteSettings). While
// it is off, none of that is anybody's: an owner's, with full control on or
// not, nor a guest's given full rights for a while. It opens nothing on its
// own: what it covers still takes the class and the level it took before.
//
// Full rights for a while are given with what the switch was then
// (remote_elevate.go): rights given while it was off never include the files,
// whatever the switch says later — the person who gave them was told so.
// Rights given while it was on lose the files when it goes off, like everyone.
//
// Switched on at the device alone, after its lock (the app asks it; the
// bridge takes the switch from StartOptions.RemoteFiles and from
// RemoteAllowFiles), and never from afar: "files_on" is among remoteNever.
// Off takes effect at once, for every request that comes after: an open page
// on the other side is refused with files_closed, and its status says
// "closed".

// remoteFilesClosed is the refusal of something that is the caller's but for
// the device's files being closed.
var remoteFilesClosed = remoteError{Error: "files_closed", Message: "network access to files is off on this device"}

// setFiles records the device's switch.
func (m *remoteManager) setFiles(open bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.files == open {
		return
	}
	m.files = open
	m.rev++
}

// RemoteAllowFiles is the device's switch "network access to files", for the
// moment it is turned: it reaches the bridge with the next options as well
// (StartOptions.RemoteFiles), and this does not wait for those. Off closes the
// files to every request from now on.
func RemoteAllowFiles(open bool) { remote.setFiles(open) }

// remoteFilesWord is what a status answer says of the files to a caller they
// could be open to.
func remoteFilesWord(open bool) string {
	if open {
		return "open"
	}
	return "closed"
}
