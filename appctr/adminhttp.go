package appctr

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
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

// adminTransports holds one http.Transport per proxy configuration, so the
// console's requests share connections (and TLS sessions) instead of dialling
// and handshaking from scratch on every call. Keyed by the proxy URL as given;
// it lives in memory only. Bounded: a console switching between a few proxies
// keeps them all, anything beyond that starts the cache over.
var (
	adminTransportsMu sync.Mutex
	adminTransports   = map[string]*http.Transport{}
)

const maxAdminTransports = 8

// adminTransport returns the transport for proxyURL, building it on first use.
// The error is already free of credentials.
func adminTransport(proxyURL string) (*http.Transport, string) {
	adminTransportsMu.Lock()
	defer adminTransportsMu.Unlock()
	if t, ok := adminTransports[proxyURL]; ok {
		return t, ""
	}
	t := &http.Transport{
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: 15 * time.Second,
		IdleConnTimeout:     90 * time.Second,
		MaxIdleConnsPerHost: 4,
		// Every request shares one HTTP/2 connection. Without pings a connection that died
		// quietly (the phone changed networks, the proxy dropped it) stays in the pool and
		// every request on it waits out its whole timeout; a ping unanswered closes it.
		HTTP2: &http.HTTP2Config{
			SendPingTimeout: 15 * time.Second,
			PingTimeout:     5 * time.Second,
		},
	}
	if proxyURL != "" {
		u, err := url.Parse(proxyURL)
		if err != nil {
			// url.Parse quotes the whole URL in its error, user info included.
			return nil, "bad proxy URL"
		}
		switch strings.ToLower(u.Scheme) {
		case "socks", "socks5", "socks5h":
			u.Scheme = "socks5"
			d, err := proxy.FromURL(u, proxy.Direct)
			if err != nil {
				return nil, "SOCKS5 proxy: " + redactProxy(err.Error(), proxyURL)
			}
			if cd, ok := d.(proxy.ContextDialer); ok {
				t.DialContext = cd.DialContext
			} else {
				t.Dial = d.Dial
			}
		case "http", "https":
			t.Proxy = http.ProxyURL(u)
		default:
			return nil, "unsupported proxy scheme " + u.Scheme
		}
	}
	if len(adminTransports) >= maxAdminTransports {
		for k, old := range adminTransports {
			old.CloseIdleConnections()
			delete(adminTransports, k)
		}
	}
	adminTransports[proxyURL] = t
	return t, ""
}

// redactProxy removes the proxy's user name and password from s, raw and
// percent-decoded, should an error message ever carry them.
func redactProxy(s, proxyURL string) string {
	if proxyURL == "" {
		return s
	}
	rest := proxyURL
	if i := strings.Index(rest, "://"); i >= 0 {
		rest = rest[i+3:]
	}
	at := strings.LastIndex(rest, "@")
	if at < 0 {
		return s
	}
	userInfo := rest[:at]
	secrets := []string{userInfo}
	if user, pass, ok := strings.Cut(userInfo, ":"); ok {
		secrets = append(secrets, user, pass)
		if du, err := url.PathUnescape(user); err == nil {
			secrets = append(secrets, du)
		}
		if dp, err := url.PathUnescape(pass); err == nil {
			secrets = append(secrets, dp)
		}
	} else if du, err := url.PathUnescape(userInfo); err == nil {
		secrets = append(secrets, du)
	}
	for _, v := range secrets {
		if len(v) >= 2 {
			s = strings.ReplaceAll(s, v, "***")
		}
	}
	return s
}

// FetchViaProxy performs one HTTP request for the Admin console: method, URL,
// headers as a JSON object, a text body, and the proxy to go through as a URL —
// socks5://user:pass@host:port (socks5h likewise), http://user:pass@host:port,
// or "" for a direct connection. The proxy clients are Go's, golang.org/x/net/proxy
// for SOCKS5 with RFC 1929 authentication and net/http for HTTP proxies: the
// same code tailscaled itself uses for its control connection, so the console
// reaches the API wherever the daemon reaches control. A SOCKS5 target is sent
// by name and resolved by the proxy. One transport per proxy is kept and reused.
// No error text carries the proxy's credentials.
func FetchViaProxy(method, rawURL, headersJSON, body, proxyURL string, timeoutSec int32) string {
	timeout := time.Duration(timeoutSec) * time.Second
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	transport, errText := adminTransport(proxyURL)
	if transport == nil {
		return proxiedHTTPResponse{Error: errText}.json()
	}
	client := &http.Client{Transport: transport, Timeout: timeout}

	var bodyReader io.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, rawURL, bodyReader)
	if err != nil {
		return proxiedHTTPResponse{Error: "bad request: " + redactProxy(err.Error(), proxyURL)}.json()
	}
	if headersJSON != "" {
		var headers map[string]string
		if err := json.Unmarshal([]byte(headersJSON), &headers); err != nil {
			return proxiedHTTPResponse{Error: "bad headers"}.json()
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
	}

	r, err := client.Do(req)
	if err != nil {
		return proxiedHTTPResponse{Error: redactProxy(err.Error(), proxyURL)}.json()
	}
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, 16<<20))
	if err != nil {
		return proxiedHTTPResponse{Status: r.StatusCode, Error: "reading the response: " + redactProxy(err.Error(), proxyURL)}.json()
	}
	out := proxiedHTTPResponse{Status: r.StatusCode, Body: string(b), Headers: map[string]string{}}
	for k := range r.Header {
		out.Headers[k] = r.Header.Get(k)
	}
	return out.json()
}
