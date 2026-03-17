package appctr

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
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
	"unsafe"

	"github.com/creack/pty"
	"github.com/gliderlabs/ssh"
	"github.com/pkg/sftp"
	gossh "golang.org/x/crypto/ssh"
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

func setWinsize(f *os.File, w, h int) {
	_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(syscall.TIOCSWINSZ),
		uintptr(unsafe.Pointer(&struct{ h, w, x, y uint16 }{uint16(h), uint16(w), 0, 0})))
}

var stateMu sync.Mutex
var cmd *exec.Cmd
var sshserver *ssh.Server
var sshListener net.Listener
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
	SSHServer     string
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

	if opt.SSHServer != "" {
		go func() {
			time.Sleep(1 * time.Second)
			keyData, err := ensureHostKey(PC.DataDir())
			if err != nil {
				slog.Error("failed to ensure host key", "err", err)
				return
			}
			if err := startSshServer(opt.SSHServer, PC, keyData); err != nil {
				slog.Error("ssh server failed", "err", err)
			}
		}()
	}

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

func ensureHostKey(dir string) ([]byte, error) {
	keyPath := filepath.Join(dir, "ssh_host_key")
	if data, err := os.ReadFile(keyPath); err == nil {
		return data, nil
	}

	slog.Info("Generating new SSH host key...")
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}

	privateKeyPEM := &pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
	}

	var pemBuf strings.Builder
	if err := pem.Encode(&pemBuf, privateKeyPEM); err != nil {
		return nil, err
	}

	data := []byte(pemBuf.String())
	if err := os.WriteFile(keyPath, data, 0600); err != nil {
		return nil, err
	}

	return data, nil
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

	if sshserver != nil {
		slog.Info("stop ssh server")
		sshserver.Close()
		sshserver = nil
	}

	if sshListener != nil {
		sshListener.Close()
		sshListener = nil
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

// startDNSProxy слушает UDP на listenAddr.
// Резолвит через tailscaled local API (Unix socket).
// При ошибке — fallback на обычные UDP серверы и DoH.
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
			// Сначала пробуем tailscale local API
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
			if _, err := pc.WriteTo(resp, cAddr); err != nil {
				slog.Info("DNS write back error", "err", err)
			}
		}(query, clientAddr)
	}
}

// parseDNSName разбирает DNS имя из wire формата начиная с offset.
// Возвращает имя, новый offset и ошибку.
func parseDNSName(msg []byte, offset int) (string, int, error) {
	var labels []string
	visited := make(map[int]bool)
	origOffset := -1

	for {
		if offset >= len(msg) {
			return "", 0, fmt.Errorf("dns name out of bounds at %d", offset)
		}
		if visited[offset] {
			return "", 0, fmt.Errorf("dns name compression loop at %d", offset)
		}
		visited[offset] = true

		length := int(msg[offset])

		if length == 0 {
			offset++
			break
		}

		// Compression pointer (top 2 bits = 11)
		if length&0xC0 == 0xC0 {
			if offset+1 >= len(msg) {
				return "", 0, fmt.Errorf("dns pointer out of bounds")
			}
			ptr := int(binary.BigEndian.Uint16(msg[offset:offset+2]) & 0x3FFF)
			if origOffset == -1 {
				origOffset = offset + 2
			}
			offset = ptr
			continue
		}

		offset++
		if offset+length > len(msg) {
			return "", 0, fmt.Errorf("dns label out of bounds")
		}
		labels = append(labels, string(msg[offset:offset+length]))
		offset += length
	}

	if origOffset != -1 {
		return strings.Join(labels, "."), origOffset, nil
	}
	return strings.Join(labels, "."), offset, nil
}

// encodeDNSName кодирует имя в DNS wire формат
func encodeDNSName(name string) []byte {
	var buf []byte
	if name == "" || name == "." {
		return []byte{0x00}
	}
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		buf = append(buf, byte(len(label)))
		buf = append(buf, []byte(label)...)
	}
	buf = append(buf, 0x00)
	return buf
}

