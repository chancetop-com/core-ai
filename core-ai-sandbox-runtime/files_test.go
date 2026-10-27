package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func uploadRequest(t *testing.T, target string, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/files/upload?path="+target, strings.NewReader(body))
	req.ContentLength = int64(len(body))
	rec := httptest.NewRecorder()
	handleFileUpload(rec, req)
	return rec
}

func TestFileUploadStreamsTheWholeBodyToDisk(t *testing.T) {
	previousWorkspace := workspaceDir
	workspaceDir = t.TempDir()
	defer func() { workspaceDir = previousWorkspace }()
	target := filepath.Join(workspaceDir, "attachments", "clip.mp4")
	body := strings.Repeat("video-bytes-", 1024)

	rec := uploadRequest(t, target, body)

	if rec.Code != http.StatusOK {
		t.Fatalf("upload status = %d, body = %s", rec.Code, rec.Body.String())
	}
	written, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if string(written) != body {
		t.Fatalf("uploaded %d bytes, want %d", len(written), len(body))
	}
	entries, err := os.ReadDir(filepath.Dir(target))
	if err != nil {
		t.Fatalf("read upload dir: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("upload left %d files, want only the target: %v", len(entries), entries)
	}
}

func TestFileUploadAcceptsAVideoPastTheOldTwentyMegabyteCap(t *testing.T) {
	previousWorkspace := workspaceDir
	workspaceDir = t.TempDir()
	defer func() { workspaceDir = previousWorkspace }()
	target := filepath.Join(workspaceDir, "attachments", "long.mp4")
	body := strings.Repeat("v", 25<<20) // the trace that started this: a 24.6 MB clip rejected by the old cap

	rec := uploadRequest(t, target, body)

	if rec.Code != http.StatusOK {
		t.Fatalf("upload status = %d, body = %s", rec.Code, rec.Body.String())
	}
	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("stat uploaded file: %v", err)
	}
	if info.Size() != int64(len(body)) {
		t.Fatalf("uploaded %d bytes, want %d", info.Size(), len(body))
	}
}

func TestFileUploadRejectsABodyOverTheConfiguredLimit(t *testing.T) {
	previousWorkspace, previousLimit := workspaceDir, maxUploadFileSize
	workspaceDir = t.TempDir()
	maxUploadFileSize = 8
	defer func() {
		workspaceDir = previousWorkspace
		maxUploadFileSize = previousLimit
	}()
	target := filepath.Join(workspaceDir, "too-big.mp4")

	rec := uploadRequest(t, target, "123456789")

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("upload status = %d, want 413, body = %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("oversized upload left a file behind: %v", err)
	}
	if entries, err := os.ReadDir(workspaceDir); err == nil && len(entries) != 0 {
		t.Fatalf("oversized upload left temp files behind: %v", entries)
	}
}

func TestFileUploadRejectsAPathOutsideTheWorkspace(t *testing.T) {
	previousWorkspace := workspaceDir
	workspaceDir = t.TempDir()
	defer func() { workspaceDir = previousWorkspace }()

	rec := uploadRequest(t, "/etc/escape.txt", "x")

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("upload status = %d, want 400, body = %s", rec.Code, rec.Body.String())
	}
}
