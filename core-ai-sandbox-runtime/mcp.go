package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// ---- MCP Process Manager ----

type McpStartRequest struct {
	ID      string            `json:"id"`
	Command string            `json:"command"`
	Args    []string          `json:"args"`
	Env     map[string]string `json:"env,omitempty"`
}

type McpStartResponse struct {
	ID      string `json:"id"`
	Status  string `json:"status"` // running, error
	PID     int    `json:"pid,omitempty"`
	Error   string `json:"error,omitempty"`
}

type McpServerProcess struct {
	ID         string
	Config     McpStartRequest
	Cmd        *exec.Cmd
	stdin      io.WriteCloser
	stdout     *bufio.Reader
	stdoutFile *os.File       // for SetReadDeadline (enables true read timeout)
	mu         sync.Mutex    // serialises concurrent JSON-RPC exchanges
	started    time.Time
}

type McpProcessManager struct {
	mu      sync.RWMutex
	servers map[string]*McpServerProcess
}

var mcpManager = &McpProcessManager{
	servers: make(map[string]*McpServerProcess),
}

// crashDetectWindow is how long Start blocks to catch an immediate crash before
// returning success to the caller. Kept short so HTTP responses don't stall —
// longer survival monitoring is asynchronous (see exitWatcher).
const crashDetectWindow = 3 * time.Second

func (m *McpProcessManager) Start(req McpStartRequest) (*McpServerProcess, error) {
	proc, err := m.startOnce(req)
	if err != nil {
		return nil, err
	}

	exitCh := make(chan error, 1)
	go func() {
		exitCh <- proc.Cmd.Wait()
	}()

	select {
	case waitErr := <-exitCh:
		m.mu.Lock()
		delete(m.servers, req.ID)
		m.mu.Unlock()
		if waitErr != nil {
			return nil, fmt.Errorf("mcp server exited immediately on startup: %w", waitErr)
		}
		return nil, fmt.Errorf("mcp server exited cleanly on startup before serving any request")
	case <-time.After(crashDetectWindow):
		// Process survived the short crash-detection window. Hand off to the
		// async watcher so the HTTP response can return now.
		go m.exitWatcher(req.ID, exitCh)
		return proc, nil
	}
}

func (m *McpProcessManager) exitWatcher(id string, exitCh <-chan error) {
	err := <-exitCh
	m.mu.Lock()
	delete(m.servers, id)
	m.mu.Unlock()
	if err != nil {
		log.Printf("[mcp:%s] process exited: %v", id, err)
	} else {
		log.Printf("[mcp:%s] process exited cleanly", id)
	}
}

func (m *McpProcessManager) startOnce(req McpStartRequest) (*McpServerProcess, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.servers[req.ID]; exists {
		return nil, fmt.Errorf("mcp server already running: %s", req.ID)
	}

	cmd := exec.Command(req.Command, req.Args...)
	cmd.Env = buildMcpEnv(req.Env)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("stdout pipe: %w", err)
	}
	stdoutFile, _ := stdout.(*os.File) // for SetReadDeadline support
	// capture stderr for debugging
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("stderr pipe: %w", err)
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start command: %w", err)
	}

	proc := &McpServerProcess{
		ID:         req.ID,
		Config:     req,
		Cmd:        cmd,
		stdin:      stdin,
		stdout:     bufio.NewReader(stdout),
		stdoutFile: stdoutFile,
		started:    time.Now(),
	}

	// log stderr in background
	go func() {
		scanner := bufio.NewScanner(stderr)
		for scanner.Scan() {
			log.Printf("[mcp:%s stderr] %s", req.ID, scanner.Text())
		}
	}()

	m.servers[req.ID] = proc
	log.Printf("[mcp:%s] started: command=%s args=%v pid=%d", req.ID, req.Command, req.Args, cmd.Process.Pid)
	return proc, nil
}

func (m *McpProcessManager) Stop(id string) error {
	m.mu.Lock()
	proc, exists := m.servers[id]
	if !exists {
		m.mu.Unlock()
		return fmt.Errorf("mcp server not found: %s", id)
	}
	delete(m.servers, id)
	m.mu.Unlock()

	if proc.stdin != nil {
		proc.stdin.Close()
	}
	if proc.Cmd != nil && proc.Cmd.Process != nil {
		proc.Cmd.Process.Kill()
	}
	log.Printf("[mcp:%s] stopped", id)
	return nil
}

func (m *McpProcessManager) Get(id string) *McpServerProcess {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.servers[id]
}

func (m *McpProcessManager) List() []McpStartResponse {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]McpStartResponse, 0, len(m.servers))
	for _, p := range m.servers {
		result = append(result, McpStartResponse{
			ID:     p.ID,
			Status: "running",
			PID:    p.Cmd.Process.Pid,
		})
	}
	return result
}

type readResult struct {
	line string
	err  error
}

