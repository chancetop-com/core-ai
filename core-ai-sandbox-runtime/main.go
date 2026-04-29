package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const maxOutputSize = 30 * 1024        // 30KB
const skillBaseDir = "/skill"           // sandbox-side mount target for materialized skills
const maxSkillArchiveSize = 5 << 20     // 5 MB

// ---- Request / Response ----

type ExecuteRequest struct {
	Tool      string `json:"tool"`
	Arguments string `json:"arguments"`
}

type ExecuteResponse struct {
	Status     string `json:"status"`               // completed, failed, timeout, pending
	Result     string `json:"result"`
	TaskID     string `json:"task_id,omitempty"`
	DurationMs int64  `json:"duration_ms,omitempty"`
}

// ---- Async task registry ----

type AsyncTask struct {
	ID         string
	Tool       string
	Status     string // pending, completed, failed, timeout
	Result     string
	DurationMs int64
	CreatedAt  time.Time
	DoneAt     time.Time
}

type TaskRegistry struct {
	mu            sync.Mutex
	tasks         map[string]*AsyncTask
	running       int32
	maxConcurrent int32
	nextID        atomic.Int64
}

func NewTaskRegistry(maxConcurrent int) *TaskRegistry {
	return &TaskRegistry{
		tasks:         make(map[string]*AsyncTask),
		maxConcurrent: int32(maxConcurrent),
	}
}

func (r *TaskRegistry) Submit(tool string, execFn func() (string, string)) (string, error) {
	if atomic.LoadInt32(&r.running) >= r.maxConcurrent {
		return "", fmt.Errorf("max concurrent async tasks reached (%d)", r.maxConcurrent)
	}

	id := fmt.Sprintf("task-%d", r.nextID.Add(1))
	task := &AsyncTask{
		ID:        id,
		Tool:      tool,
		Status:    "pending",
		CreatedAt: time.Now(),
	}

	r.mu.Lock()
	r.tasks[id] = task
	r.mu.Unlock()

	atomic.AddInt32(&r.running, 1)

	go func() {
		defer atomic.AddInt32(&r.running, -1)
		start := time.Now()
		result, status := execFn()
		task.Result = truncateOutput(result)
		task.Status = status
		task.DurationMs = time.Since(start).Milliseconds()
		task.DoneAt = time.Now()
	}()

	return id, nil
}

func (r *TaskRegistry) Get(id string) *AsyncTask {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.tasks[id]
}

func (r *TaskRegistry) Cleanup(maxAge time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	for id, t := range r.tasks {
		if t.Status != "pending" && now.Sub(t.DoneAt) > maxAge {
			delete(r.tasks, id)
		}
	}
}

// ---- Globals ----

var (
	workspaceDir = "/workspace"
	taskRegistry *TaskRegistry

	skillVersionsMu sync.Mutex
	skillVersions   = make(map[string]string) // name -> installed version
)

// ---- Tool executor type ----

type toolExecutor func(args string) (string, string)

var toolMap = map[string]toolExecutor{
	"run_bash_command":  executeBash,
	"run_python_script": executePython,
	"read_file":         executeReadFile,
	"write_file":        executeWriteFile,
	"edit_file":         executeEditFile,
	"glob_file":         executeGlob,
	"grep_file":         executeGrep,
}

// ---- Main ----

func main() {
	port := envOrDefault("PORT", "8080")
	if ws := os.Getenv("WORKSPACE_DIR"); ws != "" {
		workspaceDir = ws
	}
	maxAsync, _ := strconv.Atoi(envOrDefault("MAX_ASYNC_TASKS", "5"))
	taskRegistry = NewTaskRegistry(maxAsync)

	// Periodic cleanup of completed tasks older than 30 minutes
	go func() {
		for range time.Tick(5 * time.Minute) {
			taskRegistry.Cleanup(30 * time.Minute)
		}
	}()

	if err := os.MkdirAll(skillBaseDir, 0755); err != nil {
		log.Printf("warning: failed to create skill base dir %s: %v", skillBaseDir, err)
	}

	http.HandleFunc("/health", handleHealth)
	http.HandleFunc("/execute", handleExecute)
	http.HandleFunc("/tasks/", handleTaskPoll)
	http.HandleFunc("/skills/", handleSkillMaterialize)

	log.Printf("core-ai-sandbox-runtime starting on :%s, workspace=%s, maxAsync=%d", port, workspaceDir, maxAsync)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatalf("server failed: %v", err)
	}
}

