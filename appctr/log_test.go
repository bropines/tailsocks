package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"testing"
	"time"
)

func newTestLogManager(max int) *LogManager {
	return &LogManager{logs: make([]LogEntry, 0, max), maxSize: max}
}

func addN(lm *LogManager, category string, n int) {
	for i := 0; i < n; i++ {
		lm.AddLog(LogEntry{Category: category, Message: fmt.Sprintf("%s %d", category, i)})
	}
}

func since(t *testing.T, lm *LogManager, gen, seq int64) LogDelta {
	t.Helper()
	var d LogDelta
	if err := json.Unmarshal([]byte(lm.GetLogsJSONSince(gen, seq)), &d); err != nil {
		t.Fatal(err)
	}
	return d
}

func seqs(es []LogEntry) []int64 {
	out := make([]int64, len(es))
	for i, e := range es {
		out[i] = e.Seq
	}
	return out
}

// A full buffer drops its older half; Seq keeps counting.
func TestAddLogTrimsTheOlderHalf(t *testing.T) {
	lm := newTestLogManager(10)
	addN(lm, "CORE", 10)
	if len(lm.logs) != 10 || lm.logs[0].Seq != 1 {
		t.Fatalf("before the trim: %v", seqs(lm.logs))
	}
	lm.AddLog(LogEntry{Message: "eleventh"})
	if got := seqs(lm.logs); fmt.Sprint(got) != "[6 7 8 9 10 11]" {
		t.Fatalf("after the trim: %v, want 6..11", got)
	}
}

// The Logs screen's read: everything first, then only what is new; the trim
// moves First so the reader drops what the buffer dropped; a clear — which
// takes entries from the middle — hands the reader the whole buffer again.
func TestGetLogsJSONSince(t *testing.T) {
	lm := newTestLogManager(10)
	addN(lm, "CORE", 3)
	addN(lm, "TAILSCALE", 3)

	d := since(t, lm, -1, 0)
	if !d.Reset || len(d.Entries) != 6 || d.First != 1 || d.Last != 6 {
		t.Fatalf("first read: %+v", d)
	}
	gen := d.Gen

	d = since(t, lm, gen, 6)
	if d.Reset || len(d.Entries) != 0 || d.Entries == nil {
		t.Fatalf("nothing new: %+v (entries must be [], not null)", d)
	}

	addN(lm, "CORE", 2)
	d = since(t, lm, gen, 6)
	if d.Reset || fmt.Sprint(seqs(d.Entries)) != "[7 8]" || d.Last != 8 {
		t.Fatalf("two new: %+v", d)
	}

	addN(lm, "CORE", 3) // 11 entries: the trim
	d = since(t, lm, gen, 8)
	if d.Reset || fmt.Sprint(seqs(d.Entries)) != "[9 10 11]" || d.First != 6 {
		t.Fatalf("across the trim: %+v", d)
	}

	lm.ClearLogsWhere("TAILSCALE", false)
	d = since(t, lm, gen, 11)
	if !d.Reset || d.Gen == gen {
		t.Fatalf("after a clear: %+v, want a reset with a new gen", d)
	}
	for _, e := range d.Entries {
		if e.Category == "TAILSCALE" {
			t.Errorf("a cleared entry came back: %+v", e)
		}
	}
	if fmt.Sprint(seqs(d.Entries)) != "[7 8 9 10 11]" {
		t.Errorf("after the clear: %v", seqs(d.Entries))
	}

	gen = d.Gen
	lm.ClearLogs()
	d = since(t, lm, gen, 11)
	if !d.Reset || len(d.Entries) != 0 || d.First != 0 || d.Last != 11 {
		t.Fatalf("after clearing everything: %+v", d)
	}
	lm.AddLog(LogEntry{Message: "after"})
	if d2 := since(t, lm, d.Gen, d.Last); fmt.Sprint(seqs(d2.Entries)) != "[12]" {
		t.Fatalf("the first line after a clear: %+v", d2)
	}
}

