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
	"os"
	"strings"
	"sync"
	"time"
)

// errNotRunning is returned when the daemon process is not active.
var errNotRunning = errors.New("tailscaled is not running")

// socketProbeTTL is how long a liveness probe result stays valid. IsRunning() is
// called on every UI tick, so the probe is cached to avoid a connect() storm.
const socketProbeTTL = 500 * time.Millisecond

var (
	probeMu   sync.Mutex
	probePath string
	probeAt   time.Time
	probeOK   bool
)

// probeSocket reports whether a daemon is actually listening on the Unix socket
// at path. A leftover socket file from a crashed daemon fails this check, which
// os.Stat alone cannot detect.
func probeSocket(path string) bool {
	if path == "" {
		return false
	}
	ok := false
	if _, err := os.Stat(path); err == nil {
		conn, err := net.DialTimeout("unix", path, 500*time.Millisecond)
		if err == nil {
			ok = true
			_ = conn.Close()
		}
	}

	probeMu.Lock()
	probePath = path
	probeAt = time.Now()
	probeOK = ok
	probeMu.Unlock()
	return ok
}

// socketAlive is probeSocket with a short result cache.
func socketAlive(path string) bool {
	if path == "" {
		return false
	}
	probeMu.Lock()
	if probePath == path && time.Since(probeAt) < socketProbeTTL {
		ok := probeOK
		probeMu.Unlock()
		return ok
	}
	probeMu.Unlock()
	return probeSocket(path)
}

// waitForLocalAPI blocks until the daemon socket accepts connections or the
// timeout expires. Every startup path funnels through this so no LocalAPI call
// is issued before the daemon is able to answer it.
func waitForLocalAPI(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		stateMu.Lock()
		sock := PC.Socket()
		stateMu.Unlock()

		if probeSocket(sock) {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// localClientMu guards the cached LocalAPI client below.
var (
	localClientMu   sync.Mutex
	localClientPath string
	localClient     *http.Client
)

// newLocalClient returns an http.Client that dials the daemon's Unix socket.
//
// The client is cached per socket path and reused. Building a fresh
// http.Transport per request leaked a connection every time: after the body is
// closed the connection is parked in that throwaway transport's idle pool, and
// a hand-built transport has no IdleConnTimeout, so neither side ever closed
// it. With the UI polling status every couple of seconds that reached the
// process file descriptor limit — and in Root Mode the descriptors piled up
// inside the long-lived root daemon, which does not restart with the app.
func newLocalClient(socketPath string) *http.Client {
	localClientMu.Lock()
	defer localClientMu.Unlock()

	if localClient != nil && localClientPath == socketPath {
		return localClient
	}
	if localClient != nil {
		localClient.CloseIdleConnections()
	}

	localClientPath = socketPath
	localClient = &http.Client{
		// A wedged daemon must not block the caller forever. Streaming
		// endpoints (the IPN bus) build their own client and are unaffected.
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", socketPath)
			},
			MaxIdleConns:        4,
			MaxIdleConnsPerHost: 4,
			IdleConnTimeout:     60 * time.Second,
		},
	}
	return localClient
}

// closeLocalClient drops the cached client and its idle connections. Called
// when the bridge lets go of a daemon so nothing is left holding its socket.
func closeLocalClient() {
	localClientMu.Lock()
	defer localClientMu.Unlock()
	if localClient != nil {
		localClient.CloseIdleConnections()
		localClient = nil
		localClientPath = ""
	}
}

// doLocalRequest sends a request to the daemon's Unix socket.
//
// It is the single choke point for LocalAPI traffic: when no socket is
// configured, or the socket file is gone, the request is rejected here instead
// of producing a connection error for every caller.
func doLocalRequest(method, path string, body io.Reader) ([]byte, error) {
	stateMu.Lock()
	pc := PC
	stateMu.Unlock()

	if pc.Socket() == "" {
		return nil, ErrSocketEmpty
	}
	if _, err := os.Stat(pc.Socket()); err != nil {
		return nil, ErrDaemonNotRunning
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

	// Preferences are pushed through the masked PATCH /prefs endpoint, never
	// through Start's UpdatePrefs: the daemon replaces the entire prefs object
	// with whatever UpdatePrefs contains, so sending a partial set here silently
	// dropped the hostname, accepted routes, exit node, advertised routes and
	// services. ForceRefresh reaches this on every app resume.
	if opt != nil {
		applyStartupPrefs(opt)
	}

	data, _ := json.Marshal(map[string]interface{}{"AuthKey": authKey})

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
	err := LogoutDaemon()

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
	if err := PatchPrefsJSON(prefsJson); err != nil {
		return "Error: " + err.Error()
	}
	return "OK"
}

// GetLoginURL returns the authentication URL from IPN bus snapshot or daemon status.
func GetLoginURL() string {
	if !IsRunning() {
		return ""
	}
	bs := GetBusState()
	if bs.AuthURL != "" {
		return bs.AuthURL
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
	if s.AuthURL != "" {
		busStateMu.Lock()
		busState.AuthURL = s.AuthURL
		busStateMu.Unlock()
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

	// Embed the ETag into the JSON so Kotlin can extract it for subsequent
	// writes. An emptied serve config marshals to exactly "{}", and the old
	// string splice turned that into `{"etag":"…", }` — a trailing comma that
	// broke the Serve screen's parse after clearing rules.
	s := strings.TrimSpace(string(data))
	if s == "" || s == "null" || s == "{}" {
		s = fmt.Sprintf(`{"etag":%q}`, etag)
	} else if strings.HasPrefix(s, "{") {
		s = fmt.Sprintf(`{"etag":%q,%s`, etag, s[1:])
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/serve-config", "etag", etag)
	return s
}

// fetchCurrentEtag retrieves the current ETag for serve-config from the daemon.
func fetchCurrentEtag(client *http.Client) string {
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
