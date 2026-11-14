package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// LogEntry is the structured log record sent to Kotlin.
type LogEntry struct {
	// Seq numbers the entries of the process in the order they were added,
	// from 1; the Logs screen asks for what came after the last one it has
	// (GetLogsJSONSince).
	Seq int64 `json:"seq"`
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

// LogManager is the in-process log buffer.
//
// An entry is never changed once it is in logs, and logs is only ever
// re-sliced from the front (the trim), appended to, or replaced by a new slice
// (the clears): an append writes past the end every earlier copy of the slice
// header saw. So a reader may take the header under the lock and read the
// entries after releasing it — marshalling 10,000 of them under the lock made
// every slog call in the process, the daemon's stdout scanners and the DNS
// proxy included, wait for the Logs screen.
type LogManager struct {
	mu      sync.RWMutex
	logs    []LogEntry
	maxSize int
	// seq is the last Seq handed out; gen counts the clears, which take
	// entries out of the middle where a reader holding a Seq cannot see it.
	seq int64
	gen int64
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
	lm.seq++
	entry.Seq = lm.seq
	lm.logs = append(lm.logs, entry)
	lm.mu.Unlock()
}

// snapshot is the buffer as it is now; see LogManager for why it may be read
// without the lock.
func (lm *LogManager) snapshot() (logs []LogEntry, seq, gen int64) {
	lm.mu.RLock()
	defer lm.mu.RUnlock()
	return lm.logs, lm.seq, lm.gen
}

// GetLogsJSON returns the log buffer as a clean JSON array for Android.
func (lm *LogManager) GetLogsJSON() string {
	logs, _, _ := lm.snapshot()
	bytes, err := json.Marshal(logs)
	if err != nil {
		return "[]"
	}
	return string(bytes)
}

// LogDelta is what GetLogsJSONSince answers: the entries after a reader's
// last one, and what it needs to drop from what it holds.
type LogDelta struct {
	// Gen is the buffer's clear count, for the reader's next call.
	Gen int64 `json:"gen"`
	// Reset says that Entries is the whole buffer and replaces what the
	// reader holds: it asked with another Gen — its first call, or a clear
	// since its last.
	Reset bool `json:"reset"`
	// First is the Seq of the oldest entry still in the buffer (0 when it is
	// empty): the reader drops what it holds below it, as the buffer dropped
	// its older half.
	First int64 `json:"first"`
	// Last is the newest Seq handed out, for the reader's next call.
	Last    int64      `json:"last"`
	Entries []LogEntry `json:"entries"`
}

// GetLogsJSONSince returns, as a LogDelta, the entries added after seq — or
// the whole buffer when gen is not the buffer's (a first call passes -1). The
// Logs screen asks this every 2 s: it carries the few new lines instead of
// the whole buffer, a megabyte or two of JSON at 10,000 entries.
func (lm *LogManager) GetLogsJSONSince(gen, seq int64) string {
	logs, last, g := lm.snapshot()
	d := LogDelta{Gen: g, Reset: gen != g, Last: last, Entries: logs}
	if len(logs) > 0 {
		d.First = logs[0].Seq
	}
	if !d.Reset {
		// Seq rises along the slice, the clears keep the order.
		i := sort.Search(len(logs), func(i int) bool { return logs[i].Seq > seq })
		d.Entries = logs[i:]
	}
	if d.Entries == nil {
		d.Entries = []LogEntry{}
	}
	bytes, err := json.Marshal(d)
	if err != nil {
		return ""
	}
	return string(bytes)
}

// GetLogs returns logs as plain text (e.g. for export to a .txt file), one
// entry per line with the date, so an export that spans days can be read.
func (lm *LogManager) GetLogs() string {
	logs, _, _ := lm.snapshot()
	var sb strings.Builder
	for _, l := range logs {
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
	lm.gen++
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
	lm.gen++
}

// Exported wrappers for gomobile.
func GetLogsJSON() string { return logManager.GetLogsJSON() }
func GetLogsJSONSince(gen, seq int64) string {
	return logManager.GetLogsJSONSince(gen, seq)
}
func GetLogs() string { return logManager.GetLogs() }
func ClearLogs()      { logManager.ClearLogs() }
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

// Enabled drops debug lines unless the detailed log is on (SetLogLevel(0)).
// It used to pass everything, and the DNS proxy's debug line per query filled
// the 10,000-entry buffer by itself — pushing everything else out of it, and
// putting every name the device looked up into a log that gets exported.
func (h *dualHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= slog.LevelInfo || atomic.LoadInt32(&currentLogLevel) <= 0
}

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
