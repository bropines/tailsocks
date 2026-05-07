package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"
)

// doLocalRequest sends a request to the daemon's Unix socket.
func doLocalRequest(method, path string, body io.Reader) ([]byte, error) {
	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	if pc.Socket() == "" {
		return nil, fmt.Errorf("socket path is empty")
	}

	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
	}

	req, err := http.NewRequest(method, "http://local-tailscaled.sock"+path, body)
	if err != nil {
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(data))
	}

	return data, nil
}

// DoLocalAPIRequest executes an arbitrary LocalAPI request (used by the Console screen).
func DoLocalAPIRequest(method, path, body string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	slog.Info("LocalAPI: Manual Request", "method", method, "path", path)
	var b io.Reader
	if body != "" {
		b = strings.NewReader(body)
	}
	data, err := doLocalRequest(method, path, b)
	if err != nil {
		return "Error: " + err.Error()
	}
	return string(data)
}

// Login authenticates via LocalAPI /start.
func Login(authKey string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	slog.Info("LocalAPI: [POST] /localapi/v0/start", "has_key", authKey != "")

	// ipn.Options structure
	opts := map[string]interface{}{
		"AuthKey": authKey,
	}
	data, _ := json.Marshal(opts)

	_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(string(data)))
	if err != nil {
		return "Error: " + err.Error()
	}
	return "OK"
}

// Logout signs out via LocalAPI /logout.
func Logout() string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	slog.Info("LocalAPI: [POST] /localapi/v0/logout")
	_, err := doLocalRequest("POST", "/localapi/v0/logout", nil)
	if err != nil {
		return "Error: " + err.Error()
	}
	return "OK"
}

// SetPrefs updates preferences via LocalAPI PATCH (EditPrefs).
func SetPrefs(prefsJson string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}
	slog.Info("LocalAPI: [PATCH] /localapi/v0/prefs", "payload", prefsJson)
	_, err := doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(prefsJson))
	if err != nil {
		return "Error: " + err.Error()
	}
	return "OK"
}

// GetLoginURL returns the authentication URL from the daemon status.
func GetLoginURL() string {
	if !IsRunning() {
		return ""
	}
	data, err := doLocalRequest("GET", "/localapi/v0/status", nil)
	if err != nil {
		return ""
	}

	type status struct {
		AuthURL string `json:"AuthURL"`
	}
	var s status
	if err := json.Unmarshal(data, &s); err != nil {
		return ""
	}
	return s.AuthURL
}

// GetLoginURLString is an alias for backwards compatibility with the Kotlin layer.
func GetLoginURLString() string {
	return GetLoginURL()
}

// GetServeConfig returns the current Serve/Funnel configuration.
func GetServeConfig() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}

	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", pc.Socket())
			},
		},
	}

	resp, err := client.Get("http://local-tailscaled.sock/localapi/v0/serve-config")
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}

	etag := resp.Header.Get("Etag")

	// Embed ETag into the JSON so Kotlin can extract it for subsequent writes.
	s := string(data)
	if strings.HasPrefix(s, "{") {
		s = fmt.Sprintf(`{"etag":%q, %s`, etag, s[1:])
	} else if s == "" || s == "null" {
		s = fmt.Sprintf(`{"etag":%q}`, etag)
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/serve-config", "etag", etag)
	return s
}

