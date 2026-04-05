package appctr

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"

	"golang.org/x/net/dns/dnsmessage"
	_ "golang.org/x/mobile/bind"
)

// --- LOGGING SYSTEM ---

type LogManager struct {
	mu      sync.RWMutex
	logs    []string
	maxSize int
}

var logManager = &LogManager{
	logs:    make([]string, 0, 10000),
	maxSize: 10000,
}

func (lm *LogManager) AddLog(entry string) {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	if len(lm.logs) >= lm.maxSize {
		lm.logs = lm.logs[len(lm.logs)/2:]
	}
	lm.logs = append(lm.logs, entry)
}

func (lm *LogManager) GetLogs() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	return strings.Join(lm.logs, "\n")
}

func (lm *LogManager) ClearLogs() {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	lm.logs = make([]string, 0, lm.maxSize)
}

func GetLogs() string { return logManager.GetLogs() }
func ClearLogs()      { logManager.ClearLogs() }

type dualHandler struct {
	textHandler slog.Handler
}

func newDualHandler() *dualHandler {
	return &dualHandler{
		textHandler: slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
			Level: slog.LevelDebug,
		}),
	}
}

func (h *dualHandler) Enabled(ctx context.Context, level slog.Level) bool { return true }

func (h *dualHandler) Handle(ctx context.Context, r slog.Record) error {
	var sb strings.Builder
	sb.WriteString(r.Message)
	r.Attrs(func(a slog.Attr) bool {
		sb.WriteString(" ")
		sb.WriteString(a.Key)
		sb.WriteString("=")
		sb.WriteString(fmt.Sprintf("%v", a.Value.Any()))
		return true
	})

	msg := sb.String()
	var entry string

	if len(msg) > 4 && msg[:2] == "20" && msg[4] == '/' {
		entry = fmt.Sprintf("[%s] %s", r.Level.String(), msg)
	} else {
		timestamp := r.Time.Local().Format("15:04:05")
		entry = fmt.Sprintf("%s [%s] %s", timestamp, r.Level.String(), msg)
	}

	logManager.AddLog(entry)
	return h.textHandler.Handle(ctx, r)
}

func (h *dualHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &dualHandler{textHandler: h.textHandler.WithAttrs(attrs)}
}

func (h *dualHandler) WithGroup(name string) slog.Handler {
	return &dualHandler{textHandler: h.textHandler.WithGroup(name)}
}

func init() {
	slog.SetDefault(slog.New(newDualHandler()))
}

// --- MAIN LOGIC ---

var stateMu sync.Mutex
var cmd *exec.Cmd
var PC pathControl
var currentLogLevel int32 = 1
var dnsProxyCancel context.CancelFunc

type Closer interface {
	Close() error
}

func SetLogLevel(level int32) {
	stateMu.Lock()
	defer stateMu.Unlock()
	currentLogLevel = level
}

func logWithFilter(text string) {
	stateMu.Lock()
	lvl := currentLogLevel
	stateMu.Unlock()

	if lvl >= 1 {
		lower := strings.ToLower(text)
		if strings.Contains(lower, "magicsock") ||
			strings.Contains(lower, "netcheck") ||
			strings.Contains(lower, "ratelimit") ||
			strings.Contains(lower, "udp proxy: received") ||
			strings.Contains(lower, "logtail") {
			return
		}
	}

	slog.Info(text)
}

func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return cmd != nil && cmd.Process != nil
}

type StartOptions struct {
	ExecPath      string
	SocketPath    string
	StatePath     string
	Socks5Server  string
	HttpProxy     string
	CloseCallBack Closer
	AuthKey       string
	ExtraUpArgs   string
	DnsProxy      string // "127.0.0.1:1053" или "0.0.0.0:1053"
	DnsFallbacks  string // "8.8.8.8:53,1.1.1.1:53"
}

func Start(opt *StartOptions) {
	Stop()
	time.Sleep(500 * time.Millisecond)

	if opt.SocketPath != "" {
		_ = os.Remove(opt.SocketPath)
	}

	if opt.Socks5Server == "" {
		opt.Socks5Server = "127.0.0.1:1055"
	}

	if opt.HttpProxy == "" {
		opt.HttpProxy = "127.0.0.1:1057"
	}

	stateMu.Lock()
	PC = newPathControl(opt.ExecPath, opt.SocketPath, opt.StatePath)
	stateMu.Unlock()

	go func() {
		err := tailscaledCmd(PC, opt.Socks5Server, opt.HttpProxy)
		if err != nil {
			slog.Error("tailscaled cmd crashed", "err", err)
		}
		Stop()
		if opt.CloseCallBack != nil {
			opt.CloseCallBack.Close()
		}
	}()

	go registerMachineWithAuthKey(PC, opt)

	// DNS прокси
	if opt.DnsProxy != "" {
		go func() {
			time.Sleep(3 * time.Second)
			slog.Info("Starting DNS proxy", "addr", opt.DnsProxy)

			ctx, cancel := context.WithCancel(context.Background())
			stateMu.Lock()
			dnsProxyCancel = cancel
			socketPath := PC.Socket()
			stateMu.Unlock()

			var fallbacks []string
			if opt.DnsFallbacks != "" {
				for _, f := range strings.Split(opt.DnsFallbacks, ",") {
					f = strings.TrimSpace(f)
					if f != "" {
						fallbacks = append(fallbacks, f)
					}
				}
			}
			if len(fallbacks) == 0 {
				fallbacks = []string{"8.8.8.8:53", "1.1.1.1:53"}
			}
			if err := startDNSProxy(ctx, opt.DnsProxy, socketPath, fallbacks); err != nil {
				slog.Error("DNS proxy stopped", "err", err)
			}
		}()
	}
}