// ClearLogsWhere keeps one category or everything but it, in order.
func TestClearLogsWhere(t *testing.T) {
	lm := newTestLogManager(100)
	lm.AddLog(LogEntry{Category: "CORE", Message: "a"})
	lm.AddLog(LogEntry{Category: "TAILSCALE", Message: "b"})
	lm.AddLog(LogEntry{Category: "ERROR", Message: "c"})
	lm.AddLog(LogEntry{Category: "TAILSCALE", Message: "d"})

	keep := newTestLogManager(100)
	keep.logs = append(keep.logs, lm.logs...)
	keep.ClearLogsWhere("TAILSCALE", true)
	if got := fmt.Sprint(messages(keep.logs)); got != "[b d]" {
		t.Errorf("keep TAILSCALE: %s", got)
	}
	lm.ClearLogsWhere("TAILSCALE", false)
	if got := fmt.Sprint(messages(lm.logs)); got != "[a c]" {
		t.Errorf("drop TAILSCALE: %s", got)
	}
}

func messages(es []LogEntry) []string {
	out := make([]string, len(es))
	for i, e := range es {
		out[i] = e.Message
	}
	return out
}

// Readers marshal outside the lock while writers append and trim: the race
// detector has the last word on whether that is safe.
func TestLogReadersDoNotRaceWriters(t *testing.T) {
	lm := newTestLogManager(64)
	var wg sync.WaitGroup
	stop := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			lm.AddLog(LogEntry{Message: fmt.Sprint(i)})
			if i%500 == 0 {
				lm.ClearLogsWhere("X", false)
			}
		}
	}()
	var gen, seq int64 = -1, 0
	for i := 0; i < 300; i++ {
		_ = lm.GetLogsJSON()
		_ = lm.GetLogs()
		d := since(t, lm, gen, seq)
		gen, seq = d.Gen, d.Last
	}
	close(stop)
	wg.Wait()
}

// The handler sorts lines into the categories the Logs screen's chips show:
// the daemon's stdout is TAILSCALE with its level read off the text, the
// app's own error lines are ERROR, everything else CORE.
func TestDualHandlerClassifies(t *testing.T) {
	old := logManager
	logManager = newTestLogManager(100)
	t.Cleanup(func() { logManager = old })
	h := newDualHandler()
	handle := func(level slog.Level, msg string, attrs ...slog.Attr) LogEntry {
		t.Helper()
		r := slog.NewRecord(time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC), level, msg, 0)
		r.AddAttrs(attrs...)
		if err := h.Handle(context.Background(), r); err != nil {
			t.Fatal(err)
		}
		return logManager.logs[len(logManager.logs)-1]
	}
	daemon := slog.String("src", "daemon")

	cases := []struct {
		got           LogEntry
		level, cat    string
		wantMessageIs string
	}{
		{handle(slog.LevelInfo, "magicsock: endpoints changed", daemon), "INFO", "TAILSCALE", "magicsock: endpoints changed"},
		{handle(slog.LevelInfo, "2026/11/14 12:00:00 control: dial failed", daemon), "ERROR", "TAILSCALE", "control: dial failed"},
		{handle(slog.LevelInfo, "health: warning: no DERP", daemon), "WARN", "TAILSCALE", "health: warning: no DERP"},
		{handle(slog.LevelInfo, "Starting", slog.Int("port", 1053)), "INFO", "CORE", "Starting port=1053"},
		{handle(slog.LevelInfo, "PUT failed"), "INFO", "ERROR", "PUT failed"},
		{handle(slog.LevelError, "boom"), "ERROR", "ERROR", "boom"},
	}
	for i, c := range cases {
		if c.got.Level != c.level || c.got.Category != c.cat || c.got.Message != c.wantMessageIs {
			t.Errorf("case %d: got %+v, want level %s category %s message %q", i, c.got, c.level, c.cat, c.wantMessageIs)
		}
		if c.got.Unix == 0 || c.got.Seq == 0 {
			t.Errorf("case %d: no time or seq: %+v", i, c.got)
		}
	}
}
