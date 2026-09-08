package appctr

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/proxy"
)

// proxiedHTTPResponse is what FetchViaProxy hands back to Kotlin.
type proxiedHTTPResponse struct {
	Status  int               `json:"status"`
	Body    string            `json:"body"`
	Headers map[string]string `json:"headers,omitempty"`
	Error   string            `json:"error,omitempty"`
}

func (r proxiedHTTPResponse) json() string {
	b, err := json.Marshal(r)
	if err != nil {
		return `{"error":"cannot encode response"}`
	}
	return string(b)
}

// FetchViaProxy performs one HTTP request for the Admin console: method, URL,
// headers as a JSON object, a text body, and the proxy to go through as a URL —
// socks5://user:pass@host:port (socks5h likewise), http://user:pass@host:port,
// or "" for a direct connection. The proxy clients are Go's, golang.org/x/net/proxy
// for SOCKS5 with RFC 1929 authentication and net/http for HTTP proxies: the
// same code tailscaled itself uses for its control connection, so the console
// reaches the API wherever the daemon reaches control. A SOCKS5 target is sent
// by name and resolved by the proxy. Kotlin used to do this with OkHttp and
// the JDK's SOCKS client, whose password hand-off broke silently.
func FetchViaProxy(method, rawURL, headersJSON, body, proxyURL string, timeoutSec int32) string {
	timeout := time.Duration(timeoutSec) * time.Second
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	transport := &http.Transport{
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: timeout,
	}
	if proxyURL != "" {
		u, err := url.Parse(proxyURL)
		if err != nil {
			return proxiedHTTPResponse{Error: "bad proxy URL: " + err.Error()}.json()
		}
		switch strings.ToLower(u.Scheme) {
		case "socks", "socks5", "socks5h":
			u.Scheme = "socks5"
			d, err := proxy.FromURL(u, proxy.Direct)
			if err != nil {
				return proxiedHTTPResponse{Error: "SOCKS5 proxy: " + err.Error()}.json()
			}
			if cd, ok := d.(proxy.ContextDialer); ok {
				transport.DialContext = cd.DialContext
			} else {
				transport.Dial = d.Dial
			}
		case "http", "https":
			transport.Proxy = http.ProxyURL(u)
		default:
			return proxiedHTTPResponse{Error: "unsupported proxy scheme " + u.Scheme}.json()
		}
	}
	client := &http.Client{Transport: transport, Timeout: timeout}

	var bodyReader io.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, rawURL, bodyReader)
	if err != nil {
		return proxiedHTTPResponse{Error: "bad request: " + err.Error()}.json()
	}
	if headersJSON != "" {
		var headers map[string]string
		if err := json.Unmarshal([]byte(headersJSON), &headers); err != nil {
			return proxiedHTTPResponse{Error: "bad headers: " + err.Error()}.json()
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
	}

	r, err := client.Do(req)
	if err != nil {
		return proxiedHTTPResponse{Error: err.Error()}.json()
	}
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, 16<<20))
	if err != nil {
		return proxiedHTTPResponse{Status: r.StatusCode, Error: "reading the response: " + err.Error()}.json()
	}
	out := proxiedHTTPResponse{Status: r.StatusCode, Body: string(b), Headers: map[string]string{}}
	for k := range r.Header {
		out.Headers[k] = r.Header.Get(k)
	}
	return out.json()
}