// ---- Handlers ----

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"status":"ok"}`))
}

func handleExecute(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req ExecuteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, ExecuteResponse{Status: "failed", Result: "invalid request: " + err.Error()})
		return
	}

	executor, ok := toolMap[req.Tool]
	if !ok {
		writeJSON(w, ExecuteResponse{Status: "failed", Result: "unsupported tool: " + req.Tool})
		return
	}

	// Check if async mode is requested (parsed from arguments)
	if isAsync(req.Arguments) {
		taskID, err := taskRegistry.Submit(req.Tool, func() (string, string) {
			return executor(req.Arguments)
		})
		if err != nil {
			writeJSON(w, ExecuteResponse{Status: "failed", Result: err.Error()})
			return
		}
		writeJSON(w, ExecuteResponse{Status: "pending", TaskID: taskID, Result: "task submitted: " + taskID})
		return
	}

	start := time.Now()
	result, status := executor(req.Arguments)
	writeJSON(w, ExecuteResponse{
		Status:     status,
		Result:     truncateOutput(result),
		DurationMs: time.Since(start).Milliseconds(),
	})
}

// handleTaskPoll handles GET /tasks/{taskId}
func handleTaskPoll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	taskID := strings.TrimPrefix(r.URL.Path, "/tasks/")
	if taskID == "" {
		writeJSON(w, ExecuteResponse{Status: "failed", Result: "task_id is required"})
		return
	}

	task := taskRegistry.Get(taskID)
	if task == nil {
		writeJSON(w, ExecuteResponse{Status: "failed", Result: "task not found: " + taskID})
		return
	}

	writeJSON(w, ExecuteResponse{
		Status:     task.Status,
		Result:     task.Result,
		TaskID:     task.ID,
		DurationMs: task.DurationMs,
	})
}

// ---- Code execution tools ----

func executeBash(args string) (string, string) {
	var parsed struct {
		Command      string `json:"command"`
		WorkspaceDir string `json:"workspace_dir"`
		Timeout      int    `json:"timeout"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	if parsed.Command == "" {
		return "command is empty", "failed"
	}

	timeout := 30 * time.Second
	if parsed.Timeout > 0 {
		timeout = time.Duration(parsed.Timeout) * time.Second
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, "bash", "-c", parsed.Command)
	cmd.Dir = sanitizePath(parsed.WorkspaceDir, workspaceDir)
	cmd.Env = minimalEnv()

	output, err := cmd.CombinedOutput()
	result := string(output)

	if ctx.Err() == context.DeadlineExceeded {
		return result + "\n... command timed out", "timeout"
	}
	if err != nil {
		return fmt.Sprintf("%s\nexit status: %v", result, err), "completed"
	}
	return result, "completed"
}

