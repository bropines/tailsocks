package appctr

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"sync"

	"tailscale.com/drive"
	"tailscale.com/drive/driveimpl"
)

var (
	driveServer   *driveimpl.FileServer
	driveServerMu sync.Mutex
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
	_, err = doLocalRequest("PUT", "/localapi/v0/drive/fileserver-address", strings.NewReader(addr))
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
func StopDriveServer() error {
	driveServerMu.Lock()
	defer driveServerMu.Unlock()

	if driveServer == nil {
		slog.Info("Taildrive: Stop requested but server is not running")
		return nil
	}

	slog.Info("Taildrive: Stopping server...")
	err := driveServer.Close()
	driveServer = nil
	if err != nil {
		slog.Error("Taildrive: Error stopping server", "error", err)
	} else {
		slog.Info("Taildrive: Server stopped OK")
	}
	return err
}

// ShareEntry represents a single folder shared via Taildrive.
type ShareEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// UpdateDriveShares updates the list of shares served by the WebDAV server.
// sharesJson is a JSON array of ShareEntry objects.
func UpdateDriveShares(sharesJson string) error {
	slog.Info("Taildrive: UpdateDriveShares called", "json", sharesJson)

	driveServerMu.Lock()
	server := driveServer
	driveServerMu.Unlock()

	if server == nil {
		slog.Error("Taildrive: UpdateDriveShares failed — server is not running")
		return fmt.Errorf("drive server is not running")
	}

	var entries []ShareEntry
	if err := json.Unmarshal([]byte(sharesJson), &entries); err != nil {
		slog.Error("Taildrive: Failed to parse shares JSON", "error", err)
		return fmt.Errorf("failed to parse shares JSON: %w", err)
	}

	slog.Info("Taildrive: Parsed share entries", "count", len(entries))

	// Update the file server shares (name -> local path)
	shares := make(map[string]string)
	for _, entry := range entries {
		shares[entry.Name] = entry.Path
		slog.Info("Taildrive: Share entry", "name", entry.Name, "path", entry.Path)
	}
	server.SetShares(shares)

	// Apply/sync each share with the daemon via LocalAPI.
	// 1. Remove all old shares first by fetching the current ones and deleting them.
	currentJson, err := doLocalRequest("GET", "/localapi/v0/drive/shares", nil)
	if err != nil {
		slog.Warn("Taildrive: Could not fetch current shares from daemon", "error", err)
	} else {
		var currentShares []drive.Share
		if err := json.Unmarshal(currentJson, &currentShares); err == nil {
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
					_, _ = doLocalRequest("DELETE", "/localapi/v0/drive/shares", strings.NewReader(cs.Name))
				}
			}
		} else {
			slog.Warn("Taildrive: Failed to parse current shares response", "error", err, "body", string(currentJson))
		}
	}

	// 2. Put all new/updated shares
	for _, entry := range entries {
		share := drive.Share{
			Name: entry.Name,
			Path: entry.Path,
		}
		shareBytes, err := json.Marshal(share)
		if err != nil {
			slog.Error("Taildrive: Failed to marshal share", "name", entry.Name, "error", err)
			continue
		}
		slog.Info("Taildrive: PUT share to daemon", "name", entry.Name, "payload", string(shareBytes))
		_, err = doLocalRequest("PUT", "/localapi/v0/drive/shares", strings.NewReader(string(shareBytes)))
		if err != nil {
			slog.Error("Taildrive: Failed to register share", "name", entry.Name, "error", err)
			return fmt.Errorf("failed to register share %q: %w", entry.Name, err)
		}
		slog.Info("Taildrive: Share registered OK", "name", entry.Name)
	}

	slog.Info("Taildrive: All shares updated successfully", "total", len(entries))
	return nil
}
