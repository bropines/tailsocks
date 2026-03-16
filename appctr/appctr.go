package appctr

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"encoding/pem"
	"net/http"
	"fmt"
	"io"
	"log/slog"
	"net"
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

	// Проверяем, начинается ли строка с даты формата Tailscale (например, 2026/02/28)
	// Если да, не лепим наше время. Если нет (наши внутренние логи) — лепим.
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
var currentLogLevel int32 = 1 // 0 = Debug, 1 = Info, 2 = Errors

type Closer interface {
	Close() error
}

// Экспортируем в Android
func SetLogLevel(level int32) {
	stateMu.Lock()
	defer stateMu.Unlock()
	currentLogLevel = level
}

func logWithFilter(text string) {
	stateMu.Lock()
	lvl := currentLogLevel
	stateMu.Unlock()

	// Фильтруем мусор, если уровень Info или выше (не дебаг)
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
    DNSProxy      string
    DNSFallbacks  string
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
    	if opt.DNSProxy != "" {
        go func() {
            time.Sleep(3 * time.Second)
            slog.Info("Starting DNS proxy", "addr", opt.DNSProxy)
            fallbacks := strings.Split(opt.DNSFallbacks, ",")
            if err := startDNSProxy(opt.DNSProxy, opt.HttpProxy, fallbacks); err != nil {
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
	
	// TS_NO_LOGS_NO_SUPPORT=true вырубает сбор телеметрии через logtail
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

var dnsProxyFallbacks = []string{
    "8.8.8.8:53",
    "1.1.1.1:53",
}

// DoH fallback (опционально)
const dohURL = "https://dns.google/dns-query"

func startDNSProxy(listenAddr string, httpProxyAddr string) error {
    pc, err := net.ListenPacket("udp", listenAddr)
    if err != nil {
        return fmt.Errorf("dns proxy listen failed: %w", err)
    }
    defer pc.Close()
    slog.Info("DNS proxy listening", "addr", listenAddr)

    buf := make([]byte, 65535)
    for {
        n, clientAddr, err := pc.ReadFrom(buf)
        if err != nil {
            return nil
        }
        query := make([]byte, n)
        copy(query, buf[:n])

        go func(q []byte, cAddr net.Addr) {
            // Сначала пробуем MagicDNS
            resp, err := forwardDNSviaProxy(q, httpProxyAddr)
            if err != nil || isNXDOMAIN(resp) {
                if err != nil {
                    slog.Info("MagicDNS failed, trying fallback", "err", err)
                } else {
                    slog.Info("MagicDNS returned NXDOMAIN, trying fallback")
                }
                // Пробуем fallback серверы по очереди
                resp = tryFallbackDNS(q)
            }

            if resp == nil {
                slog.Info("All DNS resolvers failed")
                return
            }
            pc.WriteTo(resp, cAddr)
        }(query, clientAddr)
    }
}

// isNXDOMAIN проверяет RCODE в DNS ответе
func isNXDOMAIN(resp []byte) bool {
    if len(resp) < 4 {
        return false
    }
    rcode := resp[3] & 0x0F
    return rcode == 3 // NXDOMAIN
}

func tryFallbackDNS(query []byte) []byte {
    // Сначала пробуем обычные UDP серверы
    for _, server := range dnsProxyFallbacks {
        resp, err := forwardDNSviaUDP(query, server)
        if err == nil {
            return resp
        }
        slog.Info("Fallback DNS failed", "server", server, "err", err)
    }
    // Последний шанс — DoH
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
    import_b64 := base64.RawURLEncoding.EncodeToString(query)
    
    client := &http.Client{Timeout: 10 * time.Second}
    req, err := http.NewRequest("GET", dohURL+"?dns="+import_b64, nil)
    if err != nil {
        return nil, err
    }
    req.Header.Set("Accept", "application/dns-message")

    resp, err := client.Do(req)
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()

    return io.ReadAll(resp.Body)
}

func forwardDNSviaProxy(query []byte, httpProxyAddr string) ([]byte, error) {
    // ... тот же код что был выше, без изменений
}

func forwardDNSviaProxy(query []byte, httpProxyAddr string) ([]byte, error) {
    // Коннектимся к HTTP CONNECT прокси (порт 1057 — outbound http proxy tailscaled)
    conn, err := net.DialTimeout("tcp", httpProxyAddr, 5*time.Second)
    if err != nil {
        return nil, fmt.Errorf("dial http proxy: %w", err)
    }
    defer conn.Close()
    conn.SetDeadline(time.Now().Add(10 * time.Second))

    // Просим прокси пробросить нас до MagicDNS
    _, err = fmt.Fprintf(conn, "CONNECT 100.100.100.100:53 HTTP/1.1\r\nHost: 100.100.100.100:53\r\n\r\n")
    if err != nil {
        return nil, fmt.Errorf("send CONNECT: %w", err)
    }

    // Читаем ответ прокси (ищем "200")
    respBuf := make([]byte, 512)
    n, err := conn.Read(respBuf)
    if err != nil {
        return nil, fmt.Errorf("read proxy response: %w", err)
    }
    if !strings.Contains(string(respBuf[:n]), "200") {
        return nil, fmt.Errorf("proxy tunnel rejected: %s", string(respBuf[:n]))
    }

    // DNS-over-TCP: 2 байта длины + сам запрос
    tcpQuery := make([]byte, 2+len(query))
    binary.BigEndian.PutUint16(tcpQuery[:2], uint16(len(query)))
    copy(tcpQuery[2:], query)

    if _, err := conn.Write(tcpQuery); err != nil {
        return nil, fmt.Errorf("write dns query: %w", err)
    }

    // Читаем ответ: сначала 2 байта длины
    lenBuf := make([]byte, 2)
    if _, err := io.ReadFull(conn, lenBuf); err != nil {
        return nil, fmt.Errorf("read response length: %w", err)
    }
    respLen := binary.BigEndian.Uint16(lenBuf)
    if respLen == 0 {
        return nil, fmt.Errorf("empty dns response")
    }

    resp := make([]byte, respLen)
    if _, err := io.ReadFull(conn, resp); err != nil {
        return nil, fmt.Errorf("read dns response: %w", err)
    }
    return resp, nil
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