func RunTailscaleCmd(commandStr string) string {
	if !IsRunning() {
		return "Error: Tailscaled service is not running."
	}

	parts := strings.Fields(commandStr)
	if len(parts) == 0 {
		return ""
	}

	args := []string{"--socket", PC.Socket()}
	args = append(args, parts...)

	c := exec.Command(PC.Tailscale(), args...)
	output, err := c.CombinedOutput()

	result := string(output)
	if err != nil {
		result += fmt.Sprintf("\nError: %v", err)
	}
	return result
}

func registerMachineWithAuthKey(PC pathControl, opt *StartOptions) {
	count := 0
	maxRetries := 30

	for count < maxRetries {
		if _, err := os.Stat(PC.Socket()); err != nil {
			count++
			time.Sleep(1 * time.Second)
			continue
		}

		args := []string{"--socket", PC.Socket(), "up", "--reset", "--timeout", "15s"}

		if opt.AuthKey != "" {
			args = append(args, "--auth-key", opt.AuthKey)
		}

		if opt.ExtraUpArgs != "" {
			customFlags := strings.Fields(opt.ExtraUpArgs)
			args = append(args, customFlags...)
		}

		slog.Info("Running tailscale up", "try", count+1)

		c := exec.Command(PC.Tailscale(), args...)
		data, err := c.CombinedOutput()
		output := string(data)

		if err != nil {
			slog.Info("tailscale up failed", "output", output, "err", err)
			if strings.Contains(output, "invalid key") || strings.Contains(output, "API key does not exist") {
				slog.Error("Critical Auth Error: Invalid Auth Key. Please check settings.")
				return
			}
			count++
			time.Sleep(5 * time.Second)
			continue
		}

		slog.Info("tailscale up success", "output", output)
		break
	}
}

func Stop() {
	stateMu.Lock()
	defer stateMu.Unlock()

	if dnsProxyCancel != nil {
		slog.Info("stop dns proxy")
		dnsProxyCancel()
		dnsProxyCancel = nil
	}

	x := cmd
	cmd = nil

	if x != nil && x.Process != nil {
		slog.Info("stop tailscaled cmd")
		_ = x.Process.Signal(syscall.SIGTERM)

		go func(p *os.Process) {
			time.Sleep(2 * time.Second)
			_ = p.Kill()
		}(x.Process)
	}
}

func rm(path ...string) {
	if len(path) == 0 {
		return
	}
	args := []string{"-rf"}
	args = append(args, path...)
	data, err := exec.Command("/system/bin/rm", args...).CombinedOutput()
	slog.Info("rm", "cmd", args, "output", string(data), "err", err)
}

func ln(src, dst string) {
	c := exec.Command("/system/bin/ln", "-s", src, dst)
	data, err := c.CombinedOutput()
	slog.Info("ln", "cmd", c.String(), "output", string(data), "err", err)
}

type pathControl struct {
	execPath   string
	statePath  string
	socketPath string
	execDir    string
	dataDir    string
}

func newPathControl(execPath, socketPath, statePath string) pathControl {
	return pathControl{
		execPath:   execPath,
		statePath:  statePath,
		socketPath: socketPath,
		execDir:    filepath.Dir(execPath),
		dataDir:    filepath.Dir(socketPath),
	}
}

func (p pathControl) TailscaledSo() string { return p.execPath }
func (p pathControl) Tailscaled() string   { return filepath.Join(p.dataDir, "tailscaled") }
func (p pathControl) TailscaleSo() string  { return filepath.Join(p.execDir, "libtailscale.so") }
func (p pathControl) Tailscale() string    { return filepath.Join(p.dataDir, "tailscale") }
func (p pathControl) DataDir(s ...string) string {
	if len(s) == 0 {
		return p.dataDir
	}
	return filepath.Join(append([]string{p.dataDir}, s...)...)
}
func (p pathControl) Socket() string { return p.socketPath }
func (p *pathControl) State() string { return p.statePath }

