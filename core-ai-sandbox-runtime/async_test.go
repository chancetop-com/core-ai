package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestIsAsync(t *testing.T) {
	cases := []struct {
		name string
		args string
		want bool
	}{
		{"async true", `{"async": true}`, true},
		{"async false", `{"async": false}`, false},
		{"run_in_background true", `{"run_in_background": true}`, true},
		{"run_in_background false", `{"run_in_background": false}`, false},
		{"both true", `{"async": true, "run_in_background": true}`, true},
		{"neither", `{"command": "ls"}`, false},
		{"non bool value", `{"async": "true"}`, false},
		{"invalid json", `not json`, false},
		{"empty object", `{}`, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := isAsync(c.args); got != c.want {
				t.Fatalf("isAsync(%q) = %v, want %v", c.args, got, c.want)
			}
		})
	}
}

func TestHandleTaskCancelTerminatesProcessGroupAndSetsCancelled(t *testing.T) {
	oldRegistry := taskRegistry
	oldWorkspace := workspaceDir
	taskRegistry = NewTaskRegistry(5)
	workspaceDir = t.TempDir()
	defer func() {
		taskRegistry = oldRegistry
		workspaceDir = oldWorkspace
	}()

	pidFile := filepath.Join(workspaceDir, "child.pid")
	args, err := json.Marshal(map[string]any{
		"command":           "sleep 60 & echo $! > " + pidFile + "; wait",
		"run_in_background": true,
	})
	if err != nil {
		t.Fatalf("marshal args: %v", err)
	}
	execBody, _ := json.Marshal(ExecuteRequest{Tool: "run_bash_command", Arguments: string(args)})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/execute", strings.NewReader(string(execBody)))
	handleExecute(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("execute status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var submitResp ExecuteResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &submitResp); err != nil {
		t.Fatalf("decode submit response: %v", err)
	}
	if submitResp.Status != "pending" || submitResp.TaskID == "" {
		t.Fatalf("unexpected submit response: %+v", submitResp)
	}

	// wait until child process has written its pid
	var childPid int
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(pidFile)
		if err == nil {
			if pid, err := strconv.Atoi(strings.TrimSpace(string(data))); err == nil && pid > 0 {
				childPid = pid
				break
			}
		}
		time.Sleep(25 * time.Millisecond)
	}
	if childPid == 0 {
		t.Fatalf("child process did not write pid file")
	}

	// cancel via POST /tasks/{id}/cancel
	cancelRec := httptest.NewRecorder()
	cancelReq := httptest.NewRequest(http.MethodPost, "/tasks/"+submitResp.TaskID+"/cancel", nil)
	handleTasks(cancelRec, cancelReq)
	if cancelRec.Code != http.StatusOK {
		t.Fatalf("cancel status = %d, body = %s", cancelRec.Code, cancelRec.Body.String())
	}
	var cancelResp ExecuteResponse
	if err := json.Unmarshal(cancelRec.Body.Bytes(), &cancelResp); err != nil {
		t.Fatalf("decode cancel response: %v", err)
	}
	if cancelResp.Status != "cancelled" {
		t.Fatalf("cancel status = %q, want cancelled", cancelResp.Status)
	}

	// verify GET /tasks/{id} returns cancelled
	pollRec := httptest.NewRecorder()
	pollReq := httptest.NewRequest(http.MethodGet, "/tasks/"+submitResp.TaskID, nil)
	handleTasks(pollRec, pollReq)
	var pollResp ExecuteResponse
	if err := json.Unmarshal(pollRec.Body.Bytes(), &pollResp); err != nil {
		t.Fatalf("decode poll response: %v", err)
	}
	if pollResp.Status != "cancelled" {
		t.Fatalf("poll status after cancel = %q, want cancelled", pollResp.Status)
	}

	// verify child process in the process group is dead (gone or zombie awaiting PID 1 reap)
	dead := false
	for i := 0; i < 40; i++ {
		if isProcessDead(childPid) {
			dead = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !dead {
		_ = syscall.Kill(childPid, syscall.SIGKILL)
		t.Fatalf("child process %d still alive after cancel", childPid)
	}
}

func isProcessDead(pid int) bool {
	if err := syscall.Kill(pid, 0); err != nil {
		return true
	}
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return os.IsNotExist(err)
	}
	// /proc/<pid>/stat format: "<pid> (<comm>) <state> ..."
	if idx := strings.LastIndex(string(data), ")"); idx != -1 && idx+2 < len(data) {
		return data[idx+2] == 'Z' || data[idx+2] == 'X'
	}
	return false
}

func TestHandleTaskCancelMethodAndNotFound(t *testing.T) {
	oldRegistry := taskRegistry
	taskRegistry = NewTaskRegistry(5)
	defer func() { taskRegistry = oldRegistry }()

	getRec := httptest.NewRecorder()
	handleTasks(getRec, httptest.NewRequest(http.MethodGet, "/tasks/task-1/cancel", nil))
	if getRec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /tasks/task-1/cancel status = %d, want 405", getRec.Code)
	}

	notFoundRec := httptest.NewRecorder()
	handleTasks(notFoundRec, httptest.NewRequest(http.MethodPost, "/tasks/unknown-99/cancel", nil))
	if notFoundRec.Code != http.StatusNotFound {
		t.Fatalf("POST unknown task cancel status = %d, want 404", notFoundRec.Code)
	}
}

func TestAsyncMaxBashTimeoutAndRetention(t *testing.T) {
	oldSync := maxBashTimeout
	oldAsync := asyncMaxBashTimeout
	defer func() {
		maxBashTimeout = oldSync
		asyncMaxBashTimeout = oldAsync
	}()

	maxBashTimeout = 10 * time.Minute
	asyncMaxBashTimeout = 2 * time.Hour

	if got := bashTimeoutFor(1_800_000, false); got != 10*time.Minute {
		t.Fatalf("sync timeout = %s, want 10m cap", got)
	}
	if got := bashTimeoutFor(1_800_000, true); got != 30*time.Minute {
		t.Fatalf("async timeout = %s, want 30m", got)
	}
	if got := bashTimeoutFor(9_999_000, true); got != 2*time.Hour {
		t.Fatalf("async timeout cap = %s, want 2h", got)
	}

	reg := NewTaskRegistry(2)
	reg.tasks["recent"] = &AsyncTask{ID: "recent", Status: "completed", DoneAt: time.Now().Add(-90 * time.Minute)}
	reg.tasks["expired"] = &AsyncTask{ID: "expired", Status: "completed", DoneAt: time.Now().Add(-150 * time.Minute)}
	reg.Cleanup(2 * time.Hour)
	if reg.Get("recent") == nil {
		t.Fatal("task completed 90m ago should survive 2h retention")
	}
	if reg.Get("expired") != nil {
		t.Fatal("task completed 150m ago should be pruned after 2h retention")
	}
}
