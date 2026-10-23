package appctr

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

/*
 * Taildrive for the app's own code, as calls: the share browser, the downloads
 * and the Files provider (TaildriveClient in Kotlin) ask the bridge, and the
 * bridge makes the WebDAV request over the daemon's socket (drive_local.go)
 * with one pool of kept-alive connections. Nothing listens on a port for it,
 * and the app speaks no HTTP itself.
 *
 * Every call answers with a DriveResult and never fails across the language
 * boundary: what went wrong is in Fail, as a word the app maps onto its own
 * errors. Nothing here puts a path or an address into a result or a log: a
 * path names somebody's file.
 */

// The words of DriveResult.Fail.
const (
	DriveFailTimeout  = "timeout"   // the deadline passed
	DriveFailNoDaemon = "no-daemon" // nothing to connect to: the daemon is not running
	DriveFailCanceled = "canceled"  // DriveCall.Cancel
	DriveFailIO       = "io"        // anything else; Detail says what kind
)

// driveBodyCap bounds a body read whole: a listing of a large folder is a few
// megabytes, an error page a line.
const driveBodyCap = 64 << 20

// driveClient is the pool every call of the app uses.
var driveClient = &http.Client{Transport: newDriveTransport()}

// DriveResult is what one request answered.
type DriveResult struct {
	// Status is the HTTP status; 0 when no answer came (see Fail).
	Status int
	// Fail is empty for an answer, else one of the DriveFail words.
	Fail string
	// Detail is, for DriveFailIO, the kind of failure — never its message,
	// which would name the file.
	Detail string
	// Body is the answer's body: a listing, an error page; for Range, the
	// bytes asked for, on a 206 only.
	Body []byte
	// ContentRange and ContentLength are the answer's headers of those names,
	// empty when it had none.
	ContentRange  string
	ContentLength string
}

// DriveCall is one request of the app's, or one stream. Cancel, from any
// thread, ends whatever it is doing.
type DriveCall struct {
	mu       sync.Mutex
	cancels  []context.CancelFunc
	canceled bool
}

// NewDriveCall is a call not yet made.
func NewDriveCall() *DriveCall { return &DriveCall{} }

// Cancel aborts the call's request in flight and every later one.
func (c *DriveCall) Cancel() {
	c.mu.Lock()
	c.canceled = true
	cancels := c.cancels
	c.cancels = nil
	c.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
}

// context is a context for one request of c, with timeoutMs if it is above 0.
func (c *DriveCall) context(timeoutMs int) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	if timeoutMs > 0 {
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	}
	c.mu.Lock()
	if c.canceled {
		cancel()
	} else {
		c.cancels = append(c.cancels, cancel)
	}
	c.mu.Unlock()
	return ctx, cancel
}

func (c *DriveCall) isCanceled() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.canceled
}

// driveURL is the service address's URL of the plain path p.
func driveURL(p string) string {
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return (&url.URL{Scheme: driveTarget.Scheme, Host: driveTarget.Host, Path: p}).String()
}

// failure is r with err filed under its word.
func (c *DriveCall) failure(err error) *DriveResult {
	r := &DriveResult{}
	switch {
	case c.isCanceled() || errors.Is(err, context.Canceled):
		r.Fail = DriveFailCanceled
	case errors.Is(err, context.DeadlineExceeded) || os.IsTimeout(err):
		r.Fail = DriveFailTimeout
	case isNoDaemon(err):
		r.Fail = DriveFailNoDaemon
	default:
		r.Fail = DriveFailIO
		inner := err
		for {
			next := errors.Unwrap(inner)
			if next == nil {
				break
			}
			inner = next
		}
		if inner == io.ErrUnexpectedEOF || inner == io.EOF {
			r.Detail = "no answer"
		} else {
			r.Detail = fmt.Sprintf("%T", inner)
		}
	}
	return r
}

// Request makes one request with an empty body and reads the answer whole:
// PROPFIND (depth "1" or "0"), OPTIONS, DELETE, MKCOL, MOVE (to destination,
// a plain path), PUT of an empty file. Empty depth or destination sends none.
func (c *DriveCall) Request(method, path, depth, destination string, timeoutMs int) *DriveResult {
	ctx, cancel := c.context(timeoutMs)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, driveURL(path), http.NoBody)
	if err != nil {
		return c.failure(err)
	}
	if depth != "" {
		req.Header.Set("Depth", depth)
	}
	if destination != "" {
		req.Header.Set("Destination", driveURL(destination))
	}
	if method == http.MethodPut {
		req.ContentLength = 0
	}
	resp, err := driveClient.Do(req)
	if err != nil {
		return c.failure(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, driveBodyCap))
	if err != nil {
		return c.failure(err)
	}
	return answer(resp, body)
}

func answer(resp *http.Response, body []byte) *DriveResult {
	return &DriveResult{
		Status:        resp.StatusCode,
		Body:          body,
		ContentRange:  resp.Header.Get("Content-Range"),
		ContentLength: resp.Header.Get("Content-Length"),
	}
}

