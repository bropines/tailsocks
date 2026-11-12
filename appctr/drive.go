package appctr

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"tailscale.com/drive"
	"tailscale.com/drive/driveimpl"
)

var (
	driveServer      *driveimpl.FileServer
	driveServerMu    sync.Mutex
	driveProxyServer *http.Server
	// driveProxyTransport is the reverse proxy's upstream pool. http.Server.
	// Shutdown only closes the listener and the client-facing connections; the
	// connections to the daemon live here and must be closed separately,
	// otherwise every settings apply stranded the previous pool.
	driveProxyTransport *http.Transport
	driveProxyServerMu  sync.Mutex
)

// StartDriveServer starts the built-in Taildrive WebDAV file server
// and registers its local address with the tailscaled daemon.
func StartDriveServer() (string, error) {
	driveServerMu.Lock()
	if driveServer != nil {
		addr := driveServer.Addr()
		driveServerMu.Unlock()
		slog.Info("Taildrive: Server already running", "port", driveServerPort(addr))
		return addr, nil
	}

	slog.Info("Taildrive: Creating new file server...")
	server, err := driveimpl.NewFileServer()
	if err != nil {
		driveServerMu.Unlock()
		slog.Error("Taildrive: Failed to create file server", "error", err)
		return "", fmt.Errorf("failed to create drive file server: %w", err)
	}
	driveServer = server
	driveServerMu.Unlock()

	go func() {
		// Serve will run until Close() is called.
		_ = server.Serve()
	}()

	addr := server.Addr()
	slog.Info("Taildrive: Server started, registering address", "port", driveServerPort(addr))

	// Register the file server address with the local tailscaled daemon. This
	// is a LocalAPI round-trip of up to the 30s client deadline and runs
	// without driveServerMu, the way UpdateDriveShares does: StopDriveServer is
	// the first thing releaseGoResources calls, and a wedged daemon must not
	// park the whole teardown behind this registration.
	err = SetFileServerAddr(addr)
	if err != nil {
		// Clean up on failure — unless a concurrent Stop already closed and
		// replaced it while the request was in flight.
		driveServerMu.Lock()
		if driveServer == server {
			_ = server.Close()
			driveServer = nil
		}
		driveServerMu.Unlock()
		slog.Error("Taildrive: Failed to register fileserver address", "error", err)
		return "", fmt.Errorf("failed to register drive fileserver address: %w", err)
	}

	slog.Info("Taildrive: Fileserver address registered OK")
	return addr, nil
}

// driveServerPort is the port out of a FileServer address, for the log. The
// address is "<secret token>|127.0.0.1:<port>", and the token is all that
// keeps another app on the device out of the shared folders: it must not reach
// a log that is exported and shared.
func driveServerPort(addr string) string {
	if i := strings.LastIndex(addr, ":"); i >= 0 {
		return addr[i+1:]
	}
	return ""
}

// StopDriveServer stops the running WebDAV file server.
func StopDriveServer() {
	driveServerMu.Lock()
	defer driveServerMu.Unlock()

	if driveServer != nil {
		slog.Info("Taildrive: Stopping server")
		_ = driveServer.Close()
		driveServer = nil
	}
}

// ShareEntry represents a single folder shared via Taildrive.
type ShareEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// UpdateDriveShares updates the active share configuration in the WebDAV server
// and notifies the tailscaled daemon via LocalAPI PUT /localapi/v0/drive/shares.
// Expects a JSON array of ShareEntry objects: [{"Name": "share1", "Path": "/sdcard/share1"}]
func UpdateDriveShares(sharesJson string) error {
	var entries []ShareEntry
	if err := json.Unmarshal([]byte(sharesJson), &entries); err != nil {
		return fmt.Errorf("invalid shares JSON: %w", err)
	}

	driveServerMu.Lock()
	server := driveServer
	driveServerMu.Unlock()

	if server == nil {
		return fmt.Errorf("drive server is not running")
	}

	shares := make(map[string]string)
	for _, entry := range entries {
		shares[entry.Name] = entry.Path
		slog.Info("Taildrive: Share entry", "name", entry.Name, "path", entry.Path)
	}
	server.SetShares(shares)

	// Apply/sync each share with the daemon via LocalAPI.
	// 1. Remove all old shares first by fetching the current ones and deleting them.
	currentStr, err := GetDriveSharesJSON()
	if err != nil {
		slog.Warn("Taildrive: Could not fetch current shares from daemon", "error", err)
	} else {
		var currentShares []drive.Share
		if err := json.Unmarshal([]byte(currentStr), &currentShares); err == nil {
			slog.Info("Taildrive: Current daemon shares", "count", len(currentShares))
			// The daemon keeps names as drive.NormalizeShareName makes them —
			// lower case — so "Taildrop" here is "taildrop" there. Compared as
			// they were, every share named with a capital was removed and put
			// back on each apply, cutting off whoever was reading from it.
			wanted := make(map[string]bool, len(entries))
			for _, entry := range entries {
				wanted[shareKey(entry.Name)] = true
			}
			for _, cs := range currentShares {
				if wanted[shareKey(cs.Name)] {
					continue
				}
				slog.Info("Taildrive: Removing stale share", "name", cs.Name)
				if err := DeleteDriveShare(cs.Name); err != nil {
					slog.Warn("Taildrive: Could not remove stale share", "name", cs.Name, "error", err)
				}
			}
		} else {
			slog.Warn("Taildrive: Failed to parse current shares response", "error", err, "body", currentStr)
		}
	}

	// 2. Put all new/updated shares
	for _, entry := range entries {
		slog.Info("Taildrive: PUT share to daemon", "name", entry.Name, "path", entry.Path)
		err = PutDriveShare(entry.Name, entry.Path)
		if err != nil {
			slog.Error("Taildrive: Failed to register share", "name", entry.Name, "error", err)
			return fmt.Errorf("failed to register share %q: %w", entry.Name, err)
		}
		slog.Info("Taildrive: Share registered OK", "name", entry.Name)
	}

	slog.Info("Taildrive: All shares updated successfully", "total", len(entries))
	return nil
}

