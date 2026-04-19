package appctr

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
)

// TaildropFile представляет информацию о принятом файле
type TaildropFile struct {
	Name    string
	Size    int64
	ModTime int64
	Path    string
}

// SendFile отправляет файл на указанный узел.
// Имя файла у получателя будет таким же, как имя локального файла по пути filePath.
func SendFile(nodeId string, filePath string) string {
	// Используем RunTailscaleArgs, чтобы пробелы в путях не ломали команду
	return RunTailscaleArgs("file", "cp", filePath, nodeId+":")
}

// GetWaitingFiles возвращает список файлов в папке Taildrop (в формате JSON)
func GetWaitingFiles(taildropDir string) string {
	entries, err := os.ReadDir(taildropDir)
	if err != nil {
		return "[]"
	}

	var files []*TaildropFile
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		files = append(files, &TaildropFile{
			Name:    entry.Name(),
			Size:    info.Size(),
			ModTime: info.ModTime().Unix(),
			Path:    filepath.Join(taildropDir, entry.Name()),
		})
	}

	sort.Slice(files, func(i, j int) bool {
		return files[i].ModTime > files[j].ModTime
	})

	b, _ := json.Marshal(files)
	return string(b)
}

// DeleteTaildropFile удаляет принятый файл
func DeleteTaildropFile(path string) bool {
	err := os.Remove(path)
	return err == nil
}
