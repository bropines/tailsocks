package appctr

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIsPlainFileName(t *testing.T) {
	for _, name := range []string{"a.txt", "photo (1).jpg", ".hidden", "..data", "a..b", "файл.pdf", " "} {
		if !isPlainFileName(name) {
			t.Errorf("%q: refused", name)
		}
	}
	for _, name := range []string{"", ".", "..", "/", "/etc/passwd", "../a", "a/b", "a/", "a/..", `a\b`, `..\a`, "a\x00b"} {
		if isPlainFileName(name) {
			t.Errorf("%q: accepted", name)
		}
	}
}

// withTaildropDir points the bridge at dir for the test, as a run started with
// StartOptions.TaildropDir would.
func withTaildropDir(t *testing.T, dir string) {
	t.Helper()
	stateMu.Lock()
	old := lastOptions
	lastOptions = &StartOptions{TaildropDir: dir}
	stateMu.Unlock()
	t.Cleanup(func() {
		stateMu.Lock()
		lastOptions = old
		stateMu.Unlock()
	})
}

// A name is joined into the Taildrop directory, so only a plain one may get
// that far: everything else named a file somewhere else.
func TestDeleteTaildropFileStaysInside(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	if err := os.MkdirAll(filepath.Join(inbox, "sub"), 0o700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(root, "outside.txt")
	nested := filepath.Join(inbox, "sub", "nested.txt")
	inside := filepath.Join(inbox, "inside.txt")
	for _, p := range []string{outside, nested, inside} {
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	withTaildropDir(t, inbox)

	for _, name := range []string{
		"", ".", "..", "../outside.txt", "sub/nested.txt", "sub/../../outside.txt",
		outside, `..\outside.txt`, "inside.txt/", "./inside.txt", "inside.txt\x00",
	} {
		if DeleteTaildropFileFromAPI(name) {
			t.Errorf("%q: reported as deleted", name)
		}
	}
	for _, p := range []string{outside, nested, inside, filepath.Join(inbox, "sub")} {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("%s is gone: %v", p, err)
		}
	}

	if !DeleteTaildropFileFromAPI("inside.txt") {
		t.Error("a plain name was not deleted")
	}
	if _, err := os.Stat(inside); !os.IsNotExist(err) {
		t.Errorf("inside.txt still there: %v", err)
	}
	if DeleteTaildropFileFromAPI("inside.txt") {
		t.Error("a missing file reported as deleted")
	}
}

func TestTaildropPutPath(t *testing.T) {
	for _, tc := range []struct {
		peer, file, want string
	}{
		{"nABCdef12CNTRL", "/data/cache/taildrop_out/share/a.txt", "/localapi/v0/file-put/nABCdef12CNTRL/a.txt"},
		{"42", "report 100%.pdf", "/localapi/v0/file-put/42/report%20100%25.pdf"},
		{"n1", "/tmp/what?.txt", "/localapi/v0/file-put/n1/what%3F.txt"},
		// Not an ID any server hands out, but one element all the same: it
		// reaches the daemon as an ID, never as part of the path.
		{"n1?x=#y", "a", "/localapi/v0/file-put/n1%3Fx=%23y/a"},
	} {
		got, err := taildropPutPath(tc.peer, tc.file)
		if err != nil {
			t.Errorf("%q, %q: %v", tc.peer, tc.file, err)
			continue
		}
		if got != tc.want {
			t.Errorf("%q, %q: %q, want %q", tc.peer, tc.file, got, tc.want)
		}
	}
	for _, tc := range []struct{ peer, file string }{
		{"", "a.txt"},
		{".", "a.txt"},
		{"..", "a.txt"},
		{"../../logout", "a.txt"},
		{"n1/extra", "a.txt"},
		{`n1\extra`, "a.txt"},
		{"n1", ""},
		{"n1", "."},
		{"n1", "/"},
		{"n1", "/tmp/dir/.."},
		{"n1", "/tmp/dir/."},
		{"n1", `/tmp/a\b`},
	} {
		if got, err := taildropPutPath(tc.peer, tc.file); err == nil {
			t.Errorf("%q, %q: accepted as %q", tc.peer, tc.file, got)
		}
	}
}