// SetServeConfig updates the Serve/Funnel configuration.
func SetServeConfig(configJson string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}

	state := GetBackendState()
	if state != "Running" {
		return "Error: Backend is not in Running state (current: " + state + "). Wait a few seconds."
	}

	// Extract the ETag from the incoming JSON (embedded by GetServeConfig).
	var tmp map[string]interface{}
	if err := json.Unmarshal([]byte(configJson), &tmp); err != nil {
		return "Error: invalid JSON: " + err.Error()
	}
	etag, _ := tmp["etag"].(string)
	delete(tmp, "etag") // Strip it before sending to the daemon.
	cleanJson, _ := json.Marshal(tmp)

	stateMu.Lock()
	socket := PC.Socket()
	stateMu.Unlock()

	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return net.Dial("unix", socket)
			},
		},
	}

	// STEP 1: Full reset.
	// Required to clear port bindings before changing protocol (HTTP <-> HTTPS).
	slog.Info("LocalAPI: ServeConfig [Step 1/2] Resetting config", "if_match", etag)
	resetReq, _ := http.NewRequest("POST", "http://local-tailscaled.sock/localapi/v0/serve-config", strings.NewReader("{}"))
	if etag != "" {
		resetReq.Header.Set("If-Match", etag)
	}
	
	resetResp, err := client.Do(resetReq)
	var nextEtag = etag
	if err == nil {
		// Capture the new ETag returned after reset for use in Step 2.
		if resetResp.StatusCode == http.StatusOK || resetResp.StatusCode == http.StatusNoContent {
			nextEtag = resetResp.Header.Get("ETag")
			slog.Info("LocalAPI: Reset successful", "new_etag", nextEtag)
		} else {
			data, _ := io.ReadAll(resetResp.Body)
			slog.Warn("LocalAPI: Reset returned non-OK status", "status", resetResp.StatusCode, "body", string(data))
		}
		resetResp.Body.Close()
	} else {
		slog.Error("LocalAPI: Reset request failed", "err", err)
	}

	// Brief pause to let the daemon close old port listeners before applying new config.
	time.Sleep(150 * time.Millisecond)

	// STEP 2: Apply the new config.
	slog.Info("LocalAPI: ServeConfig [Step 2/2] Applying new config", "if_match", nextEtag)
	applyReq, _ := http.NewRequest("POST", "http://local-tailscaled.sock/localapi/v0/serve-config", strings.NewReader(string(cleanJson)))
	if nextEtag != "" {
		applyReq.Header.Set("If-Match", nextEtag)
	}

	applyResp, err := client.Do(applyReq)
	if err != nil {
		return "Error (Apply): " + err.Error()
	}
	defer applyResp.Body.Close()

	if applyResp.StatusCode != http.StatusOK && applyResp.StatusCode != http.StatusNoContent {
		data, _ := io.ReadAll(applyResp.Body)
		slog.Error("LocalAPI: SetServeConfig [Apply] failed", "status", applyResp.StatusCode, "body", string(data))
		return fmt.Sprintf("HTTP %d: %s", applyResp.StatusCode, string(data))
	}

	// STEP 3: Synchronise AdvertiseServices in Prefs.
	// Critical: the official CLI (serve_v2.go) sends PATCH /prefs with AdvertiseServices
	// on every `tailscale serve --service=svc:*` call. Without this the daemon's
	// vipServicesFromPrefsLocked does not include the service in its c2n /vip-services
	// response, so the coordination server never activates the VIP DNS entry.
	var advertiseServices []string
	if svcMap, ok := tmp["Services"].(map[string]interface{}); ok {
		for svcKey := range svcMap {
			// Include only service-scoped entries (format: "svc:name").
			if strings.HasPrefix(svcKey, "svc:") {
				advertiseServices = append(advertiseServices, svcKey)
			}
		}
	}

	// Two-step reset + apply (mirrors the SetServeConfig POST {}→POST config pattern).
	// Step A: Clear all previously advertised services to avoid stale svc: entries.
	slog.Info("LocalAPI: [PATCH] /localapi/v0/prefs (AdvertiseServices reset)")
	doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(`{"AdvertiseServices":[],"AdvertiseServicesSet":true}`))

	if len(advertiseServices) > 0 {
		// Step B: Apply the new service list.
		prefsPayload, _ := json.Marshal(map[string]interface{}{
			"AdvertiseServices":    advertiseServices,
			"AdvertiseServicesSet": true,
		})
		slog.Info("LocalAPI: [PATCH] /localapi/v0/prefs (AdvertiseServices apply)", "services", advertiseServices)
		doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(string(prefsPayload)))
	}

	return "OK"
}