func tailscaledCmd(p pathControl, socks5host string, httphost string) error {
	rm(p.Tailscale(), p.Tailscaled())
	ln(p.TailscaledSo(), p.Tailscale())
	ln(p.TailscaledSo(), p.Tailscaled())

	c := exec.Command(
		p.Tailscaled(),
		"--tun=userspace-networking",
		"--socks5-server="+socks5host,
		"--outbound-http-proxy-listen="+httphost,
		fmt.Sprintf("--statedir=%s", p.State()),
		fmt.Sprintf("--socket=%s", p.Socket()),
	)
	c.Dir = p.DataDir()

	c.Env = []string{
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
		"TS_NO_LOGS_NO_SUPPORT=true",
	}

	stdOut, err := c.StdoutPipe()
	if err != nil {
		return err
	}

	stdErr, err := c.StderrPipe()
	if err != nil {
		return err
	}

	stateMu.Lock()
	cmd = c
	stateMu.Unlock()

	if err := c.Start(); err != nil {
		return err
	}

	go func() {
		s := bufio.NewScanner(stdOut)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}()

	go func() {
		s := bufio.NewScanner(stdErr)
		for s.Scan() {
			logWithFilter(s.Text())
		}
	}()

	return c.Wait()
}

// --- DNS PROXY ---

func startDNSProxy(ctx context.Context, listenAddr string, socketPath string, fallbacks []string) error {
	pc, err := net.ListenPacket("udp", listenAddr)
	if err != nil {
		return fmt.Errorf("dns proxy listen failed: %w", err)
	}
	defer pc.Close()
	slog.Info("DNS proxy listening", "addr", listenAddr)

	go func() {
		<-ctx.Done()
		slog.Info("DNS proxy context cancelled, shutting down")
		pc.Close()
	}()

	buf := make([]byte, 65535)
	for {
		n, clientAddr, err := pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		query := make([]byte, n)
		copy(query, buf[:n])

		go func(q []byte, cAddr net.Addr) {
			resp, err := forwardDNSviaLocalAPI(q, socketPath)
			if err != nil {
				slog.Info("LocalAPI DNS failed, trying fallback", "err", err)
				resp = tryFallbackDNS(q, fallbacks)
			} else if isNXDOMAIN(resp) {
				slog.Info("LocalAPI DNS returned NXDOMAIN, trying fallback")
				fallbackResp := tryFallbackDNS(q, fallbacks)
				if fallbackResp != nil && !isNXDOMAIN(fallbackResp) {
					resp = fallbackResp
				}
			}

			if resp == nil {
				slog.Info("All DNS resolvers failed for query")
				return
			}

			if len(resp) >= 2 && len(q) >= 2 {
				resp[0] = q[0]
				resp[1] = q[1]
			}

			if _, err := pc.WriteTo(resp, cAddr); err != nil {
				slog.Info("DNS write back error", "err", err)
			}
		}(query, clientAddr)
	}
}

func forwardDNSviaLocalAPI(query []byte, socketPath string) ([]byte, error) {
	var p dnsmessage.Parser
	if _, err := p.Start(query); err != nil {
		return nil, fmt.Errorf("parse dns start: %w", err)
	}
	q, err := p.Question()
	if err != nil {
		return nil, fmt.Errorf("parse dns question: %w", err)
	}

	name := q.Name.String()
	name = strings.TrimSuffix(name, ".")

	qType := q.Type.String()
	qType = strings.TrimPrefix(qType, "Type")

	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return net.DialTimeout("unix", socketPath, 3*time.Second)
		},
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   5 * time.Second,
	}

	url := fmt.Sprintf("http://local-tailscaled.sock/localapi/v0/dns-query?name=%s&type=%s", name, qType)

	resp, err := client.Get(url)
	if err != nil {
		return nil, fmt.Errorf("local api request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read local api response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("local api status %d: %s", resp.StatusCode, string(body))
	}

	var dnsResp struct {
		Bytes []byte `json:"Bytes"`
	}
	if err := json.Unmarshal(body, &dnsResp); err != nil {
		return nil, fmt.Errorf("parse json: %w", err)
	}

	return dnsResp.Bytes, nil
}

func isNXDOMAIN(resp []byte) bool {
	if len(resp) < 4 {
		return false
	}
	return resp[3]&0x0F == 3
}

func tryFallbackDNS(query []byte, fallbacks []string) []byte {
	for _, server := range fallbacks {
		resp, err := forwardDNSviaUDP(query, server)
		if err == nil {
			return resp
		}
		slog.Info("Fallback DNS failed", "server", server, "err", err)
	}
	resp, err := forwardDNSviaDoH(query)
	if err == nil {
		return resp
	}
	slog.Info("DoH fallback also failed", "err", err)
	return nil
}

func forwardDNSviaUDP(query []byte, server string) ([]byte, error) {
	conn, err := net.DialTimeout("udp", server, 5*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := conn.Write(query); err != nil {
		return nil, err
	}

	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func forwardDNSviaDoH(query []byte) ([]byte, error) {
	encoded := base64.RawURLEncoding.EncodeToString(query)

	client := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", "https://dns.google/dns-query?dns="+encoded, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-message")

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("doh status: %d", resp.StatusCode)
	}

	return io.ReadAll(resp.Body)
}