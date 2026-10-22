package appctr

import (
	"os"
	"syscall"
	"testing"
)

// dupFD gives the bridge a descriptor of its own, the way Kotlin hands over a
// detached duplicate.
func dupFD(t *testing.T, f *os.File) int32 {
	t.Helper()
	raw, err := f.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var fd int
	var dupErr error
	if err := raw.Control(func(u uintptr) { fd, dupErr = syscall.Dup(int(u)) }); err != nil {
		t.Fatal(err)
	}
	if dupErr != nil {
		t.Fatal(dupErr)
	}
	return int32(fd)
}

// Without a daemon on a native device the swap is refused, so the app
// relaunches — but the bridge keeps the new device for that launch: the old
// one is down once the new one is established.
func TestSwapNativeTunWithoutADaemonKeepsTheNewDevice(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	defer ClearNativeTun(false)

	if got := SwapNativeTun(dupFD(t, r), false); got == "" {
		t.Fatal("a swap with no daemon running succeeded")
	}
	if !HasNativeTun() {
		t.Fatal("the bridge dropped the new device after a refused swap")
	}
	first := nativeTunFile()

	// A second device replaces the first, which the bridge closes.
	if got := SwapNativeTun(dupFD(t, r), false); got == "" {
		t.Fatal("a swap with no daemon running succeeded")
	}
	if nativeTunFile() == first {
		t.Fatal("the bridge kept the older device")
	}
	if _, err := first.Stat(); err == nil {
		t.Fatal("the replaced device is still open")
	}

	// Its transparent mode goes with it, to the next launch's environment.
	if nativeTunEgress() {
		t.Fatal("transparent mode on for a device handed over without it")
	}
	SwapNativeTun(dupFD(t, r), true)
	if !nativeTunEgress() {
		t.Fatal("transparent mode lost with the device")
	}
	ClearNativeTun(false)
	if nativeTunEgress() || HasNativeTun() {
		t.Fatal("ClearNativeTun kept the device or its transparent mode")
	}
}
