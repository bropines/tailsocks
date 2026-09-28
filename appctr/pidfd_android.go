//go:build android
// +build android

package appctr

// pidfd_android.go — survive Android's seccomp filter on the first process the
// bridge starts.
//
// Since Go 1.23 the os package prefers pidfds over raw pids for the processes
// it starts, and before the first one it probes whether the pidfd syscalls
// work. On Android 11 and older those syscalls are not in the app sandbox's
// seccomp allowlist, so the probe does not fail — it raises SIGSYS, and the
// kernel kills the process. Go knows about this (golang.org/issue/69065) and
// guards the probe by telling its own signal handler to let a seccomp SIGSYS
// through; but a c-shared library is only allowed to install handlers for the
// synchronous signals, so in this .so the guard is never consulted and the
// whole app dies the moment the daemon is launched. That is the Android 10
// crash in issue #11: the core logs its banner, calls killall, and is gone.
//
// So the probe happens here instead, once, before anything can start a
// process, with a handler of our own that simply returns. The trapped syscall
// then reports failure the way a missing syscall would (-ENOSYS on arm64, a
// bogus return elsewhere, which the probe's next step rejects anyway), the os
// package settles on pids for the rest of the run, and the previous handler —
// on Android, the one that writes tombstones — is put back. Where the syscalls
// are allowed, nothing is trapped and nothing changes.

/*
#include <pthread.h>
#include <signal.h>
#include <string.h>

static struct sigaction pidfdOldAction;
static sigset_t pidfdOldMask;
static volatile sig_atomic_t pidfdTrapped;

static void pidfdOnSigsys(int sig, siginfo_t *info, void *ctx) {
	(void)sig;
	(void)info;
	(void)ctx;
	pidfdTrapped = 1;
}

// pidfdMuteSigsys makes a seccomp SIGSYS survivable on the calling thread.
// Ignoring the signal would not do: the kernel forces the default action —
// death — on a SIGSYS it has to deliver, whether or not the process wants it.
static int pidfdMuteSigsys(void) {
	sigset_t unblock;
	struct sigaction sa;

	sigemptyset(&unblock);
	sigaddset(&unblock, SIGSYS);
	if (pthread_sigmask(SIG_UNBLOCK, &unblock, &pidfdOldMask) != 0) {
		return -1;
	}
	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = pidfdOnSigsys;
	sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
	sigemptyset(&sa.sa_mask);
	if (sigaction(SIGSYS, &sa, &pidfdOldAction) != 0) {
		pthread_sigmask(SIG_SETMASK, &pidfdOldMask, NULL);
		return -1;
	}
	return 0;
}

static void pidfdRestoreSigsys(void) {
	sigaction(SIGSYS, &pidfdOldAction, NULL);
	pthread_sigmask(SIG_SETMASK, &pidfdOldMask, NULL);
}

static int pidfdWasTrapped(void) { return pidfdTrapped; }
*/
import "C"

import (
	"log/slog"
	"os"
	"runtime"
)

// pidfdBlocked records that the probe was answered with SIGSYS, i.e. this
// Android is old enough to forbid the pidfd syscalls. Worth a line in the log
// of every such device: it is the difference between "pids only" being a
// deliberate fallback and being a mystery.
var pidfdBlocked bool

func init() {
	// The handler is process-wide but the signal mask is not, and the probe's
	// syscalls have to happen on the thread whose mask we just opened.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if C.pidfdMuteSigsys() != 0 {
		return
	}
	defer C.pidfdRestoreSigsys()

	// FindProcess is the cheapest way in: it runs the os package's one-time
	// pidfd check and caches the verdict for every process started later.
	if p, err := os.FindProcess(os.Getpid()); err == nil && p != nil {
		_ = p.Release()
	}
	pidfdBlocked = C.pidfdWasTrapped() != 0
}

// reportProcessHandles says, once the logger exists, whether this device made
// us fall back.
func reportProcessHandles() {
	if pidfdBlocked {
		slog.Info("pidfd syscalls are blocked by the app sandbox here (Android 11 or older), using pids for child processes")
	}
}
