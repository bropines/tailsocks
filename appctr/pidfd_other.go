//go:build !android || !cgo
// +build !android !cgo

package appctr

// Only Android's app sandbox traps the pidfd syscalls; see pidfd_android.go —
// which is a cgo file, so a build without cgo needs this stub too, even on
// Android, or it fails to link something it never had.
func reportProcessHandles() {}
