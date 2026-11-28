package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func setupPersistentWorkspaceForArchiveTest(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	prevDir := workspaceDir
	workspaceDir = dir
	t.Cleanup(func() { workspaceDir = prevDir })

	envPath := filepath.Join(dir, ".core-ai", "env")
	t.Setenv("WORKSPACE_ENV_FILE", envPath)
	t.Setenv("WORKSPACE_ID", "ws-test-1")
	t.Setenv("WORKSPACE_IMAGE", "core-ai-sandbox:test")
	return dir
}

func archiveViaHandler(t *testing.T, body workspaceArchiveRequest) *httptest.ResponseRecorder {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/workspace/archive", bytes.NewReader(raw))
	rec := httptest.NewRecorder()
	handleWorkspaceArchive(rec, req)
	return rec
}

func TestWorkspaceArchiveAndRestoreRoundTrip(t *testing.T) {
	dir := setupPersistentWorkspaceForArchiveTest(t)

	writeFile(t, filepath.Join(dir, "repo", "hello.txt"), "hello persistent world\n", 0644)
	writeFile(t, filepath.Join(dir, "bin", "run.sh"), "#!/bin/sh\necho ok\n", 0755)
	writeFile(t, filepath.Join(dir, ".core-ai", "env"), "FOO=bar\n", 0644)
	writeFile(t, filepath.Join(dir, ".core-ai", "tasks", "fresh.out"), "recent output", 0644)
	expiredPath := filepath.Join(dir, ".core-ai", "tasks", "expired.out")
	writeFile(t, expiredPath, "old output", 0644)
	oldTime := time.Now().Add(-10 * 24 * time.Hour)
	if err := os.Chtimes(expiredPath, oldTime, oldTime); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("../repo/hello.txt", filepath.Join(dir, "bin", "hello-link")); err != nil {
		t.Fatalf("symlink failed: %v", err)
	}

	// Write a file in /tmp and verify it is NOT included in the archive.
	tmpMarker := filepath.Join(os.TempDir(), "ws-archive-exclude-test-marker.txt")
	if err := os.WriteFile(tmpMarker, []byte("should not be archived"), 0644); err == nil {
		t.Cleanup(func() { _ = os.Remove(tmpMarker) })
	}

	rec := archiveViaHandler(t, workspaceArchiveRequest{
		ArchiveID:   "arch-1",
		WorkspaceID: "ws-test-1",
		Generation:  3,
		Image:       "core-ai-sandbox:1.0",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("archive status = %d, want 200, body = %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/gzip" {
		t.Fatalf("Content-Type = %q, want application/gzip", ct)
	}

	archiveBytes := append([]byte(nil), rec.Body.Bytes()...)

	// Inspect tar to verify manifest.json is the first entry and /tmp is excluded.
	gz, err := gzip.NewReader(bytes.NewReader(archiveBytes))
	if err != nil {
		t.Fatalf("gzip open: %v", err)
	}
	tr := tar.NewReader(gz)
	first, err := tr.Next()
	if err != nil {
		t.Fatalf("first tar entry: %v", err)
	}
	if first.Name != "manifest.json" {
		t.Fatalf("first entry = %q, want manifest.json", first.Name)
	}
	var manifest workspaceArchiveManifest
	if err := json.NewDecoder(tr).Decode(&manifest); err != nil {
		t.Fatalf("decode manifest: %v", err)
	}
	if manifest.WorkspaceID != "ws-test-1" || manifest.VolumeGeneration != 3 || manifest.Image != "core-ai-sandbox:1.0" {
		t.Fatalf("unexpected manifest: %+v", manifest)
	}
	if manifest.FileCount != 5 {
		t.Fatalf("manifest fileCount = %d, want 5 (hello, run.sh, env, fresh.out, symlink)", manifest.FileCount)
	}
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("tar next: %v", err)
		}
		if strings.Contains(hdr.Name, "ws-archive-exclude-test-marker") || strings.Contains(hdr.Name, "expired.out") {
			t.Fatalf("excluded file present in archive: %s", hdr.Name)
		}
	}
	_ = gz.Close()

	// Restore into a fresh empty workspace directory (with platform scaffold dirs).
	restoreDir := t.TempDir()
	workspaceDir = restoreDir
	if err := os.MkdirAll(filepath.Join(restoreDir, "home"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(restoreDir, ".npm-global", "bin"), 0755); err != nil {
		t.Fatal(err)
	}

	restoreReq := httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(archiveBytes))
	restoreReq.Header.Set("X-Archive-Generation", "3")
	restoreReq.Header.Set("X-Workspace-Id", "ws-test-1")
	restoreRec := httptest.NewRecorder()
	handleWorkspaceRestore(restoreRec, restoreReq)
	if restoreRec.Code != http.StatusOK {
		t.Fatalf("restore status = %d, want 200, body = %s", restoreRec.Code, restoreRec.Body.String())
	}

	gotHello, err := os.ReadFile(filepath.Join(restoreDir, "repo", "hello.txt"))
	if err != nil || string(gotHello) != "hello persistent world\n" {
		t.Fatalf("restored hello.txt mismatch: %v %q", err, gotHello)
	}
	info, err := os.Stat(filepath.Join(restoreDir, "bin", "run.sh"))
	if err != nil || info.Mode().Perm() != 0755 {
		t.Fatalf("restored run.sh mode = %v err = %v, want 0755", info, err)
	}
	gotEnv, err := os.ReadFile(filepath.Join(restoreDir, ".core-ai", "env"))
	if err != nil || string(gotEnv) != "FOO=bar\n" {
		t.Fatalf("restored env mismatch: %v %q", err, gotEnv)
	}
	linkTarget, err := os.Readlink(filepath.Join(restoreDir, "bin", "hello-link"))
	if err != nil || linkTarget != "../repo/hello.txt" {
		t.Fatalf("restored symlink = %q err = %v", linkTarget, err)
	}
	if _, err := os.Stat(filepath.Join(restoreDir, ".core-ai", "tasks", "expired.out")); !os.IsNotExist(err) {
		t.Fatalf("expired task output should not have been archived/restored")
	}
	if _, err := os.Stat(filepath.Join(restoreDir, ".core-ai", "restore-staging")); !os.IsNotExist(err) {
		t.Fatalf("restore-staging dir should be cleaned up")
	}
}

func TestWorkspaceArchiveAndRestoreMethodAndEnabledGuards(t *testing.T) {
	rec := httptest.NewRecorder()
	handleWorkspaceArchive(rec, httptest.NewRequest(http.MethodGet, "/workspace/archive", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("archive GET status = %d, want 405", rec.Code)
	}

	rec = httptest.NewRecorder()
	handleWorkspaceRestore(rec, httptest.NewRequest(http.MethodGet, "/workspace/restore", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("restore GET status = %d, want 405", rec.Code)
	}

	t.Setenv("WORKSPACE_ENV_FILE", "")
	rec = httptest.NewRecorder()
	handleWorkspaceArchive(rec, httptest.NewRequest(http.MethodPost, "/workspace/archive", nil))
	if rec.Code != http.StatusConflict {
		t.Fatalf("archive without WORKSPACE_ENV_FILE status = %d, want 409", rec.Code)
	}

	rec = httptest.NewRecorder()
	handleWorkspaceRestore(rec, httptest.NewRequest(http.MethodPost, "/workspace/restore", nil))
	if rec.Code != http.StatusConflict {
		t.Fatalf("restore without WORKSPACE_ENV_FILE status = %d, want 409", rec.Code)
	}
}

func TestWorkspaceRestoreRejectsGenerationAndWorkspaceMismatch(t *testing.T) {
	dir := setupPersistentWorkspaceForArchiveTest(t)
	writeFile(t, filepath.Join(dir, "file.txt"), "content", 0644)

	rec := archiveViaHandler(t, workspaceArchiveRequest{
		ArchiveID:   "arch-2",
		WorkspaceID: "ws-test-1",
		Generation:  2,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("archive status = %d", rec.Code)
	}
	archiveBytes := rec.Body.Bytes()

	// Generation mismatch -> 409
	workspaceDir = t.TempDir()
	req := httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(archiveBytes))
	req.Header.Set("X-Archive-Generation", "99")
	req.Header.Set("X-Workspace-Id", "ws-test-1")
	res := httptest.NewRecorder()
	handleWorkspaceRestore(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("generation mismatch status = %d, want 409, body = %s", res.Code, res.Body.String())
	}

	// WorkspaceId mismatch -> 409
	req = httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(archiveBytes))
	req.Header.Set("X-Archive-Generation", "2")
	req.Header.Set("X-Workspace-Id", "ws-other")
	res = httptest.NewRecorder()
	handleWorkspaceRestore(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("workspaceId mismatch status = %d, want 409, body = %s", res.Code, res.Body.String())
	}
	if _, err := os.Stat(filepath.Join(workspaceDir, "file.txt")); !os.IsNotExist(err) {
		t.Fatalf("mismatched restore must not write files into workspace")
	}
}

func TestWorkspaceRestoreRejectsNonEmptyUnlessAllowOverwrite(t *testing.T) {
	dir := setupPersistentWorkspaceForArchiveTest(t)
	writeFile(t, filepath.Join(dir, "new.txt"), "from archive", 0644)

	rec := archiveViaHandler(t, workspaceArchiveRequest{
		ArchiveID:   "arch-3",
		WorkspaceID: "ws-test-1",
		Generation:  1,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("archive status = %d", rec.Code)
	}
	archiveBytes := rec.Body.Bytes()

	targetDir := t.TempDir()
	workspaceDir = targetDir
	writeFile(t, filepath.Join(targetDir, "existing.txt"), "keep me", 0644)

	// Without X-Allow-Overwrite -> 409 WORKSPACE_RESTORE_NOT_EMPTY
	req := httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(archiveBytes))
	req.Header.Set("X-Archive-Generation", "1")
	req.Header.Set("X-Workspace-Id", "ws-test-1")
	res := httptest.NewRecorder()
	handleWorkspaceRestore(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("non-empty status = %d, want 409, body = %s", res.Code, res.Body.String())
	}
	var errResp workspaceArchiveError
	if err := json.Unmarshal(res.Body.Bytes(), &errResp); err != nil {
		t.Fatal(err)
	}
	if errResp.ErrorCode != "WORKSPACE_RESTORE_NOT_EMPTY" {
		t.Fatalf("errorCode = %q, want WORKSPACE_RESTORE_NOT_EMPTY", errResp.ErrorCode)
	}

	// With X-Allow-Overwrite: true -> 200 and replaces cleanly
	req = httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(archiveBytes))
	req.Header.Set("X-Archive-Generation", "1")
	req.Header.Set("X-Workspace-Id", "ws-test-1")
	req.Header.Set("X-Allow-Overwrite", "true")
	res = httptest.NewRecorder()
	handleWorkspaceRestore(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("overwrite status = %d, want 200, body = %s", res.Code, res.Body.String())
	}
	if _, err := os.Stat(filepath.Join(targetDir, "existing.txt")); !os.IsNotExist(err) {
		t.Fatalf("old file should be replaced after atomic swap")
	}
	got, err := os.ReadFile(filepath.Join(targetDir, "new.txt"))
	if err != nil || string(got) != "from archive" {
		t.Fatalf("restored file mismatch: %v %q", err, got)
	}
}

func TestWorkspaceRestoreCorruptStreamLeavesWorkspaceUntouched(t *testing.T) {
	dir := setupPersistentWorkspaceForArchiveTest(t)
	writeFile(t, filepath.Join(dir, "keep.txt"), "intact", 0644)

	rec := archiveViaHandler(t, workspaceArchiveRequest{
		ArchiveID:   "arch-4",
		WorkspaceID: "ws-test-1",
		Generation:  1,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("archive status = %d", rec.Code)
	}
	truncated := rec.Body.Bytes()[:len(rec.Body.Bytes())/2]

	req := httptest.NewRequest(http.MethodPost, "/workspace/restore", bytes.NewReader(truncated))
	req.Header.Set("X-Archive-Generation", "1")
	req.Header.Set("X-Workspace-Id", "ws-test-1")
	req.Header.Set("X-Allow-Overwrite", "true")
	res := httptest.NewRecorder()
	handleWorkspaceRestore(res, req)
	if res.Code == http.StatusOK {
		t.Fatalf("corrupt stream must fail, got 200")
	}

	got, err := os.ReadFile(filepath.Join(dir, "keep.txt"))
	if err != nil || string(got) != "intact" {
		t.Fatalf("existing workspace file must remain intact after failed restore: %v %q", err, got)
	}
}

func TestWorkspaceArchiveRejectsWhenAsyncTaskRunning(t *testing.T) {
	setupPersistentWorkspaceForArchiveTest(t)
	prevRegistry := taskRegistry
	reg := NewTaskRegistry(2)
	taskRegistry = reg
	t.Cleanup(func() { taskRegistry = prevRegistry })

	block := make(chan struct{})
	id, err := reg.Submit("run_bash_command", func(ctx context.Context) (string, string) {
		select {
		case <-block:
		case <-ctx.Done():
		}
		return "done", "completed"
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		close(block)
		reg.Cancel(id)
	}()

	rec := archiveViaHandler(t, workspaceArchiveRequest{
		ArchiveID:   "arch-busy",
		WorkspaceID: "ws-test-1",
		Generation:  0,
	})
	if rec.Code != http.StatusConflict {
		t.Fatalf("busy archive status = %d, want 409, body = %s", rec.Code, rec.Body.String())
	}
	var errResp workspaceArchiveError
	if err := json.Unmarshal(rec.Body.Bytes(), &errResp); err != nil {
		t.Fatal(err)
	}
	if errResp.ErrorCode != "WORKSPACE_BUSY" {
		t.Fatalf("errorCode = %q, want WORKSPACE_BUSY", errResp.ErrorCode)
	}
}

type memSamplingResponseWriter struct {
	header      http.Header
	status      int
	written     int64
	lastSampled int64
	peakHeap    uint64
}

func (m *memSamplingResponseWriter) Header() http.Header {
	if m.header == nil {
		m.header = make(http.Header)
	}
	return m.header
}

func (m *memSamplingResponseWriter) WriteHeader(code int) {
	m.status = code
}

func (m *memSamplingResponseWriter) Write(p []byte) (int, error) {
	if m.status == 0 {
		m.status = http.StatusOK
	}
	m.written += int64(len(p))
	if m.written-m.lastSampled >= 64*1024 {
		m.lastSampled = m.written
		var ms runtime.MemStats
		runtime.ReadMemStats(&ms)
		if ms.HeapAlloc > m.peakHeap {
			m.peakHeap = ms.HeapAlloc
		}
	}
	return len(p), nil
}

func TestWorkspaceArchiveLargeSparseFileStreamsUnder64MB(t *testing.T) {
	dir := setupPersistentWorkspaceForArchiveTest(t)
	sparsePath := filepath.Join(dir, "big-sparse.bin")
	f, err := os.Create(sparsePath)
	if err != nil {
		t.Fatal(err)
	}
	const sparseSize = 200 << 20 // 200 MiB
	if err := f.Truncate(sparseSize); err != nil {
		_ = f.Close()
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}

	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)

	body, _ := json.Marshal(workspaceArchiveRequest{
		ArchiveID:   "arch-large",
		WorkspaceID: "ws-test-1",
		Generation:  1,
	})
	req := httptest.NewRequest(http.MethodPost, "/workspace/archive", bytes.NewReader(body))
	w := &memSamplingResponseWriter{}
	handleWorkspaceArchive(w, req)

	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	peak := w.peakHeap
	if after.HeapAlloc > peak {
		peak = after.HeapAlloc
	}

	if w.status != http.StatusOK {
		t.Fatalf("large archive status = %d, want 200", w.status)
	}
	if w.written == 0 {
		t.Fatalf("large archive wrote 0 bytes")
	}

	const maxPeakBytes = 64 << 20
	t.Logf("200MiB sparse archive streaming: compressedBytes=%d heapBefore=%dKiB heapPeak=%dKiB (limit=65536KiB)",
		w.written, before.HeapAlloc>>10, peak>>10)
	if peak >= maxPeakBytes {
		t.Fatalf("peak heap alloc = %d bytes (>= 64MiB limit)", peak)
	}
}