// shareKey is name as the daemon keeps it (drive.NormalizeShareName), for
// telling whether two names are one share.
func shareKey(name string) string {
	if n, err := drive.NormalizeShareName(name); err == nil {
		return n
	}
	return strings.ToLower(strings.TrimSpace(name))
}

// StartDriveProxy starts the WebDAV reverse-proxy on the specified local address
// with optional Basic Authentication, in front of the daemon's Taildrive
// (100.100.100.100:8080) — reached through its socket; see drive_local.go.
func StartDriveProxy(localAddr, username, password string) error {
	driveProxyServerMu.Lock()
	defer driveProxyServerMu.Unlock()

	if driveProxyServer != nil {
		slog.Info("Taildrive Proxy: Recreating proxy to apply new settings", "addr", localAddr, "has_auth", username != "")
		_ = shutdownDriveProxyLocked()
	}

	transport := newDriveTransport()
	reverseProxy := newDriveReverseProxy(transport)

	// Basic Auth when credentials are provided, and never a browser's request
	// on behalf of a web page: see driveProxyHostAllowed.
	auth := username != "" && password != ""
	handler := http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("Origin") != "" || !driveProxyHostAllowed(req.Host, driveProxySelfName()) {
			http.Error(w, "Forbidden", http.StatusForbidden)
			return
		}
		if auth {
			u, p, ok := req.BasicAuth()
			// Both compared, in constant time: how long a refusal took must
			// not say how much of a guess was right.
			userOK := subtle.ConstantTimeCompare([]byte(u), []byte(username)) == 1
			passOK := subtle.ConstantTimeCompare([]byte(p), []byte(password)) == 1
			if !ok || !userOK || !passOK {
				w.Header().Set("WWW-Authenticate", `Basic realm="Taildrive Proxy"`)
				http.Error(w, "Unauthorized", http.StatusUnauthorized)
				return
			}
		}
		reverseProxy.ServeHTTP(w, req)
	})

	server := &http.Server{
		Addr:    localAddr,
		Handler: handler,
		// A client that connects and sends nothing must not hold a
		// connection for ever; bodies — files — take what they take.
		ReadHeaderTimeout: 10 * time.Second,
	}
	driveProxyServer = server
	driveProxyTransport = transport

	go func() {
		slog.Info("Taildrive Proxy: Server started listening", "addr", localAddr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Taildrive Proxy: Server error", "error", err)
			driveProxyServerMu.Lock()
			if driveProxyServer == server {
				driveProxyServer = nil
				driveProxyTransport = nil
				transport.CloseIdleConnections()
			}
			driveProxyServerMu.Unlock()
		}
	}()

	return nil
}

// driveProxyHostAllowed reports whether a request's Host header is one a
// WebDAV client that was given this proxy's address would send: an IP
// address, localhost, or this node's own MagicDNS name (selfDNSName, full or
// short) for a client elsewhere in the tailnet. Any other name is a browser
// sent here by a web page whose own name was pointed at the device (DNS
// rebinding) — the proxy rewrites Host on the way to the daemon, so without
// this check such a page could read and write every Taildrive share. The
// remote-management endpoint refuses the same way (remoteHostIsAddress).
func driveProxyHostAllowed(host, selfDNSName string) bool {
	if remoteHostIsAddress(host) {
		return true
	}
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	if host == "" {
		return false
	}
	if host == "localhost" {
		return true
	}
	self := strings.ToLower(strings.TrimSuffix(selfDNSName, "."))
	if self == "" {
		return false
	}
	short, _, _ := strings.Cut(self, ".")
	return host == self || host == short
}

// driveProxySelfName is this node's MagicDNS name as the bus last gave it, ""
// before it has: read per request, so it must not cost a LocalAPI round trip.
func driveProxySelfName() string {
	self := GetBusState().Self
	if self == nil {
		return ""
	}
	if self.DNSName != "" {
		return self.DNSName
	}
	return self.Name // a tailcfg.Node (SelfChange) names itself here
}

// shutdownDriveProxyLocked stops the listener and closes the upstream pool.
// Caller holds driveProxyServerMu. Shared by the re-apply path and
// StopDriveProxy so neither can forget the transport again.
func shutdownDriveProxyLocked() error {
	if driveProxyServer == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	err := driveProxyServer.Shutdown(ctx)
	if driveProxyTransport != nil {
		driveProxyTransport.CloseIdleConnections()
		driveProxyTransport = nil
	}
	driveProxyServer = nil
	return err
}

// StopDriveProxy stops the WebDAV reverse-proxy.
func StopDriveProxy() error {
	driveProxyServerMu.Lock()
	defer driveProxyServerMu.Unlock()

	if driveProxyServer == nil {
		slog.Debug("Taildrive Proxy: Server is not running")
		return nil
	}

	slog.Info("Taildrive Proxy: Stopping server...")
	err := shutdownDriveProxyLocked()
	if err != nil {
		slog.Error("Taildrive Proxy: Error stopping server", "error", err)
	} else {
		slog.Info("Taildrive Proxy: Server stopped OK")
	}
	return err
}
