package appctr

import (
	"context"
	"io"
	"sync"
	"sync/atomic"
)

// A Taildrop send the app can watch and stop.
//
// The daemon reports nothing about a file on its way out: the only measure is
// how much of it the LocalAPI request has read. The daemon streams that body
// to the peer with little buffering of its own, so what it has taken is, to
// within a few hundred kilobytes, what has left the device.

// sendCancelled is what a send stopped by CancelSendFile returns after
// "Error: "; the app shows its own word for it.
const sendCancelled = "cancelled"

type trackedSend struct {
	sent   atomic.Int64
	cancel context.CancelFunc
}

var (
	trackedSendsMu sync.Mutex
	trackedSends   = map[string]*trackedSend{}
)

type sentCounter struct {
	r io.Reader
	n *atomic.Int64
}

func (c *sentCounter) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}

// SendFileTracked is SendFileFromAPI under a name: while it runs,
// SendFileProgress(id) says how far it is and CancelSendFile(id) stops it.
// Blocking, like the plain send; the id is the caller's and one send's.
func SendFileTracked(id, peerID, filePath string) string {
	ctx, cancel := context.WithCancel(context.Background())
	t := &trackedSend{cancel: cancel}
	trackedSendsMu.Lock()
	trackedSends[id] = t
	trackedSendsMu.Unlock()
	defer func() {
		cancel()
		trackedSendsMu.Lock()
		delete(trackedSends, id)
		trackedSendsMu.Unlock()
	}()
	return sendFile(ctx, peerID, filePath, &t.sent)
}

// SendFileProgress is the bytes of send id the daemon has taken, or -1 when
// no such send is running.
func SendFileProgress(id string) int64 {
	trackedSendsMu.Lock()
	t := trackedSends[id]
	trackedSendsMu.Unlock()
	if t == nil {
		return -1
	}
	return t.sent.Load()
}

// CancelSendFile stops send id, if it is running.
func CancelSendFile(id string) {
	trackedSendsMu.Lock()
	t := trackedSends[id]
	trackedSendsMu.Unlock()
	if t != nil {
		t.cancel()
	}
}
