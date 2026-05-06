package appctr

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func startTaildropCollector(ctx context.Context, taildropDir string) {
	slog.Info("Starting Taildrop Collector", "dir", taildropDir)
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if !IsRunning() {
				continue
			}
			processIncomingFiles(taildropDir)
		}
	}
}

func processIncomingFiles(taildropDir string) {
	data, err := doLocalRequest("GET", "/localapi/v0/files", nil)
	if err != nil {
		return
	}

	type waitingFile struct {
		Name string
		Size int64
	}
	var files []waitingFile
	if err := json.Unmarshal(data, &files); err != nil {
		return
	}

	if len(files) == 0 {
		return
	}

	slog.Info("Taildrop: Found waiting files", "count", len(files))
	if err := os.MkdirAll(taildropDir, 0755); err != nil {
		slog.Error("Taildrop: Failed to create dir", "err", err)
		return
	}

	for _, f := range files {
		destPath := filepath.Join(taildropDir, f.Name)
		slog.Info("Taildrop: Downloading file", "name", f.Name, "dest", destPath)

		content, err := doLocalRequest("GET", "/localapi/v0/files/"+url.PathEscape(f.Name), nil)
		if err != nil {
			slog.Error("Taildrop: Download failed", "name", f.Name, "err", err)
			continue
		}

		if err := os.WriteFile(destPath, content, 0644); err != nil {
			slog.Error("Taildrop: Save failed", "name", f.Name, "err", err)
			continue
		}
		slog.Info("Taildrop: Saved successfully", "name", f.Name)
	}
}

func GetTaildropFilesFromAPI() string {
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if opt == nil || opt.TaildropDir == "" {
		return "[]"
	}

	return GetWaitingFiles(opt.TaildropDir)
}

func DeleteTaildropFileFromAPI(name string) bool {
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if opt == nil || opt.TaildropDir == "" {
		return false
	}

	path := filepath.Join(opt.TaildropDir, name)
	err := os.Remove(path)
	if err != nil {
		slog.Error("Taildrop: Failed to delete file", "path", path, "err", err)
	}
	return err == nil
}

func GetTaildropDirContents() string {
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()
	if opt == nil || opt.TaildropDir == "" {
		return "TaildropDir not set"
	}
	entries, err := os.ReadDir(opt.TaildropDir)
	if err != nil {
		return "Error: " + err.Error()
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	if len(names) == 0 {
		return "Empty"
	}
	return strings.Join(names, ", ")
}

func SaveTaildropFileToPath(name, destPath string) string {
	if !IsRunning() {
		return "Tailscaled not running"
	}
	data, err := doLocalRequest("GET", "/localapi/v0/files/"+url.PathEscape(name), nil)
	if err != nil {
		return "Download failed: " + err.Error()
	}
	if err := os.WriteFile(destPath, data, 0644); err != nil {
		return "Save failed: " + err.Error()
	}
	return "OK"
}

func SendFileFromAPI(peerID, filePath string) string {
	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}

	f, err := os.Open(filePath)
	if err != nil {
		return "Error: " + err.Error()
	}
	defer f.Close()

	name := url.PathEscape(filepath.Base(filePath))
	// PUT /localapi/v0/file-put/<id>/<name>
	data, err := doLocalRequest("PUT", "/localapi/v0/file-put/"+peerID+"/"+name, f)
	if err != nil {
		return "Error: " + err.Error()
	}
	return string(data)
}
