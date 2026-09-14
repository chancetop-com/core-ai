package main

import (
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// A timed-out bash command whose child still holds the stdout pipe (the Chromium screenshot case)
// must return promptly and leave no orphan behind.
func TestExecuteBashTimeoutKillsChildHoldingOutputPipe(t *testing.T) {
	useTempWorkspace(t)

	start := time.Now()
	result, status := executeBash(`{"command": "sleep 30 & echo child=$!; wait", "timeout": 300}`)
	elapsed := time.Since(start)

	if status != "timeout" {
		t.Fatalf("status = %q, want timeout; result: %s", status, result)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("executeBash blocked for %s after timeout; result: %s", elapsed, result)
	}
	childPid := parseChildPid(t, result)
	waitForProcessExit(t, childPid)
}

func TestExecutePythonTimeoutKillsChildHoldingOutputPipe(t *testing.T) {
	useTempWorkspace(t)

	code := "import subprocess,sys;p=subprocess.Popen(['sleep','30']);print('child=%d'%p.pid,flush=True);p.wait()"
	start := time.Now()
	result, status := executePython(`{"code": "` + code + `", "timeout": 3}`)
	elapsed := time.Since(start)

	if status != "timeout" {
		t.Fatalf("status = %q, want timeout; result: %s", status, result)
	}
	if elapsed > 8*time.Second {
		t.Fatalf("executePython blocked for %s after 3s timeout; result: %s", elapsed, result)
	}
	childPid := parseChildPid(t, result)
	waitForProcessExit(t, childPid)
}

func parseChildPid(t *testing.T, output string) int {
	t.Helper()
	for _, line := range strings.Split(output, "\n") {
		if strings.HasPrefix(line, "child=") {
			pid, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "child=")))
			if err != nil {
				t.Fatalf("bad child pid line %q: %v", line, err)
			}
			return pid
		}
	}
	t.Fatalf("child pid not found in output: %q", output)
	return 0
}

func waitForProcessExit(t *testing.T, pid int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if err := syscall.Kill(pid, 0); err != nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	_ = syscall.Kill(pid, syscall.SIGKILL)
	t.Fatalf("child process %d still alive after timeout", pid)
}
