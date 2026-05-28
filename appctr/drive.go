package appctr

import (
	"encoding/json"
	"fmt"
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
		return driveServer.Addr(), nil
	}

	server, err := driveimpl.NewFileServer()
	if err != nil {
		return "", fmt.Errorf("failed to create drive file server: %w", err)
	}

	driveServer = server

	go func() {
		// Serve will run until Close() is called.
		_ = server.Serve()
	}()

	addr := server.Addr()

	// Register the file server address with the local tailscaled daemon
	_, err = doLocalRequest("PUT", "/localapi/v0/drive/fileserver-address", strings.NewReader(addr))
	if err != nil {
		// Clean up on failure
		_ = server.Close()
		driveServer = nil
		return "", fmt.Errorf("failed to register drive fileserver address: %w", err)
	}

	return addr, nil
}

// StopDriveServer stops the running WebDAV file server.
func StopDriveServer() error {
	driveServerMu.Lock()
	defer driveServerMu.Unlock()

	if driveServer == nil {
		return nil
	}

	err := driveServer.Close()
	driveServer = nil
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
	driveServerMu.Lock()
	server := driveServer
	driveServerMu.Unlock()

	if server == nil {
		return fmt.Errorf("drive server is not running")
	}

	var entries []ShareEntry
	if err := json.Unmarshal([]byte(sharesJson), &entries); err != nil {
		return fmt.Errorf("failed to parse shares JSON: %w", err)
	}

	// Update the file server shares (name -> local path)
	shares := make(map[string]string)
	for _, entry := range entries {
		shares[entry.Name] = entry.Path
	}
	server.SetShares(shares)

	// Apply/sync each share with the daemon via LocalAPI.
	// 1. Remove all old shares first by fetching the current ones and deleting them.
	currentJson, err := doLocalRequest("GET", "/localapi/v0/drive/shares", nil)
	if err == nil {
		var currentShares []drive.Share
		if err := json.Unmarshal(currentJson, &currentShares); err == nil {
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
					_, _ = doLocalRequest("DELETE", "/localapi/v0/drive/shares", strings.NewReader(cs.Name))
				}
			}
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
			continue
		}
		_, err = doLocalRequest("PUT", "/localapi/v0/drive/shares", strings.NewReader(string(shareBytes)))
		if err != nil {
			return fmt.Errorf("failed to register share %q: %w", entry.Name, err)
		}
	}

	return nil
}
