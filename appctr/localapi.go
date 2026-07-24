package appctr

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"
)

// errNotRunning is returned when the daemon process is not active.
var errNotRunning = errors.New("tailscaled is not running")

// newLocalClient returns an http.Client that dials the daemon's Unix socket.
func newLocalClient(socketPath string) http.Client {
	return http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", socketPath)
			},
		},
	}
}

// doLocalRequest sends a request to the daemon's Unix socket.
func doLocalRequest(method, path string, body io.Reader) ([]byte, error) {
	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	if pc.Socket() == "" {
		return nil, fmt.Errorf("socket path is empty")
	}

	client := newLocalClient(pc.Socket())

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

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(data))
	}

	return data, nil
}

// DoLocalAPIRequest executes an arbitrary LocalAPI request (used by the Console screen).
func DoLocalAPIRequest(method, path, body string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
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
		return "Error: " + errNotRunning.Error()
	}
	slog.Info("LocalAPI: [POST] /localapi/v0/start", "has_key", authKey != "")

	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	opts := map[string]interface{}{
		"AuthKey": authKey,
	}

	if opt != nil && opt.LoginServer != "" {
		opts["UpdatePrefs"] = map[string]interface{}{
			"ControlURL":  opt.LoginServer,
			"WantRunning": true,
		}
	} else {
		opts["UpdatePrefs"] = map[string]interface{}{
			"WantRunning": true,
		}
	}

	data, _ := json.Marshal(opts)

	_, err := doLocalRequest("POST", "/localapi/v0/start", strings.NewReader(string(data)))
	if err != nil {
		return "Error: " + err.Error()
	}

	if authKey == "" {
		slog.Info("LocalAPI: triggering /localapi/v0/login-interactive")
		time.Sleep(300 * time.Millisecond)
		_, err := doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
		if err != nil {
			return "Error: " + err.Error()
		}

		for i := 0; i < 15; i++ {
			time.Sleep(300 * time.Millisecond)
			sData, err := doLocalRequest("GET", "/localapi/v0/status", nil)
			if err == nil {
				var st struct {
					AuthURL string `json:"AuthURL"`
				}
				if json.Unmarshal(sData, &st) == nil && st.AuthURL != "" {
					slog.Info("LocalAPI: AuthURL ready", "url", st.AuthURL)
					break
				}
			}
			if i == 5 {
				slog.Info("LocalAPI: re-triggering login-interactive")
				_, _ = doLocalRequest("POST", "/localapi/v0/login-interactive", nil)
			}
		}
	}
	return "OK"
}

// Logout signs out via LocalAPI /logout.
func Logout() string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
	}
	slog.Info("LocalAPI: [POST] /localapi/v0/logout")
	_, err := doLocalRequest("POST", "/localapi/v0/logout", nil)

	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	if pc.State() != "" {
		slog.Info("Wiping state directory after logout", "dir", pc.State())
		_ = os.Remove(pc.State() + "/tailscaled.state")
		_ = os.RemoveAll(pc.State() + "/profiles")
	}

	if err != nil {
		return "Error: " + err.Error()
	}
	return "OK"
}