// SendJSONRPC writes a JSON-RPC request to the process stdin and reads a
// JSON-RPC response from stdout. It matches responses by the JSON-RPC id.
// Notifications (messages without id) from the server are skipped.
//
// A real read deadline is set on the underlying stdout file descriptor so that
// ReadString can be interrupted when the timeout fires. This is much cleaner
// than a goroutine+channel approach which would leak goroutines (the blocked
// ReadString can not be woken up) and cause data races on the shared bufio.Reader.
func (p *McpServerProcess) SendJSONRPC(requestJSON []byte, timeout time.Duration) ([]byte, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	// extract request id to match response
	reqID := extractJSONRPCID(requestJSON)

	// write request to stdin (MCP line-delimited JSON)
	if _, err := p.stdin.Write(requestJSON); err != nil {
		return nil, fmt.Errorf("write to stdin: %w", err)
	}
	if _, err := p.stdin.Write([]byte("\n")); err != nil {
		return nil, fmt.Errorf("write newline to stdin: %w", err)
	}

	// set a real read deadline on the underlying pipe so ReadString will
	// unblock when the timeout fires. Clear it on return.
	if p.stdoutFile != nil {
		p.stdoutFile.SetReadDeadline(time.Now().Add(timeout))
		defer p.stdoutFile.SetReadDeadline(time.Time{})
	}

	// read responses until we find one matching our request id
	for {
		line, err := p.stdout.ReadString('\n')
		if err != nil {
			// A timeout from SetReadDeadline surfaces as an os.Timeout error.
			if os.IsTimeout(err) {
				return nil, fmt.Errorf("timeout waiting for response (id=%s)", reqID)
			}
			return nil, fmt.Errorf("read from stdout: %w", err)
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		// check if this line matches our request id
		respID := extractJSONRPCID([]byte(line))
		if respID == reqID {
			return []byte(line), nil
		}
		// notification or mismatched response — log and skip
		log.Printf("[mcp:%s] skipping non-matching message: id=%s (waiting for %s)", p.ID, respID, reqID)
	}
}

// ---- HTTP Handlers ----

// POST /mcp/start
func handleMcpStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req McpStartRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeMcpJSON(w, http.StatusBadRequest, McpStartResponse{Status: "error", Error: "invalid request: " + err.Error()})
		return
	}
	if req.ID == "" || req.Command == "" {
		writeMcpJSON(w, http.StatusBadRequest, McpStartResponse{Status: "error", Error: "id and command are required"})
		return
	}

	proc, err := mcpManager.Start(req)
	if err != nil {
		writeMcpJSON(w, http.StatusConflict, McpStartResponse{ID: req.ID, Status: "error", Error: err.Error()})
		return
	}

	writeMcpJSON(w, http.StatusOK, McpStartResponse{
		ID:     proc.ID,
		Status: "running",
		PID:    proc.Cmd.Process.Pid,
	})
}

// handleMcp dispatches /mcp by HTTP method:
//
//	GET  — list running MCP servers
//	POST — MCP JSON-RPC bridge (X-Mcp-Server-Id header identifies target process)
func handleMcp(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeMcpJSON(w, http.StatusOK, map[string]interface{}{"servers": mcpManager.List()})
	case http.MethodPost:
		handleMcpBridge(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// handleMcpBridge forwards an MCP JSON-RPC request to a child process's stdin
// and returns the response from its stdout. The target process is identified by
// the X-Mcp-Server-Id header.
func handleMcpBridge(w http.ResponseWriter, r *http.Request) {
	serverID := r.Header.Get("X-Mcp-Server-Id")
	if serverID == "" {
		writeMcpJSON(w, http.StatusBadRequest, map[string]string{"error": "X-Mcp-Server-Id header is required"})
		return
	}

	proc := mcpManager.Get(serverID)
	if proc == nil {
		writeMcpJSON(w, http.StatusNotFound, map[string]string{"error": "mcp server not found: " + serverID})
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeMcpJSON(w, http.StatusBadRequest, map[string]string{"error": "failed to read body: " + err.Error()})
		return
	}

	response, err := proc.SendJSONRPC(body, 120*time.Second)
	if err != nil {
		log.Printf("[mcp:%s] bridge error: %v", serverID, err)
		writeMcpJSON(w, http.StatusBadGateway, map[string]string{"error": "mcp bridge failed: " + err.Error()})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.Write(response)
}

// POST /mcp/stop — stop an MCP server process.
// Request body: {"id": "server-id"}
func handleMcpStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeMcpJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request: " + err.Error()})
		return
	}
	if req.ID == "" {
		writeMcpJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
		return
	}

	if err := mcpManager.Stop(req.ID); err != nil {
		writeMcpJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	}

	writeMcpJSON(w, http.StatusOK, map[string]string{"status": "stopped", "id": req.ID})
}

// ---- Helpers ----

func buildMcpEnv(customEnv map[string]string) []string {
	// inherit sandbox environment
	parent := minimalEnv()
	if len(customEnv) > 0 {
		envMap := make(map[string]string)
		for _, e := range parent {
			key, val, found := strings.Cut(e, "=")
			if found {
				envMap[key] = val
			}
		}
		for k, v := range customEnv {
			envMap[k] = v
		}
		result := make([]string, 0, len(envMap))
		for k, v := range envMap {
			result = append(result, k+"="+v)
		}
		return result
	}
	return parent
}

func extractJSONRPCID(raw []byte) string {
	var msg struct {
		ID json.RawMessage `json:"id"`
	}
	if err := json.Unmarshal(raw, &msg); err != nil {
		return ""
	}
	if len(msg.ID) == 0 || string(msg.ID) == "null" {
		return "" // notification
	}
	// return raw bytes as string (handles both string and number ids)
	return string(msg.ID)
}

func writeMcpJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
