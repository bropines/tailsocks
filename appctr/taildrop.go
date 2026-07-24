package appctr

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

// getLastOptions safely retrieves the last StartOptions under stateMu.
func getLastOptions() *StartOptions {
	stateMu.Lock()
	defer stateMu.Unlock()
	return lastOptions
}

// GetWaitingFiles scans the Taildrop directory and returns a JSON list of files.
func GetWaitingFiles(dir string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "[]"
	}
	type fileInfo struct {
		Name string `json:"Name"`
		Size int64  `json:"Size"`
		Path string `json:"Path"`
	}
	var files []fileInfo
	for _, e := range entries {
		if !e.IsDir() {
			info, err := e.Info()
			if err == nil {
				files = append(files, fileInfo{
					Name: e.Name(),
					Size: info.Size(),
					Path: filepath.Join(dir, e.Name()),
				})
			}
		}
	}
	data, _ := json.Marshal(files)
	return string(data)
}

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
	filesStr, err := GetWaitingFilesJSON()
	if err != nil {
		return
	}

	type waitingFile struct {
		Name string
		Size int64
	}
	var files []waitingFile
	if err := json.Unmarshal([]byte(filesStr), &files); err != nil {
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
	opt := getLastOptions()
	if opt == nil || opt.TaildropDir == "" {
		return "[]"
	}
	return GetWaitingFiles(opt.TaildropDir)
}

func DeleteTaildropFileFromAPI(name string) bool {
	opt := getLastOptions()
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




func SaveTaildropFileToPath(name, destPath string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
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

// SendFileFromAPI sends a file to a peer via LocalAPI PUT.
func SendFileFromAPI(peerID, filePath string) string {
	if !IsRunning() {
		return "Error: " + errNotRunning.Error()
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
