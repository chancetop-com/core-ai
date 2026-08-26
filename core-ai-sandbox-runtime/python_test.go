package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// useTempWorkspace points workspaceDir at a temp dir for the test and restores it afterwards.
func useTempWorkspace(t *testing.T) string {
	t.Helper()
	original := workspaceDir
	workspaceDir = t.TempDir()
	t.Cleanup(func() { workspaceDir = original })
	return workspaceDir
}

func writeScript(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("write script: %v", err)
	}
}

func TestExecutePythonInlineCode(t *testing.T) {
	useTempWorkspace(t)

	result, status := executePython(`{"code": "print('from code')"}`)

	if status != "completed" {
		t.Fatalf("status = %q, want completed; result: %s", status, result)
	}
	if strings.TrimSpace(result) != "from code" {
		t.Fatalf("result = %q, want 'from code'", result)
	}
}

func TestExecutePythonScriptPathAbsolute(t *testing.T) {
	ws := useTempWorkspace(t)
	script := filepath.Join(ws, "build.py")
	writeScript(t, script, "print('from file')\n")

	result, status := executePython(`{"script_path": "` + script + `"}`)

	if status != "completed" {
		t.Fatalf("status = %q, want completed; result: %s", status, result)
	}
	if strings.TrimSpace(result) != "from file" {
		t.Fatalf("result = %q, want 'from file'", result)
	}
}

func TestExecutePythonScriptPathRelativeToWorkspace(t *testing.T) {
	ws := useTempWorkspace(t)
	writeScript(t, filepath.Join(ws, "scripts", "rel.py"), "print('relative')\n")

	result, status := executePython(`{"script_path": "scripts/rel.py"}`)

	if status != "completed" {
		t.Fatalf("status = %q, want completed; result: %s", status, result)
	}
	if strings.TrimSpace(result) != "relative" {
		t.Fatalf("result = %q, want 'relative'", result)
	}
}

func TestExecutePythonCodeTakesPrecedenceOverScriptPath(t *testing.T) {
	ws := useTempWorkspace(t)
	script := filepath.Join(ws, "ignored.py")
	writeScript(t, script, "print('from file')\n")

	result, status := executePython(`{"code": "print('from code')", "script_path": "` + script + `"}`)

	if status != "completed" {
		t.Fatalf("status = %q, want completed; result: %s", status, result)
	}
	if strings.TrimSpace(result) != "from code" {
		t.Fatalf("result = %q, want 'from code'", result)
	}
}

func TestExecutePythonScriptPathNotFound(t *testing.T) {
	useTempWorkspace(t)

	result, status := executePython(`{"script_path": "missing/nowhere.py"}`)

	if status != "failed" {
		t.Fatalf("status = %q, want failed; result: %s", status, result)
	}
	if !strings.Contains(result, "does not exist") || !strings.Contains(result, "nowhere.py") {
		t.Fatalf("result = %q, want message naming the missing script", result)
	}
}

func TestExecutePythonScriptPathNonZeroExit(t *testing.T) {
	ws := useTempWorkspace(t)
	script := filepath.Join(ws, "boom.py")
	writeScript(t, script, "import sys\nprint('before')\nsys.exit(3)\n")

	result, status := executePython(`{"script_path": "` + script + `"}`)

	if status != "completed" {
		t.Fatalf("status = %q, want completed (same contract as inline code); result: %s", status, result)
	}
	if !strings.Contains(result, "before") || !strings.Contains(result, "exit status 3") {
		t.Fatalf("result = %q, want captured output plus exit status", result)
	}
}

func TestExecutePythonMissingCodeAndScriptPath(t *testing.T) {
	useTempWorkspace(t)

	result, status := executePython(`{"async": false}`)

	if status != "failed" {
		t.Fatalf("status = %q, want failed; result: %s", status, result)
	}
	if !strings.Contains(result, "'code'") || !strings.Contains(result, "'script_path'") {
		t.Fatalf("result = %q, want message naming both accepted parameters", result)
	}
}
