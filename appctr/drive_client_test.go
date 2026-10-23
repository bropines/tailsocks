package appctr

import (
	"bytes"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeShare is a share as the daemon answers for it: one file, ranges, a
// listing, MOVE and PUT, and what each request looked like.
type fakeShare struct {
	mu       sync.Mutex
	file     []byte
	requests []*http.Request
	put      []byte
	stall    chan struct{} // while set, GET waits on it
}

func (f *fakeShare) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.requests = append(f.requests, r.Clone(r.Context()))
	stall := f.stall
	f.mu.Unlock()
	if r.Host != "100.100.100.100:8080" {
		http.Error(w, "wrong host", http.StatusBadRequest)
		return
	}
	switch r.Method {
	case "PROPFIND":
		w.WriteHeader(207)
		fmt.Fprintf(w, `<D:multistatus xmlns:D="DAV:"><D:response><D:href>%s</D:href></D:response></D:multistatus>`, r.URL.EscapedPath())
	case "MOVE":
		w.WriteHeader(http.StatusCreated)
	case "PUT":
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.put = b
		f.mu.Unlock()
		w.WriteHeader(http.StatusCreated)
	case "GET":
		if stall != nil {
			select {
			case <-stall:
			case <-r.Context().Done():
				return
			}
		}
		http.ServeContent(w, r, "f.bin", time.Time{}, bytes.NewReader(f.file))
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (f *fakeShare) last() *http.Request {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.requests[len(f.requests)-1]
}

func newFakeShare(t *testing.T, size int) *fakeShare {
	f := &fakeShare{file: make([]byte, size)}
	rand.New(rand.NewSource(3)).Read(f.file)
	fakeDriveSocket(t, f)
	return f
}

func TestDriveRequestListsAndEscapes(t *testing.T) {
	f := newFakeShare(t, 10)
	r := NewDriveCall().Request("PROPFIND", "/tailnet/dev/share/a b#c%d+e.txt", "1", "", 5000)
	if r.Fail != "" || r.Status != 207 {
		t.Fatalf("PROPFIND: %+v", r)
	}
	got := f.last()
	if got.Header.Get("Depth") != "1" {
		t.Errorf("Depth = %q", got.Header.Get("Depth"))
	}
	if got.URL.Path != "/tailnet/dev/share/a b#c%d+e.txt" {
		t.Errorf("the daemon got path %q", got.URL.Path)
	}
	if !strings.Contains(string(r.Body), "a%20b%23c%25d+e.txt") {
		t.Errorf("listing %q", r.Body)
	}
}

func TestDriveRequestMove(t *testing.T) {
	f := newFakeShare(t, 10)
	r := NewDriveCall().Request("MOVE", "/t/d/s/old name.txt", "", "/t/d/s/new name.txt", 5000)
	if r.Status != http.StatusCreated {
		t.Fatalf("MOVE: %+v", r)
	}
	if got, want := f.last().Header.Get("Destination"), "http://100.100.100.100:8080/t/d/s/new%20name.txt"; got != want {
		t.Errorf("Destination = %q, want %q", got, want)
	}
}

func TestDriveRange(t *testing.T) {
	f := newFakeShare(t, 5000)
	c := NewDriveCall()
	r := c.Range("/t/d/s/f.bin", 1000, 1024, 5000)
	if r.Status != http.StatusPartialContent || !bytes.Equal(r.Body, f.file[1000:2024]) {
		t.Fatalf("range: status %d, %d bytes", r.Status, len(r.Body))
	}
	if r.ContentRange != "bytes 1000-2023/5000" {
		t.Errorf("Content-Range = %q", r.ContentRange)
	}
	// Where the file ends first: the rest of it.
	r = c.Range("/t/d/s/f.bin", 4500, 1024, 5000)
	if r.Status != 206 || !bytes.Equal(r.Body, f.file[4500:]) {
		t.Fatalf("the last range: status %d, %d bytes", r.Status, len(r.Body))
	}
	// Past the end: the status and the length, no bytes.
	r = c.Range("/t/d/s/f.bin", 6000, 10, 5000)
	if r.Status != http.StatusRequestedRangeNotSatisfiable || len(r.Body) != 0 || r.ContentRange != "bytes */5000" {
		t.Fatalf("past the end: %+v", r)
	}
}

func TestDriveRangesInParallelShareThePool(t *testing.T) {
	f := newFakeShare(t, 1<<20)
	var wg sync.WaitGroup
	errs := make(chan string, 64)
	for i := 0; i < 64; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			off := int64(i * 16384)
			r := NewDriveCall().Range("/t/d/s/f.bin", off, 16384, 10000)
			if r.Status != 206 || !bytes.Equal(r.Body, f.file[off:off+16384]) {
				errs <- "range " + strconv.Itoa(i)
			}
		}(i)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Error(e)
	}
}

func TestDriveStream(t *testing.T) {
	f := newFakeShare(t, 300000)
	s := NewDriveCall().Open("/t/d/s/f.bin", 5000)
	if s.Result.Fail != "" || s.Result.Status != 200 || s.Result.ContentLength != "300000" {
		t.Fatalf("open: %+v", s.Result)
	}
	var got []byte
	for {
		b, err := s.Read(65536)
		if err != nil {
			t.Fatal(err)
		}
		if len(b) == 0 {
			break
		}
		got = append(got, b...)
	}
	s.Close()
	if !bytes.Equal(got, f.file) {
		t.Fatalf("read %d bytes, want the %d of the file", len(got), len(f.file))
	}
}

