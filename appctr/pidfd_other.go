//go:build !android
// +build !android

package appctr

// Only Android's app sandbox traps the pidfd syscalls; see pidfd_android.go.
func reportProcessHandles() {}