// buildDNSResponse строит DNS ответ из списка IP адресов
func buildDNSResponse(queryID uint16, qname string, qtype uint16, qclass uint16, ips []net.IP, ttl uint32) []byte {
	var buf []byte

	// Header
	buf = append(buf, byte(queryID>>8), byte(queryID))

	ancount := uint16(0)
	rcode := uint16(3) // NXDOMAIN по умолчанию
	for _, ip := range ips {
		if qtype == 1 && ip.To4() != nil {
			ancount++
			rcode = 0
		} else if qtype == 28 && ip.To4() == nil {
			ancount++
			rcode = 0
		}
	}
	if ancount == 0 && len(ips) > 0 {
		// есть IP но не того типа — NOERROR с 0 ответов
		rcode = 0
	}

	flags := uint16(0x8180) | rcode // QR=1, AA=0, RD=1, RA=1
	buf = append(buf, byte(flags>>8), byte(flags))
	buf = append(buf, 0x00, 0x01)                      // QDCOUNT = 1
	buf = append(buf, byte(ancount>>8), byte(ancount)) // ANCOUNT
	buf = append(buf, 0x00, 0x00)                      // NSCOUNT
	buf = append(buf, 0x00, 0x00)                      // ARCOUNT

	// Question section
	buf = append(buf, encodeDNSName(qname)...)
	buf = append(buf, byte(qtype>>8), byte(qtype))
	buf = append(buf, byte(qclass>>8), byte(qclass))

	// Answer section
	for _, ip := range ips {
		var rddata []byte
		if qtype == 1 {
			ip4 := ip.To4()
			if ip4 == nil {
				continue
			}
			rddata = ip4
		} else if qtype == 28 {
			ip6 := ip.To16()
			if ip.To4() != nil {
				continue // пропускаем IPv4 для AAAA запроса
			}
			rddata = ip6
		} else {
			continue
		}

		// Name — compression pointer на offset 12 (начало question)
		buf = append(buf, 0xC0, 0x0C)
		buf = append(buf, byte(qtype>>8), byte(qtype))
		buf = append(buf, 0x00, 0x01) // CLASS IN
		buf = append(buf, byte(ttl>>24), byte(ttl>>16), byte(ttl>>8), byte(ttl))
		buf = append(buf, 0x00, byte(len(rddata)))
		buf = append(buf, rddata...)
	}

	return buf
}

// forwardDNSviaLocalAPI резолвит DNS через tailscaled local API по Unix сокету
func forwardDNSviaLocalAPI(query []byte, socketPath string) ([]byte, error) {
	if len(query) < 12 {
		return nil, fmt.Errorf("dns query too short: %d bytes", len(query))
	}

	queryID := binary.BigEndian.Uint16(query[0:2])

	// Парсим вопрос
	name, endOffset, err := parseDNSName(query, 12)
	if err != nil {
		return nil, fmt.Errorf("parse question name: %w", err)
	}
	if endOffset+4 > len(query) {
		return nil, fmt.Errorf("query too short for type/class")
	}

	qtype := binary.BigEndian.Uint16(query[endOffset : endOffset+2])
	qclass := binary.BigEndian.Uint16(query[endOffset+2 : endOffset+4])

	qtypeStr := "A"
	switch qtype {
	case 1:
		qtypeStr = "A"
	case 28:
		qtypeStr = "AAAA"
	default:
		// Для остальных типов сразу идём в fallback
		return nil, fmt.Errorf("unsupported qtype %d, use fallback", qtype)
	}

	// HTTP клиент через Unix socket
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return net.DialTimeout("unix", socketPath, 3*time.Second)
		},
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   5 * time.Second,
	}

	// Запрос к local API
	apiURL := fmt.Sprintf(
		"http://local-tailscaled.sock/localapi/v0/dns/query?name=%s.&type=%s",
		name, qtypeStr,
	)
	resp, err := client.Get(apiURL)
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

	// Парсим ответ — tailscale возвращает JSON массив строк с IP
	// Возможные форматы: ["1.2.3.4"] или {"Addrs":["1.2.3.4"]} или {"Status":"...","Answers":[...]}
	var ips []net.IP

	// Пробуем формат массива строк
	var ipList []string
	if err := json.Unmarshal(body, &ipList); err == nil {
		for _, s := range ipList {
			if ip := net.ParseIP(strings.TrimSpace(s)); ip != nil {
				ips = append(ips, ip)
			}
		}
	} else {
		// Пробуем формат объекта с полем Addrs
		var obj struct {
			Addrs   []string `json:"Addrs"`
			Answers []struct {
				Value string `json:"Value"`
				TTL   uint32 `json:"TTL"`
			} `json:"Answers"`
		}
		if err := json.Unmarshal(body, &obj); err == nil {
			for _, s := range obj.Addrs {
				if ip := net.ParseIP(strings.TrimSpace(s)); ip != nil {
					ips = append(ips, ip)
				}
			}
			for _, ans := range obj.Answers {
				if ip := net.ParseIP(strings.TrimSpace(ans.Value)); ip != nil {
					ips = append(ips, ip)
				}
			}
		}
	}

	slog.Info("LocalAPI DNS resolved", "name", name, "ips", fmt.Sprintf("%v", ips))

	return buildDNSResponse(queryID, name, qtype, qclass, ips, 300), nil
}