func executePython(args string) (string, string) {
	var parsed struct {
		Code         string `json:"code"`
		Script       string `json:"script"`
		WorkspaceDir string `json:"workspace_dir"`
		Timeout      int    `json:"timeout"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}

	code := parsed.Code
	if code == "" {
		code = parsed.Script
	}
	if code == "" {
		return "code is empty", "failed"
	}

	timeout := 60 * time.Second
	if parsed.Timeout > 0 {
		timeout = time.Duration(parsed.Timeout) * time.Second
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, findPython(), "-u", "-c", code)
	cmd.Dir = sanitizePath(parsed.WorkspaceDir, workspaceDir)
	cmd.Env = minimalEnv()

	output, err := cmd.CombinedOutput()
	result := string(output)

	if ctx.Err() == context.DeadlineExceeded {
		return result + "\n... script timed out", "timeout"
	}
	if err != nil {
		return fmt.Sprintf("%s\nexit status: %v", result, err), "completed"
	}
	return result, "completed"
}

// ---- File operation tools ----

func executeReadFile(args string) (string, string) {
	var parsed struct {
		FilePath string `json:"file_path"`
		Offset   int    `json:"offset"`
		Limit    int    `json:"limit"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	if parsed.FilePath == "" {
		return "file_path is required", "failed"
	}

	safePath := resolvePath(parsed.FilePath)

	data, err := os.ReadFile(safePath)
	if err != nil {
		return fmt.Sprintf("error reading file: %v", err), "failed"
	}

	lines := strings.Split(string(data), "\n")

	// Apply line-based offset and limit
	startLine := 0
	if parsed.Offset > 0 {
		startLine = parsed.Offset - 1 // 1-based to 0-based
	}
	if startLine >= len(lines) {
		return "", "completed"
	}

	endLine := len(lines)
	if parsed.Limit > 0 {
		endLine = startLine + parsed.Limit
		if endLine > len(lines) {
			endLine = len(lines)
		}
	}

	// Format with line numbers
	var sb strings.Builder
	for i := startLine; i < endLine; i++ {
		fmt.Fprintf(&sb, "%d | %s\n", i+1, lines[i])
	}
	return sb.String(), "completed"
}

func executeWriteFile(args string) (string, string) {
	var parsed struct {
		FilePath string `json:"file_path"`
		Content  string `json:"content"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	if parsed.FilePath == "" {
		return "file_path is required", "failed"
	}

	safePath := resolvePath(parsed.FilePath)

	if err := os.MkdirAll(filepath.Dir(safePath), 0755); err != nil {
		return fmt.Sprintf("failed to create directory: %v", err), "failed"
	}
	if err := os.WriteFile(safePath, []byte(parsed.Content), 0644); err != nil {
		return fmt.Sprintf("failed to write file: %v", err), "failed"
	}
	return "file written: " + parsed.FilePath, "completed"
}

func executeEditFile(args string) (string, string) {
	var parsed struct {
		FilePath   string `json:"file_path"`
		OldString  string `json:"old_string"`
		NewString  string `json:"new_string"`
		ReplaceAll bool   `json:"replace_all"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	if parsed.FilePath == "" || parsed.OldString == "" {
		return "file_path and old_string are required", "failed"
	}

	safePath := resolvePath(parsed.FilePath)

	data, err := os.ReadFile(safePath)
	if err != nil {
		return fmt.Sprintf("error reading file: %v", err), "failed"
	}

	content := string(data)
	if !strings.Contains(content, parsed.OldString) {
		return "old_string not found in file", "failed"
	}

	var newContent string
	if parsed.ReplaceAll {
		newContent = strings.ReplaceAll(content, parsed.OldString, parsed.NewString)
	} else {
		newContent = strings.Replace(content, parsed.OldString, parsed.NewString, 1)
	}

	if err := os.WriteFile(safePath, []byte(newContent), 0644); err != nil {
		return fmt.Sprintf("failed to write file: %v", err), "failed"
	}
	return "file edited: " + parsed.FilePath, "completed"
}

func executeGlob(args string) (string, string) {
	var parsed struct {
		Pattern string `json:"pattern"`
		Path    string `json:"path"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	if parsed.Pattern == "" {
		return "pattern is required", "failed"
	}

	baseDir := workspaceDir
	if parsed.Path != "" {
		baseDir = resolvePath(parsed.Path)
	}

	var results []string
	filepath.Walk(baseDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(baseDir, path)
		matched, _ := filepath.Match(parsed.Pattern, filepath.Base(path))
		if matched {
			results = append(results, rel)
		}
		if len(results) >= 1000 {
			return io.EOF
		}
		return nil
	})

	return strings.Join(results, "\n"), "completed"
}

func executeGrep(args string) (string, string) {
	var parsed struct {
		Pattern         string `json:"pattern"`
		Path            string `json:"path"`
		Glob            string `json:"glob"`
		OutputMode      string `json:"output_mode"`
		HeadLimit       int    `json:"head_limit"`
		CaseInsensitive bool   `json:"-i"`
	}
	if err := json.Unmarshal([]byte(args), &parsed); err != nil {
		return "failed to parse arguments: " + err.Error(), "failed"
	}
	// Also check the explicit field name
	var rawMap map[string]interface{}
	json.Unmarshal([]byte(args), &rawMap)
	if v, ok := rawMap["-i"]; ok {
		if b, ok := v.(bool); ok {
			parsed.CaseInsensitive = b
		}
	}

	if parsed.Pattern == "" {
		return "pattern is required", "failed"
	}

	baseDir := workspaceDir
	if parsed.Path != "" {
		baseDir = resolvePath(parsed.Path)
	}

	pattern := parsed.Pattern
	if parsed.CaseInsensitive {
		pattern = "(?i)" + pattern
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		return fmt.Sprintf("invalid pattern: %v", err), "failed"
	}

	limit := parsed.HeadLimit
	if limit <= 0 {
		limit = 100
	}
	if parsed.OutputMode == "" {
		parsed.OutputMode = "files_with_matches"
	}

	var results []string
	seen := make(map[string]bool)
	count := 0

	filepath.Walk(baseDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return nil
		}
		// Skip binary/large files
		if info.Size() > 1024*1024 {
			return nil
		}
		// Glob filter
		if parsed.Glob != "" {
			matched, _ := filepath.Match(parsed.Glob, filepath.Base(path))
			if !matched {
				return nil
			}
		}

		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}

		rel, _ := filepath.Rel(baseDir, path)
		lines := strings.Split(string(data), "\n")
		for i, line := range lines {
			if !re.MatchString(line) {
				continue
			}
			switch parsed.OutputMode {
			case "content":
				results = append(results, fmt.Sprintf("%s:%d: %s", rel, i+1, line))
			case "count":
				if !seen[rel] {
					seen[rel] = true
				}
				count++
			default: // files_with_matches
				if !seen[rel] {
					seen[rel] = true
					results = append(results, rel)
				}
			}
			if len(results) >= limit {
				return io.EOF
			}
		}
		return nil
	})

	if parsed.OutputMode == "count" {
		for f := range seen {
			results = append(results, f)
		}
	}

	return strings.Join(results, "\n"), "completed"
}

// ---- Helpers ----

func resolvePath(requested string) string {
	if !filepath.IsAbs(requested) {
		requested = filepath.Join(workspaceDir, requested)
	}
	return filepath.Clean(requested)
}

func sanitizePath(requested, defaultPath string) string {
	if requested == "" {
		return defaultPath
	}
	return filepath.Clean(requested)
}

// ---- Skill materialization ----

// handleSkillMaterialize handles POST /skills/{name} with a zip body.
// Extracts into /skill/{name}/. Version check via X-Skill-Version header:
// if the same version is already installed, skips extraction.
func handleSkillMaterialize(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	name := strings.TrimPrefix(r.URL.Path, "/skills/")
	name = strings.TrimSuffix(name, "/")
	if name == "" || strings.Contains(name, "/") || strings.Contains(name, "..") {
		http.Error(w, "invalid skill name", http.StatusBadRequest)
		return
	}

	version := r.Header.Get("X-Skill-Version")
	if version != "" {
		skillVersionsMu.Lock()
		installed := skillVersions[name]
		skillVersionsMu.Unlock()
		if installed != "" && installed == version {
			w.WriteHeader(http.StatusNoContent)
			return
		}
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxSkillArchiveSize+1))
	if err != nil {
		http.Error(w, "failed to read body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if len(body) > maxSkillArchiveSize {
		http.Error(w, fmt.Sprintf("archive exceeds max size (%d bytes)", maxSkillArchiveSize), http.StatusRequestEntityTooLarge)
		return
	}

	targetDir := filepath.Join(skillBaseDir, name)
	if err := os.RemoveAll(targetDir); err != nil {
		http.Error(w, "failed to clear target dir: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		http.Error(w, "failed to create target dir: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if err := extractZip(body, targetDir); err != nil {
		http.Error(w, "failed to extract archive: "+err.Error(), http.StatusBadRequest)
		return
	}

	skillVersionsMu.Lock()
	skillVersions[name] = version
	skillVersionsMu.Unlock()

	log.Printf("materialized skill: name=%s, version=%s, size=%d, target=%s", name, version, len(body), targetDir)
	w.WriteHeader(http.StatusNoContent)
}

func isExecutableExtension(path string) bool {
	lower := strings.ToLower(path)
	return strings.HasSuffix(lower, ".sh") ||
		strings.HasSuffix(lower, ".py") ||
		strings.HasSuffix(lower, ".pl") ||
		strings.HasSuffix(lower, ".rb") ||
		strings.HasSuffix(lower, ".js")
}

func extractZip(data []byte, targetDir string) error {
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return fmt.Errorf("invalid zip: %w", err)
	}
	for _, f := range reader.File {
		if strings.Contains(f.Name, "..") {
			return fmt.Errorf("invalid entry path: %s", f.Name)
		}
		dest := filepath.Join(targetDir, f.Name)
		cleanDest := filepath.Clean(dest)
		if !strings.HasPrefix(cleanDest, filepath.Clean(targetDir)+string(os.PathSeparator)) && cleanDest != filepath.Clean(targetDir) {
			return fmt.Errorf("entry escapes target dir: %s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(cleanDest, 0755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(cleanDest), 0755); err != nil {
			return err
		}
		mode := os.FileMode(0644)
		if isExecutableExtension(f.Name) {
			mode = 0755
		}
		out, err := os.OpenFile(cleanDest, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
		if err != nil {
			return err
		}
		in, err := f.Open()
		if err != nil {
			out.Close()
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			in.Close()
			out.Close()
			return err
		}
		in.Close()
		if err := out.Close(); err != nil {
			return err
		}
	}
	return nil
}

func minimalEnv() []string {
	return []string{
		"PATH=/usr/local/bin:/usr/bin:/bin",
		"HOME=/tmp",
		"LANG=en_US.UTF-8",
		"PYTHONIOENCODING=utf-8",
		"PYTHONDONTWRITEBYTECODE=1",
	}
}

func findPython() string {
	if _, err := exec.LookPath("python3"); err == nil {
		return "python3"
	}
	return "python"
}

func truncateOutput(output string) string {
	if len(output) > maxOutputSize {
		return output[:maxOutputSize] + "\n... [output truncated at 30KB]"
	}
	return output
}

// isAsync checks if the arguments JSON contains "async": true
func isAsync(args string) bool {
	var raw map[string]interface{}
	if err := json.Unmarshal([]byte(args), &raw); err != nil {
		return false
	}
	v, ok := raw["async"]
	if !ok {
		return false
	}
	b, ok := v.(bool)
	return ok && b
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func writeJSON(w http.ResponseWriter, resp ExecuteResponse) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

