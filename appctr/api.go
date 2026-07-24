package appctr

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/url"
	"strings"
)

// ============================================================================
// TailSocks LocalAPI v0 Master Binding Module (appctr/api.go)
// Strongly-typed, CLI-free JNI bindings for 100% Unix Socket LocalAPI control.
// ============================================================================

// --- 1. Status & Profiles ---

// GetStatusJSON returns the raw JSON output of /localapi/v0/status.
// Set includePeers to true to include full peer list metadata.
func GetStatusJSON(includePeers bool) (string, error) {
	path := "/localapi/v0/status"
	if includePeers {
		path += "?peers=true"
	}
	data, err := doLocalRequest("GET", path, nil)
	if err != nil {
		return "", fmt.Errorf("GetStatus error: %w", err)
	}
	return string(data), nil
}

// GetProfilesJSON returns current profiles list from /localapi/v0/profiles/.
func GetProfilesJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/profiles/", nil)
	if err != nil {
		return "", fmt.Errorf("GetProfiles error: %w", err)
	}
	return string(data), nil
}

// SwitchProfile switches active daemon profile by profile ID.
func SwitchProfile(profileID string) error {
	path := "/localapi/v0/profiles/" + url.PathEscape(profileID)
	_, err := doLocalRequest("POST", path, nil)
	if err != nil {
		return fmt.Errorf("SwitchProfile error: %w", err)
	}
	return nil
}

// --- 2. Preferences & Daemon State ---

// GetPrefsJSON fetches current IPN preferences from /localapi/v0/prefs.
func GetPrefsJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/prefs", nil)
	if err != nil {
		return "", fmt.Errorf("GetPrefs error: %w", err)
	}
	return string(data), nil
}

// PatchPrefsJSON applies preference updates to /localapi/v0/prefs.
func PatchPrefsJSON(jsonPayload string) error {
	if !IsRunning() {
		return errNotRunning
	}
	_, err := doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(jsonPayload))
	if err != nil {
		return fmt.Errorf("PatchPrefs error: %w", err)
	}
	return nil
}

// StartDaemon sends initial /localapi/v0/start request with StartOptions.
func StartDaemon(jsonPayload string) error {
	if !IsRunning() {
		return errNotRunning
	}
	_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(jsonPayload))
	if err != nil {
		return fmt.Errorf("StartDaemon error: %w", err)
	}
	return nil
}

// LoginInteractive triggers interactive login via /localapi/v0/login-interactive.
func LoginInteractive() error {
	if !IsRunning() {
		return errNotRunning
	}
	_, err := doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
	if err != nil {
		return fmt.Errorf("LoginInteractive error: %w", err)
	}
	return nil
}

// LogoutDaemon logs out current account via /localapi/v0/logout.
func LogoutDaemon() error {
	if !IsRunning() {
		return nil
	}
	_, err := doLocalRequest("POST", "/localapi/v0/logout", nil)
	if err != nil {
		return fmt.Errorf("LogoutDaemon error: %w", err)
	}
	return nil
}

// --- 3. Diagnostics & Networking ---

// GetNetcheckJSON returns netcheck diagnostic JSON from /localapi/v0/netcheck.
func GetNetcheckJSON(requestDERP bool) (string, error) {
	path := "/localapi/v0/netcheck"
	if requestDERP {
		path += "?full=true"
	}
	data, err := doLocalRequest("GET", path, nil)
	if err != nil {
		return "", fmt.Errorf("GetNetcheck error: %w", err)
	}
	return string(data), nil
}

// PingTarget pings a target node IP address via /localapi/v0/ping.
func PingTarget(targetIP string, pingType string) (string, error) {
	if pingType == "" {
		pingType = "disco"
	}
	payload := map[string]string{
		"IP":   targetIP,
		"Type": pingType,
	}
	body, _ := json.Marshal(payload)
	data, err := doLocalRequest("POST", "/localapi/v0/ping", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("PingTarget error: %w", err)
	}
	return string(data), nil
}