// SetPrefs updates preferences via LocalAPI PATCH (EditPrefs).
func SetPrefs(prefsJson string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
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



// GetServeConfig returns the current Serve/Funnel configuration.
func GetServeConfig() string {
	if !IsRunning() {
		return `{"Error": "` + errNotRunning.Error() + `"}`
	}

	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	client := newLocalClient(pc.Socket())

	resp, err := client.Get("http://local-tailscaled.sock/localapi/v0/serve-config")
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Sprintf(`{"Error": %q}`, err.Error())
	}

	etag := resp.Header.Get("ETag")

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

// fetchCurrentEtag retrieves the current ETag for serve-config from the daemon.
func fetchCurrentEtag(client http.Client) string {
	resp, err := client.Get("http://local-tailscaled.sock/localapi/v0/serve-config")
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	return resp.Header.Get("ETag")
}

// SetServeConfig updates the Serve/Funnel configuration.
func SetServeConfig(configJson string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
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
	delete(tmp, "etag")

	// DON'T delete Services! It's a valid part of the daemon's ServeConfig in this version.
	cleanJson, _ := json.Marshal(tmp)

	stateMu.Lock()
	socket := PC.Socket()
	stateMu.Unlock()

	client := newLocalClient(socket)

	// STEP 1: Synchronise AdvertiseServices in Prefs.
	// We do this BEFORE updating ServeConfig so the daemon already knows about the services
	// when it receives rules for them. This avoids potential discards of service-scoped rules.
	var advertiseServices []string
	if svcMap, ok := tmp["Services"].(map[string]interface{}); ok {
		for svcKey := range svcMap {
			if strings.HasPrefix(svcKey, "svc:") {
				advertiseServices = append(advertiseServices, svcKey)
			}
		}
	}
	prefsPayload, _ := json.Marshal(map[string]interface{}{
		"AdvertiseServices":    advertiseServices,
		"AdvertiseServicesSet": true,
	})
	slog.Info("LocalAPI: [PATCH] /localapi/v0/prefs (Sync AdvertiseServices)", "services", advertiseServices)
	doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(string(prefsPayload)))

	// STEP 2: Full reset of ServeConfig.
	slog.Info("LocalAPI: ServeConfig [Step 1/2] Resetting config", "if_match", etag)
	// We use a nil config by sending an empty object.
	// For some daemon versions, we might need to be even more explicit if it doesn't clear AllowFunnel.
	resetReq, _ := http.NewRequest("POST", "http://local-tailscaled.sock/localapi/v0/serve-config", strings.NewReader("{}"))
	if etag != "" {
		resetReq.Header.Set("If-Match", etag)
	}
	
	resetResp, err := client.Do(resetReq)
	var nextEtag = etag
	if err == nil {
		data, _ := io.ReadAll(resetResp.Body)
		if resetResp.StatusCode == http.StatusOK || resetResp.StatusCode == http.StatusNoContent {
			nextEtag = resetResp.Header.Get("ETag")
			slog.Info("LocalAPI: Reset successful", "status", resetResp.StatusCode, "new_etag", nextEtag)
		} else {
			slog.Warn("LocalAPI: Reset returned non-OK status", "status", resetResp.StatusCode, "body", string(data))
		}
		resetResp.Body.Close()
	} else {
		slog.Error("LocalAPI: Reset request failed", "err", err)
	}

	// If the config is literally "{}", we might want to stop here to ensure everything is purged.
	if string(cleanJson) == "{}" {
		slog.Info("LocalAPI: Full purge requested, stopping after reset")
		// Force one more attempt if reset didn't seem to return OK? 
		// Actually, we'll trust Step 2 for now, but let's ensure nextEtag is updated.
		return "OK"
	}

	if nextEtag == "" {
		nextEtag = fetchCurrentEtag(client)
		slog.Info("LocalAPI: Refetched ETag after reset", "etag", nextEtag)
	}

	time.Sleep(200 * time.Millisecond)

	// STEP 3: Apply the new config.
	slog.Info("LocalAPI: ServeConfig [Step 2/2] Applying new config", "if_match", nextEtag, "payload", string(cleanJson))
	
	applyReq, _ := http.NewRequest("POST", "http://local-tailscaled.sock/localapi/v0/serve-config", strings.NewReader(string(cleanJson)))
	if nextEtag != "" {
		applyReq.Header.Set("If-Match", nextEtag)
	}

	applyResp, err := client.Do(applyReq)
	if err != nil {
		return "Error (Apply): " + err.Error()
	}
	defer applyResp.Body.Close()

	applyData, _ := io.ReadAll(applyResp.Body)
	if applyResp.StatusCode != http.StatusOK && applyResp.StatusCode != http.StatusNoContent {
		slog.Error("LocalAPI: SetServeConfig [Apply] failed", "status", applyResp.StatusCode, "body", string(applyData))
		return fmt.Sprintf("HTTP %d: %s", applyResp.StatusCode, string(applyData))
	}

	finalEtag := applyResp.Header.Get("ETag")
	slog.Info("LocalAPI: Apply successful", "status", applyResp.StatusCode, "final_etag", finalEtag)

	return "OK"
}

// GetCertificatePair returns the PEM-encoded certificate and private key pair for a domain.
func GetCertificatePair(domain string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
	}
	slog.Info("LocalAPI: [GET] /localapi/v0/cert/" + domain + "?type=pair")
	data, err := doLocalRequest("GET", "/localapi/v0/cert/"+domain+"?type=pair", nil)
	if err != nil {
		return "Error: " + err.Error()
	}
	return string(data)
}
