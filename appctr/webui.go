package appctr

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/client/web"
)

// webUIState is everything StartWebUI creates, kept together so StopWebUI can
// release all of it: the listener, the web.Server and the LocalAPI transport
// its client uses.
type webUIState struct {
	srv *http.Server
	ws  *web.Server
	tr  *http.Transport
}

func StartWebUI(addr string) {
	stateMu.Lock()
	if webUI != nil {
		if webUI.srv.Addr == addr {
			stateMu.Unlock()
			return
		}
		// Address changed, need to restart
		stateMu.Unlock()
		StopWebUI()
		stateMu.Lock()
	}
	pc := PC
	stateMu.Unlock()

	slog.Info("Web UI start requested", "addr", addr)
	// Own the LocalAPI transport instead of letting local.Client build one
	// lazily: its default has no IdleConnTimeout and is unreachable from
	// StopWebUI, so every WebUI restart (EnableWebUI toggled, address changed)
	// left connections to the daemon socket idle forever.
	sock := pc.Socket()
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", sock)
		},
		MaxIdleConns:        2,
		MaxIdleConnsPerHost: 2,
		IdleConnTimeout:     60 * time.Second,
	}
	lc := &local.Client{
		Socket:        sock,
		UseSocketOnly: true,
		Transport:     tr,
	}
	ws, err := web.NewServer(web.ServerOpts{
		Mode:        web.LoginServerMode,
		LocalClient: lc,
		Logf: func(format string, args ...any) {
			slog.Info("web", "msg", fmt.Sprintf(format, args...))
		},
	})
	if err != nil {
		tr.CloseIdleConnections()
		slog.Error("Failed to create web server", "err", err)
		return
	}
	srv := &http.Server{
		Addr:    addr,
		Handler: ws,
	}
	st := &webUIState{srv: srv, ws: ws, tr: tr}
	stateMu.Lock()
	webUI = st
	stateMu.Unlock()
	go func() {
		slog.Info("Web UI listening", "addr", addr)
		// Use the captured local, not the global: StopWebUI can clear the global
		// concurrently, which would nil-deref here and crash the whole process.
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Web UI listen error", "err", err)
		}
		stateMu.Lock()
		mine := webUI == st
		if mine {
			webUI = nil
		}
		stateMu.Unlock()
		if mine {
			// Listen failed without a StopWebUI to clean up after us.
			st.tr.CloseIdleConnections()
		}
	}()
}

func StopWebUI() {
	stateMu.Lock()
	st := webUI
	webUI = nil
	stateMu.Unlock()
	if st != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := st.srv.Shutdown(ctx); err != nil {
			// A handler parked in the web server's IPN-bus long-poll outlives
			// the graceful window; force it, or its streaming LocalAPI
			// connection survives the stop.
			_ = st.srv.Close()
		}
		st.ws.Shutdown()
		st.tr.CloseIdleConnections()
		slog.Info("Web UI stopped")
	}
}
