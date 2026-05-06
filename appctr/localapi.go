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
)

// doLocalRequest выполняет запрос к Unix-сокету демона.
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

// DoLocalAPIRequest выполняет произвольный запрос к LocalAPI (для Консоли).
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

// Login выполняет авторизацию через LocalAPI /start.
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

// Logout выполняет выход через LocalAPI /logout.
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

// SetPrefs обновляет настройки через LocalAPI PATCH (EditPrefs).
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

// GetLoginURL возвращает URL для авторизации из статуса демона.
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

// GetLoginURLString - алиас для совместимости с Kotlin-слоем.
func GetLoginURLString() string {
	return GetLoginURL()
}

// GetServeConfig возвращает текущую конфигурацию Serve/Funnel.
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

	// Вшиваем ETag в JSON для Kotlin, если это объект
	s := string(data)
	if strings.HasPrefix(s, "{") {
		s = fmt.Sprintf(`{"etag":%q, %s`, etag, s[1:])
	} else if s == "" || s == "null" {
		s = fmt.Sprintf(`{"etag":%q}`, etag)
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/serve-config", "etag", etag)
	return s
}

// SetServeConfig обновляет конфигурацию Serve/Funnel.
func SetServeConfig(configJson string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}

	state := GetBackendState()
	slog.Info("LocalAPI: SetServeConfig attempt", "backend_state", state)

	if state != "Running" {
		return "Error: Backend is not in Running state (current: " + state + "). Wait a few seconds."
	}

	// Извлекаем etag из присланного JSON
	var tmp map[string]interface{}
	if err := json.Unmarshal([]byte(configJson), &tmp); err != nil {
		return "Error: invalid JSON: " + err.Error()
	}
	etag, _ := tmp["etag"].(string)
	delete(tmp, "etag") // Удаляем, чтобы не слать демону

	cleanJson, _ := json.Marshal(tmp)

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

	req, err := http.NewRequest("POST", "http://local-tailscaled.sock/localapi/v0/serve-config", strings.NewReader(string(cleanJson)))
	if err != nil {
		return "Error: " + err.Error()
	}

	if etag != "" {
		req.Header.Set("If-Match", etag)
	}

	slog.Info("LocalAPI: [POST] /localapi/v0/serve-config", "etag", etag, "payload", string(cleanJson))

	resp, err := client.Do(req)
	if err != nil {
		return "Error: " + err.Error()
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		data, _ := io.ReadAll(resp.Body)
		slog.Error("LocalAPI: SetServeConfig failed", "status", resp.StatusCode, "body", string(data))
		return fmt.Sprintf("HTTP %d: %s", resp.StatusCode, string(data))
	}

	return "OK"
}