// Range asks for length bytes of path from offset. A 206 carries the bytes in
// Body — all of them, or the rest of the file where it ends first — and the
// file's length in ContentRange; any other answer only its status and
// headers.
func (c *DriveCall) Range(path string, offset int64, length int, timeoutMs int) *DriveResult {
	ctx, cancel := c.context(timeoutMs)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, driveURL(path), http.NoBody)
	if err != nil {
		return c.failure(err)
	}
	req.Header.Set("Range", "bytes="+strconv.FormatInt(offset, 10)+"-"+strconv.FormatInt(offset+int64(length)-1, 10))
	resp, err := driveClient.Do(req)
	if err != nil {
		return c.failure(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent {
		// An error page or a whole file the server sent instead: not ours to read.
		io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		return answer(resp, nil)
	}
	// As many bytes as the answer says it carries: all asked for, or fewer
	// where the file ends. A body cut short is a failure, not a short file.
	want := length
	if first, last, ok := parseContentRange(resp.Header.Get("Content-Range")); ok && last-first+1 < int64(want) {
		want = int(last - first + 1)
	}
	buf := make([]byte, want)
	if _, err := io.ReadFull(resp.Body, buf); err != nil {
		r := c.failure(err)
		if r.Fail == DriveFailIO {
			r.Detail = "short body"
		}
		return r
	}
	return answer(resp, buf)
}

// parseContentRange is the first and last byte of "bytes first-last/total".
func parseContentRange(v string) (first, last int64, ok bool) {
	v, found := strings.CutPrefix(strings.TrimSpace(v), "bytes ")
	if !found {
		return 0, 0, false
	}
	span, _, _ := strings.Cut(v, "/")
	a, b, found := strings.Cut(span, "-")
	if !found {
		return 0, 0, false
	}
	first, err1 := strconv.ParseInt(strings.TrimSpace(a), 10, 64)
	last, err2 := strconv.ParseInt(strings.TrimSpace(b), 10, 64)
	if err1 != nil || err2 != nil || last < first {
		return 0, 0, false
	}
	return first, last, true
}

// DriveStream is the body of a GET of a whole file, read a piece at a time.
type DriveStream struct {
	// Result is the answer's status and headers, or why there is none; its
	// Body is empty.
	Result *DriveResult
	call   *DriveCall
	body   io.ReadCloser
	cancel context.CancelFunc
}

// Open sends a GET of path and returns its body to Read. The deadline,
// timeoutMs, is for the answer to begin; the body is not hurried.
func (c *DriveCall) Open(path string, timeoutMs int) *DriveStream {
	ctx, cancel := c.context(0)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, driveURL(path), http.NoBody)
	if err != nil {
		cancel()
		return &DriveStream{Result: c.failure(err)}
	}
	var timer *time.Timer
	if timeoutMs > 0 {
		timer = time.AfterFunc(time.Duration(timeoutMs)*time.Millisecond, cancel)
	}
	resp, err := driveClient.Do(req)
	if timer != nil && !timer.Stop() && err == nil {
		err = context.DeadlineExceeded
		resp.Body.Close()
	}
	if err != nil {
		cancel()
		r := c.failure(err)
		if timer != nil && errors.Is(err, context.Canceled) && !c.isCanceled() {
			r.Fail = DriveFailTimeout
		}
		return &DriveStream{Result: r}
	}
	return &DriveStream{Result: answer(resp, nil), call: c, body: resp.Body, cancel: cancel}
}

// Read returns up to max bytes of the body; none at its end.
func (s *DriveStream) Read(max int) ([]byte, error) {
	if s.body == nil {
		return nil, nil
	}
	buf := make([]byte, max)
	n, err := io.ReadAtLeast(s.body, buf, 1)
	if err == io.EOF {
		return nil, nil
	}
	if err != nil && n == 0 {
		r := s.call.failure(err)
		return nil, errors.New("Taildrive: " + r.Fail + " " + r.Detail)
	}
	return buf[:n], nil
}

// Close ends the stream, read to its end or not.
func (s *DriveStream) Close() {
	if s.body != nil {
		s.body.Close()
	}
	if s.cancel != nil {
		s.cancel()
	}
}

// DriveUpload is the body of a PUT, written a piece at a time.
type DriveUpload struct {
	call *DriveCall
	pw   *io.PipeWriter
	done chan *DriveResult
}

// Upload starts a PUT of path whose body is what Write is given.
func (c *DriveCall) Upload(path string) *DriveUpload {
	ctx, cancel := c.context(0)
	pr, pw := io.Pipe()
	u := &DriveUpload{call: c, pw: pw, done: make(chan *DriveResult, 1)}
	go func() {
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodPut, driveURL(path), pr)
		if err != nil {
			pr.CloseWithError(err)
			u.done <- c.failure(err)
			return
		}
		resp, err := driveClient.Do(req)
		if err != nil {
			pr.CloseWithError(err)
			u.done <- c.failure(err)
			return
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		resp.Body.Close()
		pr.Close()
		u.done <- answer(resp, body)
	}()
	return u
}

// Write sends p as the next piece of the body.
func (u *DriveUpload) Write(p []byte) error {
	if _, err := u.pw.Write(p); err != nil {
		r := u.call.failure(err)
		return errors.New("Taildrive: " + r.Fail + " " + r.Detail)
	}
	return nil
}

// Finish ends the body and waits up to timeoutMs for the answer.
func (u *DriveUpload) Finish(timeoutMs int) *DriveResult {
	u.pw.Close()
	if timeoutMs <= 0 {
		return <-u.done
	}
	select {
	case r := <-u.done:
		return r
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		u.call.Cancel()
		<-u.done
		return &DriveResult{Fail: DriveFailTimeout}
	}
}

// Abort gives the upload up: the request is cut and nothing more is sent.
func (u *DriveUpload) Abort() {
	u.pw.CloseWithError(context.Canceled)
	u.call.Cancel()
}
