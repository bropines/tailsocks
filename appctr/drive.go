package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync"

	"golang.org/x/net/proxy"
	"tailscale.com/drive"
	"tailscale.com/drive/driveimpl"
)

var (
	driveServer        *driveimpl.FileServer
	driveServerMu      sync.Mutex
	driveProxyServer   *http.Server
	driveProxyServerMu sync.Mutex
)

// StartDriveServer starts the built-in Taildrive WebDAV file server
// and registers its local address with the tailscaled daemon.
func StartDriveServer() (string, error) {
	driveServerMu.Lock()
	defer driveServerMu.Unlock()

	if driveServer != nil {
		addr := driveServer.Addr()
		slog.Info("Taildrive: Server already running", "addr", addr)
		return addr, nil
	}

	slog.Info("Taildrive: Creating new file server...")
	server, err := driveimpl.NewFileServer()
	if err != nil {
		slog.Error("Taildrive: Failed to create file server", "error", err)
		return "", fmt.Errorf("failed to create drive file server: %w", err)
	}

	driveServer = server

	go func() {
		// Serve will run until Close() is called.
		_ = server.Serve()
	}()

	addr := server.Addr()
	slog.Info("Taildrive: Server started, registering address", "addr", addr)

	// Register the file server address with the local tailscaled daemon
	err = SetFileServerAddr(addr)
	if err != nil {
		// Clean up on failure
		_ = server.Close()
		driveServer = nil
		slog.Error("Taildrive: Failed to register fileserver address", "error", err)
		return "", fmt.Errorf("failed to register drive fileserver address: %w", err)
	}

	slog.Info("Taildrive: Fileserver address registered OK")
	return addr, nil
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
			for _, cs := range currentShares {
				// If a current share is not in our new list, remove it
				found := false
				for _, entry := range entries {
					if entry.Name == cs.Name {
						found = true
						break
					}
				}
				if !found {
					slog.Info("Taildrive: Removing stale share", "name", cs.Name)
					_ = DeleteDriveShare(cs.Name)
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

// StartDriveProxy starts the WebDAV reverse-proxy on the specified local address
// with optional Basic Authentication, targetting Quad100 (100.100.100.100:8080).
func StartDriveProxy(localAddr, username, password string) error {
	driveProxyServerMu.Lock()
	defer driveProxyServerMu.Unlock()

	if driveProxyServer != nil {
		slog.Info("Taildrive Proxy: Recreating proxy to apply new settings", "addr", localAddr, "has_auth", username != "")
		_ = driveProxyServer.Shutdown(context.Background())
		driveProxyServer = nil
	}

	socks, user, pass, _ := GConfig.get()
	if socks == "" {
		return fmt.Errorf("SOCKS5 proxy is not running")
	}

	var auth *proxy.Auth
	if user != "" || pass != "" {
		auth = &proxy.Auth{User: user, Password: pass}
	}

	targetUrl, err := url.Parse("http://100.100.100.100:8080")
	if err != nil {
		return err
	}

	dialer, err := proxy.SOCKS5("tcp", socks, auth, proxy.Direct)
	if err != nil {
		return fmt.Errorf("failed to create SOCKS5 dialer: %w", err)
	}

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.Dial(network, addr)
		},
	}

	reverseProxy := httputil.NewSingleHostReverseProxy(targetUrl)
	reverseProxy.Transport = transport

	originalDirector := reverseProxy.Director
	reverseProxy.Director = func(req *http.Request) {
		originalDirector(req)
		req.Host = targetUrl.Host

		// Rewrite Destination header for WebDAV MOVE/COPY
		if dest := req.Header.Get("Destination"); dest != "" {
			if parsedDest, err := url.Parse(dest); err == nil {
				parsedDest.Scheme = targetUrl.Scheme
				parsedDest.Host = targetUrl.Host
				req.Header.Set("Destination", parsedDest.String())
			}
		}
	}

	// Wrap in Basic Auth handler if credentials are provided
	var handler http.Handler = reverseProxy
	if username != "" && password != "" {
		handler = http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			u, p, ok := req.BasicAuth()
			if !ok || u != username || p != password {
				w.Header().Set("WWW-Authenticate", `Basic realm="Taildrive Proxy"`)
				http.Error(w, "Unauthorized", http.StatusUnauthorized)
				return
			}
			reverseProxy.ServeHTTP(w, req)
		})
	}

	server := &http.Server{
		Addr:    localAddr,
		Handler: handler,
	}
	driveProxyServer = server

	go func() {
		slog.Info("Taildrive Proxy: Server started listening", "addr", localAddr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Taildrive Proxy: Server error", "error", err)
			driveProxyServerMu.Lock()
			if driveProxyServer == server {
				driveProxyServer = nil
			}
			driveProxyServerMu.Unlock()
		}
	}()

	return nil
}

// StopDriveProxy stops the WebDAV reverse-proxy.
func StopDriveProxy() error {
	driveProxyServerMu.Lock()
	defer driveProxyServerMu.Unlock()

	if driveProxyServer == nil {
		slog.Info("Taildrive Proxy: Server is not running")
		return nil
	}

	slog.Info("Taildrive Proxy: Stopping server...")
	err := driveProxyServer.Shutdown(context.Background())
	driveProxyServer = nil
	if err != nil {
		slog.Error("Taildrive Proxy: Error stopping server", "error", err)
	} else {
		slog.Info("Taildrive Proxy: Server stopped OK")
	}
	return err
}
