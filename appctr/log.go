package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"
)

// LogEntry is the structured log record sent to Kotlin.
type LogEntry struct {
	// Unix is the entry's wall-clock time in milliseconds since the epoch.
	// Timestamp carries only the time of day, and a buffer that lives as long
	// as the app process spans days; the Logs screen orders it by this, together
	// with the root daemon's dated file, instead of by seconds since midnight.
	Unix      int64  `json:"unix"`
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
		// Discard the oldest half when the buffer is full.
		lm.logs = lm.logs[len(lm.logs)/2:]
	}
	lm.logs = append(lm.logs, entry)
	lm.mu.Unlock()
}

// GetLogsJSON returns the log buffer as a clean JSON array for Android.
func (lm *LogManager) GetLogsJSON() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	bytes, err := json.Marshal(lm.logs)
	if err != nil {
		return "[]"
	}
	return string(bytes)
}

// GetLogs returns logs as plain text (e.g. for export to a .txt file), one
// entry per line with the date, so an export that spans days can be read.
func (lm *LogManager) GetLogs() string {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	var sb strings.Builder
	for _, l := range lm.logs {
		day := ""
		if l.Unix != 0 {
			day = time.UnixMilli(l.Unix).Local().Format("2006/01/02 ")
		}
		fmt.Fprintf(&sb, "%s%s [%s] [%s] %s\n", day, l.Timestamp, l.Level, l.Category, l.Message)
	}
	return sb.String()
}

func (lm *LogManager) ClearLogs() {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	lm.logs = make([]LogEntry, 0, lm.maxSize)
}

// ClearLogsWhere drops the entries of one category, or, with keep set, every
// entry except that category. The Logs screen clears by source: the daemon's
// lines are all TAILSCALE, everything else is the app's.
func (lm *LogManager) ClearLogsWhere(category string, keep bool) {
	lm.mu.Lock()
	defer lm.mu.Unlock()
	kept := make([]LogEntry, 0, len(lm.logs))
	for _, l := range lm.logs {
		if (l.Category == category) == keep {
			kept = append(kept, l)
		}
	}
	lm.logs = kept
}

// Exported wrappers for gomobile.
func GetLogsJSON() string { return logManager.GetLogsJSON() }
func GetLogs() string     { return logManager.GetLogs() }
func ClearLogs()          { logManager.ClearLogs() }
func ClearLogsWhere(category string, keep bool) {
	logManager.ClearLogsWhere(category, keep)
}
func LogAndroid(level, category, message string) {
	now := time.Now()
	logManager.AddLog(LogEntry{
		Unix:      now.UnixMilli(),
		Timestamp: now.Format("15:04:05"),
		Level:     level,
		Category:  category,
		Message:   message,
	})
}

// --- slog handler ---

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
	source := ""
	r.Attrs(func(a slog.Attr) bool {
		// The daemon's stdout is logged with src=daemon (logWithFilter); the
		// attribute names the source and is not part of the line.
		if a.Key == "src" {
			source = a.Value.String()
			return true
		}
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
	lowerMsg := strings.ToLower(msg)

	// Category is the source: TAILSCALE is the daemon (its stdout here, its
	// file in Root Mode, which the Logs screen parses the same way), CORE is
	// the app, and ERROR keeps the app's own error-level lines red. The level
	// of a daemon line is read off the text, as the Logs screen does for the file.
	category := "CORE"
	level := r.Level.String()
	switch {
	case source == "daemon":
		category = "TAILSCALE"
		switch {
		case strings.Contains(lowerMsg, "error") || strings.Contains(lowerMsg, "failed") || strings.Contains(lowerMsg, "panic"):
			level = "ERROR"
		case strings.Contains(lowerMsg, "warn"):
			level = "WARN"
		}
	case r.Level >= slog.LevelError || strings.Contains(lowerMsg, "error") || strings.Contains(lowerMsg, "failed"):
		category = "ERROR"
	}

	entry := LogEntry{
		Unix:      r.Time.UnixMilli(),
		Timestamp: timestamp,
		Level:     level,
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