func TestDriveUpload(t *testing.T) {
	f := newFakeShare(t, 10)
	u := NewDriveCall().Upload("/t/d/s/new.bin")
	want := bytes.Repeat([]byte("taildrive "), 50000)
	for i := 0; i < len(want); i += 4096 {
		end := min(i+4096, len(want))
		if err := u.Write(want[i:end]); err != nil {
			t.Fatal(err)
		}
	}
	r := u.Finish(5000)
	if r.Fail != "" || r.Status != http.StatusCreated {
		t.Fatalf("finish: %+v", r)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if !bytes.Equal(f.put, want) {
		t.Fatalf("the daemon got %d bytes, want %d", len(f.put), len(want))
	}
}

func TestDriveCancelEndsAWaitingRange(t *testing.T) {
	f := newFakeShare(t, 4096)
	f.mu.Lock()
	f.stall = make(chan struct{})
	f.mu.Unlock()
	defer close(f.stall)
	c := NewDriveCall()
	done := make(chan *DriveResult, 1)
	go func() { done <- c.Range("/t/d/s/f.bin", 0, 1024, 30000) }()
	time.Sleep(100 * time.Millisecond)
	c.Cancel()
	select {
	case r := <-done:
		if r.Fail != DriveFailCanceled {
			t.Fatalf("after Cancel: %+v", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Cancel did not end the request")
	}
	// A call canceled before it is made is not made.
	if r := c.Range("/t/d/s/f.bin", 0, 1024, 30000); r.Fail != DriveFailCanceled {
		t.Fatalf("a request of a canceled call: %+v", r)
	}
}

func TestDriveTimeout(t *testing.T) {
	f := newFakeShare(t, 4096)
	f.mu.Lock()
	f.stall = make(chan struct{})
	f.mu.Unlock()
	defer close(f.stall)
	if r := NewDriveCall().Range("/t/d/s/f.bin", 0, 1024, 200); r.Fail != DriveFailTimeout {
		t.Fatalf("a stalled range: %+v", r)
	}
	if s := NewDriveCall().Open("/t/d/s/f.bin", 200); s.Result.Fail != DriveFailTimeout {
		t.Fatalf("a stalled open: %+v", s.Result)
	}
}

func TestDriveWithoutTheDaemon(t *testing.T) {
	noDaemon(t)
	if r := NewDriveCall().Request("PROPFIND", "/", "1", "", 5000); r.Fail != DriveFailNoDaemon || r.Status != 0 {
		t.Fatalf("no daemon: %+v", r)
	}
	if r := NewDriveCall().Range("/t/d/s/f.bin", 0, 10, 5000); r.Fail != DriveFailNoDaemon {
		t.Fatalf("no daemon, a range: %+v", r)
	}
	if r := NewDriveCall().Upload("/t/d/s/x").Finish(5000); r.Fail != DriveFailNoDaemon {
		t.Fatalf("no daemon, an upload: %+v", r)
	}
}

// A result names no file: the detail of a failure is its kind.
func TestDriveFailureNamesNoPath(t *testing.T) {
	noDaemon(t)
	r := NewDriveCall().Request("PROPFIND", "/tailnet/secret-device/private/file.txt", "0", "", 5000)
	for _, s := range []string{r.Fail, r.Detail} {
		if strings.Contains(s, "secret") || strings.Contains(s, "private") || strings.Contains(s, "drive.sock") {
			t.Fatalf("a result names the path: %+v", r)
		}
	}
}

// A body that stops before the range it announced is a failure.
func TestDriveRangeCutShort(t *testing.T) {
	fakeDriveSocket(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 0-1023/5000")
		w.Header().Set("Content-Length", "1024")
		w.WriteHeader(206)
		w.Write(make([]byte, 500))
		// The handler ends with 524 bytes owed: the connection is closed.
	}))
	r := NewDriveCall().Range("/t/d/s/f.bin", 0, 1024, 5000)
	if r.Fail != DriveFailIO || r.Detail != "short body" {
		t.Fatalf("a body cut short: %+v", r)
	}
}

// A range that stops arriving is cut after driveIdle as a timeout, however
// long its deadline: asked again, a stalled connection is replaced.
func TestDriveRangeIdle(t *testing.T) {
	old := driveIdle
	driveIdle = 300 * time.Millisecond
	defer func() { driveIdle = old }()
	release := make(chan struct{})
	defer close(release)
	fakeDriveSocket(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 0-65535/100000")
		w.Header().Set("Content-Length", "65536")
		w.WriteHeader(206)
		w.Write(make([]byte, 1000))
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	start := time.Now()
	r := NewDriveCall().Range("/t/d/s/f.bin", 0, 65536, 60000)
	if r.Fail != DriveFailTimeout {
		t.Fatalf("a stalled body: %+v", r)
	}
	if d := time.Since(start); d > 5*time.Second {
		t.Fatalf("gave up after %v, not the idle wait", d)
	}
}

// A slow body that keeps arriving is not cut, however long it takes in all.
func TestDriveRangeSlowButMoving(t *testing.T) {
	old := driveIdle
	driveIdle = 300 * time.Millisecond
	defer func() { driveIdle = old }()
	fakeDriveSocket(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 0-9999/10000")
		w.Header().Set("Content-Length", "10000")
		w.WriteHeader(206)
		for i := 0; i < 10; i++ {
			w.Write(make([]byte, 1000))
			w.(http.Flusher).Flush()
			time.Sleep(100 * time.Millisecond)
		}
	}))
	r := NewDriveCall().Range("/t/d/s/f.bin", 0, 10000, 60000)
	if r.Fail != "" || len(r.Body) != 10000 {
		t.Fatalf("a slow body: fail %q, %d bytes", r.Fail, len(r.Body))
	}
}
