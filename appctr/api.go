// Package appctr provides native JNI bridge bindings and a high-performance,
// CLI-less client for controlling the embedded Tailscale daemon via Unix domain socket.
package appctr

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/url"
	"strings"
)

// ============================================================================
// TailSocks Production-Grade LocalAPI v0 Client & Bindings (appctr/api.go)
// Adheres strictly to Effective Go, CLI-free management, and Reset-then-Apply.
// ============================================================================

var (
	// ErrDaemonNotRunning is returned when localapi requests are issued while tailscaled is inactive.
	ErrDaemonNotRunning = errors.New("tailscaled daemon is not running")

	// ErrSocketEmpty is returned when the Unix domain socket path has not been initialized.
	ErrSocketEmpty = errors.New("daemon socket path is empty")

	// ErrBadRequest is returned when an invalid or empty parameter is supplied.
	ErrBadRequest = errors.New("invalid or empty parameter supplied")
)

// LocalClient represents a strongly-typed client for Tailscale LocalAPI v0.
type LocalClient struct {
	// socketPath is the absolute filesystem path to tailscaled.sock
	socketPath string
}

// NewLocalClient initializes a new LocalClient bound to the specified Unix socket path.
func NewLocalClient(socketPath string) *LocalClient {
	return &LocalClient{socketPath: socketPath}
}

// execute performs an HTTP request over the client's Unix socket connection.
func (c *LocalClient) execute(method, path string, body io.Reader) ([]byte, error) {
	if c.socketPath == "" {
		stateMu.Lock()
		pc := PC
		stateMu.Unlock()
		c.socketPath = pc.Socket()
	}

	if c.socketPath == "" {
		return nil, ErrSocketEmpty
	}

	return doLocalRequest(method, path, body)
}

// --- 1. Status & Profiles ---

// GetStatusJSON retrieves the current node status from /localapi/v0/status.
// When includePeers is true, full peer metadata is included in the response.
func GetStatusJSON(includePeers bool) (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	path := "/localapi/v0/status"
	if includePeers {
		path += "?peers=true"
	}
	data, err := doLocalRequest("GET", path, nil)
	if err != nil {
		return "", fmt.Errorf("GetStatusJSON failed: %w", err)
	}
	return string(data), nil
}

// GetProfilesJSON fetches all registered account profiles from /localapi/v0/profiles/.
func GetProfilesJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/profiles/", nil)
	if err != nil {
		return "", fmt.Errorf("GetProfilesJSON failed: %w", err)
	}
	return string(data), nil
}

// SwitchProfile switches the active daemon profile by profile ID.
func SwitchProfile(profileID string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	if strings.TrimSpace(profileID) == "" {
		return ErrBadRequest
	}
	path := "/localapi/v0/profiles/" + url.PathEscape(profileID)
	_, err := doLocalRequest("POST", path, nil)
	if err != nil {
		return fmt.Errorf("SwitchProfile failed: %w", err)
	}
	return nil
}

// --- 2. Preferences & Daemon State ---

// GetPrefsJSON fetches the current daemon preferences from /localapi/v0/prefs.
func GetPrefsJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/prefs", nil)
	if err != nil {
		return "", fmt.Errorf("GetPrefsJSON failed: %w", err)
	}
	return string(data), nil
}

// PatchPrefsJSON applies incremental preference updates to /localapi/v0/prefs.
func PatchPrefsJSON(jsonPayload string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	if GetLoginURL() != "" {
		slog.Info("syncSettings: AuthURL is active, skipping PATCH /prefs to preserve login flow")
		return nil
	}
	_, err := doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(jsonPayload))
	if err != nil {
		return fmt.Errorf("PatchPrefsJSON failed: %w", err)
	}
	return nil
}

// StartDaemon sends initial engine configuration via /localapi/v0/start.
func StartDaemon(jsonPayload string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(jsonPayload))
	if err != nil {
		return fmt.Errorf("StartDaemon failed: %w", err)
	}
	return nil
}

// LoginInteractive triggers an interactive web login flow via /localapi/v0/login-interactive.
func LoginInteractive() error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	_, err := doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
	if err != nil {
		return fmt.Errorf("LoginInteractive failed: %w", err)
	}
	return nil
}

// LogoutDaemon logs out the active profile session via /localapi/v0/logout.
func LogoutDaemon() error {
	if !IsRunning() {
		return nil
	}
	_, err := doLocalRequest("POST", "/localapi/v0/logout", nil)
	if err != nil {
		return fmt.Errorf("LogoutDaemon failed: %w", err)
	}
	return nil
}

// --- 3. Network Diagnostics & Topologies ---

// GetNetcheckJSON initiates or retrieves network diagnostic results from /localapi/v0/netcheck.
func GetNetcheckJSON(requestDERP bool) (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	// There is no /localapi/v0/netcheck endpoint; the report is produced
	// in-process (netcheck.go), which also works around the daemon's inability
	// to enumerate interfaces on Android.
	return GetNetcheckFromAPI(), nil
}

// PingTarget sends a DERP or disco ping to a remote peer target via /localapi/v0/ping.
func PingTarget(targetIP string, pingType string) (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	if strings.TrimSpace(targetIP) == "" {
		return "", ErrBadRequest
	}
	if pingType == "" {
		pingType = "disco"
	}
	v := url.Values{}
	v.Set("ip", strings.TrimSpace(targetIP))
	v.Set("type", pingType)
	data, err := doLocalRequest("POST", "/localapi/v0/ping?"+v.Encode(), nil)
	if err != nil {
		return "", fmt.Errorf("PingTarget failed: %w", err)
	}
	return string(data), nil
}

