package appctr

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

/*
 * How the bridge reaches the tailnet's Taildrive shares, without the SOCKS5 port.
 *
 * The daemon serves what answers at 100.100.100.100:8080 on a unix socket
 * beside its own (patch 26, cmd/tailscaled/android_drive.go). Everything here
 * that talks Taildrive dials that socket: the app's own client — calls into
 * the bridge, drive_client.go — and the WebDAV proxy other apps use. A daemon
 * without the socket — one from before it, still running across an update —
 * is reached the old way, through SOCKS5.
 */

// DriveSocketName must equal driveSocketName in patch 26.
const DriveSocketName = "drive.sock"

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
		// A reply that has not begun in this long is not coming; a body, once
		// it flows, takes what it takes.
		ResponseHeaderTimeout: 60 * time.Second,
		// The daemon's WebDAV is plain HTTP/1.1 on a local socket; a body is a
		// file, and compressing it would only cost.
		DisableCompression: true,
	}
}

// isNoDaemon reports whether err is the failure to reach the daemon at all.
func isNoDaemon(err error) bool {
	var op *net.OpError
	return errors.As(err, &op) && op.Op == "dial"
}

// newDriveReverseProxy forwards to the daemon's Taildrive over transport,
// rewriting the Host and a MOVE/COPY Destination to the service address.
func newDriveReverseProxy(transport http.RoundTripper) *httputil.ReverseProxy {
	rp := httputil.NewSingleHostReverseProxy(driveTarget)
	rp.Transport = transport
	// No daemon to reach is a 503, told apart from the 502 a daemon gives for
	// a device whose file server is gone.
	rp.ErrorHandler = func(w http.ResponseWriter, req *http.Request, err error) {
		if isNoDaemon(err) {
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
