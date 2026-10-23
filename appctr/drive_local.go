package appctr

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync"
	"time"
)

/*
 * How the app reaches the tailnet's Taildrive shares, without the SOCKS5 port.
 *
 * The daemon serves what answers at 100.100.100.100:8080 on a unix socket
 * beside its own (patch 26, cmd/tailscaled/android_drive.go). Everything here
 * that talks Taildrive dials that socket: the WebDAV proxy other apps use, and
 * the loopback endpoint the app's own client (TaildriveClient in Kotlin) uses
 * as an HTTP proxy. A daemon without the socket — one from before it, still
 * running across an update — is reached the old way, through SOCKS5.
 */

// DriveSocketName must equal driveSocketName in patch 26.
const DriveSocketName = "drive.sock"

// DriveLocalHeader carries the endpoint's token. Without it the endpoint
// refuses: any app on the device can connect to a loopback port.
const DriveLocalHeader = "X-TailSocks-Drive"

// DriveErrorHeader, set to DriveErrorNoDaemon, marks an answer that did not
// come from the daemon because there was none to reach.
const (
	DriveErrorHeader   = "X-TailSocks-Drive-Error"
	DriveErrorNoDaemon = "no-daemon"
)

// driveTarget is where the requests go, as the daemon names its Taildrive.
var driveTarget = &url.URL{Scheme: "http", Host: "100.100.100.100:8080"}

// driveDial connects to the daemon's Taildrive: its socket, or through SOCKS5
// when the socket is not there.
func driveDial(ctx context.Context, _, _ string) (net.Conn, error) {
	var d net.Dialer
	c, err := d.DialContext(ctx, "unix", getPC().DataDir(DriveSocketName))
	if err == nil {
		return c, nil
	}
	socks, user, pass, _ := GConfig.get()
	if socks == "" {
		return nil, err
	}
	dial, serr := socksDialContext(socks, user, pass)
	if serr != nil {
		return nil, err
	}
	return dial(ctx, "tcp", driveTarget.Host)
}

// newDriveTransport is an upstream pool to the daemon's Taildrive. Parallel
// downloads and a player's reads each hold a connection, so it keeps several.
func newDriveTransport() *http.Transport {
	return &http.Transport{
		DialContext:         driveDial,
		MaxIdleConnsPerHost: 16,
		IdleConnTimeout:     90 * time.Second,
		// The daemon's WebDAV is plain HTTP/1.1 on a local socket; a body is a
		// file, and compressing it would only cost.
		DisableCompression: true,
	}
}

// newDriveReverseProxy forwards to the daemon's Taildrive over transport,
// rewriting the Host and a MOVE/COPY Destination to the service address.
func newDriveReverseProxy(transport http.RoundTripper) *httputil.ReverseProxy {
	rp := httputil.NewSingleHostReverseProxy(driveTarget)
	rp.Transport = transport
	// No daemon to dial is told apart from the daemon's own 502 for a device
	// whose file server is gone: the app shows "not connected" for it.
	rp.ErrorHandler = func(w http.ResponseWriter, req *http.Request, err error) {
		var op *net.OpError
		if errors.As(err, &op) && op.Op == "dial" {
			w.Header().Set(DriveErrorHeader, DriveErrorNoDaemon)
			http.Error(w, "Taildrive: no daemon", http.StatusServiceUnavailable)
			return
		}
		if req.Context().Err() == nil {
			slog.Warn("Taildrive: request to the daemon failed", "method", req.Method, "error", err)
		}
		w.WriteHeader(http.StatusBadGateway)
	}
	direct := rp.Director
	rp.Director = func(req *http.Request) {
		direct(req)
		req.Host = driveTarget.Host
		if dest := req.Header.Get("Destination"); dest != "" {
			if u, err := url.Parse(dest); err == nil {
				u.Scheme = driveTarget.Scheme
				u.Host = driveTarget.Host
				req.Header.Set("Destination", u.String())
			}
		}
	}
	return rp
}

var driveLocal struct {
	mu        sync.Mutex
	addr      string
	token     string
	transport *http.Transport
}

// DriveLocalAddr is the loopback endpoint the app's own Taildrive client uses
// as an HTTP proxy, host:port; started on the first call and kept for the
// life of the process. Empty when it could not start: the client then goes
// through SOCKS5 as before.
func DriveLocalAddr() string {
	driveLocal.mu.Lock()
	defer driveLocal.mu.Unlock()
	if driveLocal.addr != "" {
		return driveLocal.addr
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		slog.Error("Taildrive: no local endpoint", "error", err)
		return ""
	}
	var b [24]byte
	if _, err := rand.Read(b[:]); err != nil {
		ln.Close()
		return ""
	}
	token := hex.EncodeToString(b[:])
	transport := newDriveTransport()
	rp := newDriveReverseProxy(transport)
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			if subtle.ConstantTimeCompare([]byte(req.Header.Get(DriveLocalHeader)), []byte(token)) != 1 {
				http.Error(w, "Forbidden", http.StatusForbidden)
				return
			}
			req.Header.Del(DriveLocalHeader)
			req.Header.Del("Proxy-Connection")
			rp.ServeHTTP(w, req)
		}),
		ReadHeaderTimeout: 30 * time.Second,
	}
	go srv.Serve(ln)
	driveLocal.addr = ln.Addr().String()
	driveLocal.token = token
	driveLocal.transport = transport
	slog.Info("Taildrive: local endpoint started")
	return driveLocal.addr
}

// DriveLocalToken is the value of DriveLocalHeader the endpoint wants; empty
// before DriveLocalAddr has started it.
func DriveLocalToken() string {
	driveLocal.mu.Lock()
	defer driveLocal.mu.Unlock()
	return driveLocal.token
}