// WhoIsAddr performs identity lookup for a remote IP address via /localapi/v0/whois.
func WhoIsAddr(addr string) (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	if strings.TrimSpace(addr) == "" {
		return "", ErrBadRequest
	}
	path := "/localapi/v0/whois?addr=" + url.QueryEscape(addr)
	data, err := doLocalRequest("GET", path, nil)
	if err != nil {
		return "", fmt.Errorf("WhoIsAddr failed: %w", err)
	}
	return string(data), nil
}

// GetDERPMapJSON retrieves the current DERP relay region map from /localapi/v0/derp/map.
func GetDERPMapJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	// The route is "derpmap", not "derp/map" — the old path always 404'd.
	data, err := doLocalRequest("GET", "/localapi/v0/derpmap", nil)
	if err != nil {
		return "", fmt.Errorf("GetDERPMapJSON failed: %w", err)
	}
	return string(data), nil
}

// --- 4. Taildrive & File Transfers ---

// GetDriveSharesJSON lists all configured Taildrive shared directories.
func GetDriveSharesJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/drive/shares", nil)
	if err != nil {
		return "", fmt.Errorf("GetDriveSharesJSON failed: %w", err)
	}
	return string(data), nil
}

// PutDriveShare adds or updates a local directory share in Taildrive.
func PutDriveShare(name, path string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	if strings.TrimSpace(name) == "" || strings.TrimSpace(path) == "" {
		return ErrBadRequest
	}
	payload := map[string]string{
		"name": name,
		"path": path,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("PutDriveShare payload error: %w", err)
	}
	_, err = doLocalRequest("PUT", "/localapi/v0/drive/shares", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("PutDriveShare failed: %w", err)
	}
	return nil
}

// DeleteDriveShare removes a Taildrive share by its share name.
func DeleteDriveShare(name string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	if strings.TrimSpace(name) == "" {
		return ErrBadRequest
	}
	path := "/localapi/v0/drive/shares/" + url.PathEscape(name)
	_, err := doLocalRequest("DELETE", path, nil)
	if err != nil {
		return fmt.Errorf("DeleteDriveShare failed: %w", err)
	}
	return nil
}

// SetFileServerAddr registers the local Taildrive Web interface server address.
func SetFileServerAddr(addr string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	_, err := doLocalRequest("PUT", "/localapi/v0/drive/fileserver-address", strings.NewReader(addr))
	if err != nil {
		return fmt.Errorf("SetFileServerAddr failed: %w", err)
	}
	return nil
}

// GetFileTargetsJSON fetches remote tailnet nodes capable of receiving files via Taildrop.
func GetFileTargetsJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/file-targets", nil)
	if err != nil {
		return "", fmt.Errorf("GetFileTargetsJSON failed: %w", err)
	}
	return string(data), nil
}

// GetWaitingFilesJSON retrieves incoming Taildrop files waiting to be received.
func GetWaitingFilesJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/files/", nil)
	if err != nil {
		return "", fmt.Errorf("GetWaitingFilesJSON failed: %w", err)
	}
	return string(data), nil
}

// --- 5. Serve & Funnel Configuration ---

// GetServeConfigJSON retrieves active Serve/Funnel virtual service rules from /localapi/v0/serve-config.
func GetServeConfigJSON() (string, error) {
	if !IsRunning() {
		return "", ErrDaemonNotRunning
	}
	data, err := doLocalRequest("GET", "/localapi/v0/serve-config", nil)
	if err != nil {
		return "", fmt.Errorf("GetServeConfigJSON failed: %w", err)
	}
	return string(data), nil
}

// SetServeConfigJSON applies new Serve/Funnel rules following the mandatory Reset-then-Apply pattern.
func SetServeConfigJSON(configJSON string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	// 1. Mandatory Reset pattern: POST empty object {} to clear stale daemon state
	_, _ = doLocalRequest("POST", "/localapi/v0/serve-config", strings.NewReader("{}"))

	if configJSON == "" || configJSON == "{}" {
		slog.Info("SetServeConfigJSON: reset complete, cleared serve config")
		return nil
	}

	// 2. Apply new configuration
	_, err := doLocalRequest("POST", "/localapi/v0/serve-config", strings.NewReader(configJSON))
	if err != nil {
		return fmt.Errorf("SetServeConfigJSON failed: %w", err)
	}
	return nil
}

// ResetServeConfig clears all active Serve and Funnel rules.
func ResetServeConfig() error {
	return SetServeConfigJSON("{}")
}

// --- 6. DNS Configuration ---

// SetDNSJSON pushes custom DNS configuration updates to /localapi/v0/set-dns.
func SetDNSJSON(dnsJSON string) error {
	if !IsRunning() {
		return ErrDaemonNotRunning
	}
	var body io.Reader
	if dnsJSON != "" {
		body = strings.NewReader(dnsJSON)
	}
	_, err := doLocalRequest("POST", "/localapi/v0/set-dns", body)
	if err != nil {
		return fmt.Errorf("SetDNSJSON failed: %w", err)
	}
	return nil
}
