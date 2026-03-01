package appctr

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
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
	timestamp := r.Time.Local().Format("15:04:05")
	
	var sb strings.Builder
	sb.WriteString(r.Message)
	r.Attrs(func(a slog.Attr) bool {
		sb.WriteString(" ")
		sb.WriteString(a.Key)
		sb.WriteString("=")
		sb.WriteString(fmt.Sprintf("%v", a.Value.Any()))
		return true
	})

	entry := fmt.Sprintf("%s [%s] %s", timestamp, r.Level.String(), sb.String())
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

func SetLogLevel(level int32) {
	stateMu.Lock()
	defer stateMu.Unlock()
	currentLogLevel = level
}

func cleanTailscaleLog(text string) string {
	if len(text) > 20 && text[4] == '/' && text[7] == '/' && text[13] == ':' {
		return text[20:]
	}
	return text
}

func logWithFilter(text string) {
	text = cleanTailscaleLog(text)
	
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
	SSHServer    string
	ExecPath     string
	SocketPath   string
	StatePath    string
	Socks5Server string
	HttpProxy    string
	CloseCallBack Closer
	AuthKey      string
	ExtraUpArgs  string
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
	c.Env = []string{
		fmt.Sprintf("TS_LOGS_DIR=%s/logs", p.DataDir()),
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