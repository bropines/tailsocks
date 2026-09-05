package appctr

import (
	"context"
	"encoding/json"
	"io"
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
			processIncomingFiles(ctx, taildropDir)
		}
	}
}

// processIncomingFiles moves every file the daemon holds for us into
// taildropDir. ctx is the collector's: cancelling it (Stop, Detach, restart)
// aborts an in-flight download instead of letting it finish against a daemon
// the bridge has let go of.
func processIncomingFiles(ctx context.Context, taildropDir string) {
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
		if ctx.Err() != nil {
			return
		}
		destPath := filepath.Join(taildropDir, f.Name)
		slog.Info("Taildrop: Downloading file", "name", f.Name, "dest", destPath)

		if err := downloadTaildropFile(ctx, f.Name, destPath); err != nil {
			slog.Error("Taildrop: Download failed", "name", f.Name, "err", err)
			continue
		}

		// The daemon drops a file from its waiting list only on DELETE. Without
		// this the same files were fetched and rewritten on every poll, forever,
		// for everything ever received.
		if _, err := doLocalRequest("DELETE", "/localapi/v0/files/"+url.PathEscape(f.Name), nil); err != nil {
			slog.Warn("Taildrop: could not clear file from the waiting list", "name", f.Name, "err", err)
			continue
		}
		slog.Info("Taildrop: Saved successfully", "name", f.Name)
	}
}

// downloadTaildropFile streams one waiting file from the daemon to destPath.
// The body goes straight from the socket to disk: reading it into memory first
// held the whole file in the heap (twice, with the WriteFile copy), which OOM-
// kills the process on a phone for a video-sized transfer — and because the
// daemon keeps the file until it is deleted, the same download was retried
// every 5s. The deadline-free client is used so a transfer slower than 30s is
// not cut off; a partial file is removed so it is not listed as received.
func downloadTaildropFile(ctx context.Context, name, destPath string) error {
	resp, err := doLocalStream(ctx, "GET", "/localapi/v0/files/"+url.PathEscape(name), nil, -1)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// Stream into a temporary name and rename on success: the transfer can now
	// take minutes, and GetWaitingFiles lists the directory while it runs, so a
	// half-written file must not be visible under its final name.
	out, err := os.CreateTemp(filepath.Dir(destPath), ".taildrop-*.part")
	if err != nil {
		return err
	}
	tmpPath := out.Name()
	if _, err := io.Copy(out, resp.Body); err != nil {
		out.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(tmpPath)
		return err
	}
	if err := os.Chmod(tmpPath, 0644); err != nil {
		os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, destPath); err != nil {
		os.Remove(tmpPath)
		return err
	}
	return nil
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
	// User-initiated and synchronous on the caller's thread: it is not bound to
	// a daemon run, the transfer itself decides when it ends.
	if err := downloadTaildropFile(context.Background(), name, destPath); err != nil {
		return "Download failed: " + err.Error()
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

	fi, err := f.Stat()
	if err != nil {
		return "Error: " + err.Error()
	}

	name := url.PathEscape(filepath.Base(filePath))
	// PUT /localapi/v0/file-put/<id>/<name>, on the deadline-free client: the
	// upload takes as long as the tailnet path allows (a few hundred MB over
	// DERP is well past 30s), and the 30s client aborted it half-way with an
	// error to the user. The Content-Length lets the daemon announce the size
	// to the peer, the way the CLI does.
	resp, err := doLocalStream(context.Background(), "PUT", "/localapi/v0/file-put/"+peerID+"/"+name, f, fi.Size())
	if err != nil {
		return "Error: " + err.Error()
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return "Error: " + err.Error()
	}
	return string(data)
}