// WhoIsAddr performs identity lookup for a remote IP address via /localapi/v0/whois.
func WhoIsAddr(addr string) (string, error) {
	path := "/localapi/v0/whois?addr=" + url.QueryEscape(addr)
	data, err := doLocalRequest("GET", path, nil)
	if err != nil {
		return "", fmt.Errorf("WhoIsAddr error: %w", err)
	}
	return string(data), nil
}

// GetDERPMapJSON returns current DERP region map JSON from /localapi/v0/derp/map.
func GetDERPMapJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/derp/map", nil)
	if err != nil {
		return "", fmt.Errorf("GetDERPMap error: %w", err)
	}
	return string(data), nil
}

// --- 4. Taildrive & File Exchange ---

// GetDriveSharesJSON lists current Taildrive shared directories.
func GetDriveSharesJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/drive/shares", nil)
	if err != nil {
		return "", fmt.Errorf("GetDriveShares error: %w", err)
	}
	return string(data), nil
}

// PutDriveShare adds or updates a local directory share in Taildrive.
func PutDriveShare(name, path string) error {
	payload := map[string]string{
		"name": name,
		"path": path,
	}
	body, _ := json.Marshal(payload)
	_, err := doLocalRequest("PUT", "/localapi/v0/drive/shares", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("PutDriveShare error: %w", err)
	}
	return nil
}

// DeleteDriveShare removes a Taildrive share by name.
func DeleteDriveShare(name string) error {
	path := "/localapi/v0/drive/shares/" + url.PathEscape(name)
	_, err := doLocalRequest("DELETE", path, nil)
	if err != nil {
		return fmt.Errorf("DeleteDriveShare error: %w", err)
	}
	return nil
}

// SetFileServerAddr registers local Taildrive file server address.
func SetFileServerAddr(addr string) error {
	payload := map[string]string{
		"address": addr,
	}
	body, _ := json.Marshal(payload)
	_, err := doLocalRequest("PUT", "/localapi/v0/drive/fileserver-address", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("SetFileServerAddr error: %w", err)
	}
	return nil
}

// GetFileTargetsJSON returns nodes capable of receiving files via Taildrop.
func GetFileTargetsJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/file-targets", nil)
	if err != nil {
		return "", fmt.Errorf("GetFileTargets error: %w", err)
	}
	return string(data), nil
}

// GetWaitingFilesJSON lists incoming files queued in Taildrop.
func GetWaitingFilesJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/files/", nil)
	if err != nil {
		return "", fmt.Errorf("GetWaitingFiles error: %w", err)
	}
	return string(data), nil
}

// --- 5. Serve & Funnel ---

// GetServeConfigJSON retrieves active Serve/Funnel rules from /localapi/v0/serve-config.
func GetServeConfigJSON() (string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/serve-config", nil)
	if err != nil {
		return "", fmt.Errorf("GetServeConfig error: %w", err)
	}
	return string(data), nil
}

// SetServeConfigJSON applies new Serve/Funnel rules (following Reset-then-Apply pattern).
func SetServeConfigJSON(configJSON string) error {
	if !IsRunning() {
		return errNotRunning
	}
	// 1. Reset pattern: POST empty object {} to clear stale daemon state
	_, _ = doLocalRequest("POST", "/localapi/v0/serve-config", strings.NewReader("{}"))

	if configJSON == "" || configJSON == "{}" {
		slog.Info("SetServeConfig: reset complete, cleared serve config")
		return nil
	}

	// 2. Apply new configuration
	_, err := doLocalRequest("POST", "/localapi/v0/serve-config", strings.NewReader(configJSON))
	if err != nil {
		return fmt.Errorf("SetServeConfig error: %w", err)
	}
	return nil
}

// ResetServeConfig clears all active Serve and Funnel rules.
func ResetServeConfig() error {
	return SetServeConfigJSON("{}")
}

// --- 6. DNS Configuration ---

// SetDNSJSON pushes DNS configuration updates to /localapi/v0/set-dns.
func SetDNSJSON(dnsJSON string) error {
	var body io.Reader
	if dnsJSON != "" {
		body = strings.NewReader(dnsJSON)
	}
	_, err := doLocalRequest("POST", "/localapi/v0/set-dns", body)
	if err != nil {
		return fmt.Errorf("SetDNS error: %w", err)
	}
	return nil
}
