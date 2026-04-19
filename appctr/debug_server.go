package appctr

import (
	"bufio"
	"fmt"
	"log/slog"
	"net"
	"strings"
	"sync"
)

var (
	activeConns = make(map[net.Conn]bool)
	connsMu     sync.Mutex
)

// StartDebugServer запускает TCP сервер на указанном адресе (например, "127.0.0.1:4567")
func StartDebugServer(addr string) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		slog.Error("Hedgehog Debug Server failed to start", "err", err)
		return
	}
	slog.Info("Hedgehog Debug Server listening", "addr", addr)

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				continue
			}

			connsMu.Lock()
			activeConns[conn] = true
			connsMu.Unlock()

			go handleDebugConn(conn)
		}
	}()
}

// BroadcastLog рассылает строку лога всем активным TCP-клиентам
func BroadcastLog(msg string) {
	connsMu.Lock()
	defer connsMu.Unlock()
	for conn := range activeConns {
		// Используем горутину для каждой записи, чтобы один медленный клиент не вешал весь стрим
		go func(c net.Conn, m string) {
			fmt.Fprint(c, m)
		}(conn, msg)
	}
}

func handleDebugConn(conn net.Conn) {
	defer func() {
		connsMu.Lock()
		delete(activeConns, conn)
		connsMu.Unlock()
		conn.Close()
	}()

	fmt.Fprintf(conn, "🦔 Welcome to TailSocks Hedgehog Debug Tunnel\n")
	fmt.Fprintf(conn, "--------------------------------------------\n")
	fmt.Fprintf(conn, "Live logs are streaming below. Type commands to execute.\n")
	fmt.Fprintf(conn, "Commands: status, netcheck, ping <ip>, clear (logs)\n")
	fmt.Fprintf(conn, "Type 'exit' to disconnect.\n\n")

	// Сразу отдаем последние 50 строк истории для контекста
	fmt.Fprintf(conn, "--- Recent History ---\n%s\n--- End of History ---\n\n", GetLogs())

	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		lower := strings.ToLower(line)
		if lower == "exit" || lower == "quit" {
			return
		}

		if lower == "clear" {
			ClearLogs()
			fmt.Fprintf(conn, "Logs cleared.\n")
			continue
		}

		slog.Info("Debug Tunnel executing", "cmd", line)
		
		// Выполняем команду через стандартный механизм Tailscale CLI
		output := RunTailscaleCmd(line)
		fmt.Fprintf(conn, "\n[Hedgehog Output]:\n%s\n\n", output)
	}
}
