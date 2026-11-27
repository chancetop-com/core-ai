package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestWorkspaceStatReturnsFilesystemMetrics(t *testing.T) {
	previousWorkspace := workspaceDir
	workspaceDir = t.TempDir()
	defer func() { workspaceDir = previousWorkspace }()

	req := httptest.NewRequest(http.MethodGet, "/workspace/stat", nil)
	rec := httptest.NewRecorder()
	handleWorkspaceStat(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body = %s", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", ct)
	}

	var resp WorkspaceStatResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v, body = %s", err, rec.Body.String())
	}
	if resp.CapacityBytes == 0 {
		t.Fatalf("capacity_bytes = 0, want > 0")
	}
	if resp.UsedBytes > resp.CapacityBytes {
		t.Fatalf("used_bytes (%d) > capacity_bytes (%d)", resp.UsedBytes, resp.CapacityBytes)
	}
	if resp.AvailableBytes > resp.CapacityBytes {
		t.Fatalf("available_bytes (%d) > capacity_bytes (%d)", resp.AvailableBytes, resp.CapacityBytes)
	}
	if resp.InodesUsed > resp.InodesTotal {
		t.Fatalf("inodes_used (%d) > inodes_total (%d)", resp.InodesUsed, resp.InodesTotal)
	}
}

func TestWorkspaceStatRejectsNonGet(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/workspace/stat", nil)
	rec := httptest.NewRecorder()
	handleWorkspaceStat(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

func TestWorkspaceStatReturns500WhenDirMissing(t *testing.T) {
	previousWorkspace := workspaceDir
	workspaceDir = filepath.Join(t.TempDir(), "does-not-exist")
	defer func() { workspaceDir = previousWorkspace }()

	req := httptest.NewRequest(http.MethodGet, "/workspace/stat", nil)
	rec := httptest.NewRecorder()
	handleWorkspaceStat(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500, body = %s", rec.Code, rec.Body.String())
	}
}
