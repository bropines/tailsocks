package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
)

// Структура для лога, которая полетит в Kotlin
type LogEntry struct {
	Timestamp string `json:"timestamp"`
	Level     string `json:"level"`
	Category  string `json:"category"`
	Message   string `json:"message"`
}

type LogManager struct {
	mu      sync.RWMutex
	logs    []LogEntry
	maxSize int
}

var logManager = &LogManager{
	logs:    make([]LogEntry, 0, 10000),
	maxSize: 10000,
}

func (lm *LogManager) AddLog(entry LogEntry) {
	lm.mu.Lock()
	if len(lm.logs) >= lm.maxSize {
		// Очищаем половину, если переполнилось
		lm.logs = lm.logs[len(lm.logs)/2:]
	}
	lm.logs = append(lm.logs, entry)
	lm.mu.Unlock()

	// Вещаем в лог-менеджер (уже добавлено выше)
}

// Отдаем чистый JSON для Android
func (lm *LogManager) GetLogsJSON() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	
	bytes, err := json.Marshal(lm.logs)
	if err != nil {
		return "[]"
	}
	return string(bytes)
}

// Оставляем старый метод просто на всякий случай (например, для экспорта в txt)
func (lm *LogManager) GetLogs() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	var sb strings.Builder
	for _, l := range lm.logs {
		sb.WriteString(fmt.Sprintf("%s [%s] %s\n", l.Timestamp, l.Level, l.Message))
	}
	return sb.String()
}

func (lm *LogManager) ClearLogs() {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	lm.logs = make([]LogEntry, 0, lm.maxSize)
}

// Экспортируем функции для gomobile
func GetLogsJSON() string { return logManager.GetLogsJSON() }
func GetLogs() string     { return logManager.GetLogs() }
func ClearLogs()          { logManager.ClearLogs() }

// --- Обработчик slog ---

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

func (h *dualHandler) Enabled(_ context.Context, _ slog.Level) bool { return true }

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

	if len(msg) > 20 && msg[4] == '/' && msg[7] == '/' && msg[13] == ':' && msg[16] == ':' {
		msg = msg[20:]
	}

	timestamp := r.Time.Local().Format("15:04:05")

	// Определение категории
	category := "OTHER"
	lowerMsg := strings.ToLower(msg)
	if r.Level >= slog.LevelError || strings.Contains(lowerMsg, "error") || strings.Contains(lowerMsg, "failed") {
		category = "ERROR"
	} else if strings.HasPrefix(msg, "[v1]") || strings.HasPrefix(msg, "[v2]") || strings.Contains(lowerMsg, "wgengine") {
		category = "TAILSCALE"
	} else {
		category = "CORE"
	}

	entry := LogEntry{
		Timestamp: timestamp,
		Level:     r.Level.String(),
		Category:  category,
		Message:   msg,
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