// isNXDOMAIN проверяет RCODE == 3 в DNS ответе
func isNXDOMAIN(resp []byte) bool {
	if len(resp) < 4 {
		return false
	}
	return resp[3]&0x0F == 3
}

// tryFallbackDNS перебирает fallback серверы, потом DoH
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

// forwardDNSviaUDP отправляет DNS запрос напрямую по UDP
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

// forwardDNSviaDoH отправляет DNS запрос через DNS-over-HTTPS (dns.google)
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

// --- SSH PART ---

func startSshServer(addr string, pc pathControl, keyData []byte) error {
	p, _ := pem.Decode(keyData)
	if p == nil {
		return fmt.Errorf("failed to decode pem")
	}
	key, err := x509.ParsePKCS1PrivateKey(p.Bytes)
	if err != nil {
		return err
	}
	signer, err := gossh.NewSignerFromKey(key)
	if err != nil {
		return err
	}

	ssh_server := ssh.Server{
		Addr:        addr,
		HostSigners: []ssh.Signer{signer},
		SubsystemHandlers: map[string]ssh.SubsystemHandler{
			"sftp": sftpHandler,
		},
		Handler: func(s ssh.Session) {
			ptyHandler(s, pc)
		},
	}

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}

	stateMu.Lock()
	sshserver = &ssh_server
	sshListener = ln
	stateMu.Unlock()

	slog.Info("starting ssh server", "host", addr)
	return ssh_server.Serve(ln)
}

var ptyWelcome = `
Welcome to Tailscaled SSH
	Tailscaled: %s
	Work Dir: %s
	RemoteAddr: %s
`

func ptyHandler(s ssh.Session, pc pathControl) {
	_, _ = fmt.Fprintf(s, ptyWelcome, pc.TailscaledSo(), pc.DataDir(), s.RemoteAddr())
	slog.Info("new pty session", "remote addr", s.RemoteAddr())

	c := exec.Command("/system/bin/sh")
	c.Dir = pc.DataDir()
	ptyReq, winCh, isPty := s.Pty()
	if isPty {
		c.Env = append(c.Env, fmt.Sprintf("TERM=%s", ptyReq.Term))
		f, err := pty.Start(c)
		if err != nil {
			slog.Error("start pty", "err", err)
			return
		}
		go func() {
			for win := range winCh {
				setWinsize(f, win.Width, win.Height)
			}
		}()
		go func() {
			_, _ = io.Copy(f, s)
			f.Close()
		}()
		_, _ = io.Copy(s, f)
		s.Close()
		_ = c.Wait()
		slog.Info("session exit", "remote addr", s.RemoteAddr())
	} else {
		_, _ = io.WriteString(s, "No PTY requested.\n")
		_ = s.Exit(1)
	}
}

func sftpHandler(sess ssh.Session) {
	slog.Info("new sftp session", "remote addr", sess.RemoteAddr())
	debugStream := io.Discard
	serverOptions := []sftp.ServerOption{sftp.WithDebug(debugStream)}
	server, err := sftp.NewServer(sess, serverOptions...)
	if err != nil {
		slog.Error("sftp server init", "err", err)
		return
	}
	if err := server.Serve(); err == io.EOF {
		server.Close()
		slog.Info("sftp client exited session.")
	} else if err != nil {
		slog.Error("sftp server completed", "err", err)
	}
	slog.Info("sftp session exited")
}