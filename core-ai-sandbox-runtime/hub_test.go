package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
)

func TestBindLifecycle(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	body := `{"server_url":"http://core-ai-server:8080","token":"cst_abc.def","session_id":"s-1","agent_name":"seo","expires_at":123}`
	record := httptest.NewRecorder()
	handleBind(record, httptest.NewRequest(http.MethodPost, "/bind", strings.NewReader(body)))
	if record.Code != http.StatusNoContent {
		t.Fatalf("POST /bind = %d, want 204", record.Code)
	}
	if !hubBound() {
		t.Fatal("sandbox should report bound after POST /bind")
	}
	if got := currentBinding.Load().SessionID; got != "s-1" {
		t.Fatalf("bound session id = %q, want s-1", got)
	}

	record = httptest.NewRecorder()
	handleBind(record, httptest.NewRequest(http.MethodDelete, "/bind", nil))
	if record.Code != http.StatusNoContent {
		t.Fatalf("DELETE /bind = %d, want 204", record.Code)
	}
	if hubBound() {
		t.Fatal("sandbox should report unbound after DELETE /bind")
	}
}

func TestBindRejectsIncompletePayload(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	record := httptest.NewRecorder()
	handleBind(record, httptest.NewRequest(http.MethodPost, "/bind", strings.NewReader(`{"session_id":"s-1"}`)))
	if record.Code != http.StatusBadRequest {
		t.Fatalf("POST /bind without token = %d, want 400", record.Code)
	}
	if hubBound() {
		t.Fatal("incomplete payload must not replace the binding")
	}
}

func TestHealthReportsBinding(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	read := func() HealthResponse {
		record := httptest.NewRecorder()
		handleHealth(record, httptest.NewRequest(http.MethodGet, "/health", nil))
		var health HealthResponse
		if err := json.Unmarshal(record.Body.Bytes(), &health); err != nil {
			t.Fatalf("unmarshal health: %v", err)
		}
		return health
	}
	if read().Bound {
		t.Fatal("health must report bound=false before any bind")
	}
	currentBinding.Store(&binding{ServerURL: "http://x", Token: "t", SessionID: "s"})
	health := read()
	if !health.Bound || health.RuntimeVersion == "" {
		t.Fatalf("health = %+v, want bound=true and a runtime version", health)
	}
}

func TestHubProxyRequiresBinding(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	record := httptest.NewRecorder()
	handleHubProxy(newHubReverseProxy())(record, httptest.NewRequest(http.MethodGet, "/hub/me", nil))
	if record.Code != http.StatusServiceUnavailable {
		t.Fatalf("unbound /hub/me = %d, want 503", record.Code)
	}
	if !strings.Contains(record.Body.String(), "not_bound") {
		t.Fatalf("unbound body = %q, want a not_bound error", record.Body.String())
	}
}

func TestHubProxyRewritesRequest(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	type observed struct {
		path          string
		query         string
		authorization string
		client        string
		sdk           string
		body          string
	}
	var seen observed
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		payload, _ := io.ReadAll(r.Body)
		seen = observed{
			path:          r.URL.Path,
			query:         r.URL.RawQuery,
			authorization: r.Header.Get("Authorization"),
			client:        r.Header.Get("X-Core-AI-Client"),
			sdk:           r.Header.Get("X-Core-AI-Sdk"),
			body:          string(payload),
		}
		w.Write([]byte(`{"ok":true}`))
	}))
	defer server.Close()

	currentBinding.Store(&binding{ServerURL: server.URL, Token: "cst_token.sig", SessionID: "s-1"})
	request := httptest.NewRequest(http.MethodPost, "/hub/tools/kie/upload_file/call?x=1",
		strings.NewReader(`{"arguments":"{}"}`))
	// a script must not be able to choose the credential or the client identity
	request.Header.Set("Authorization", "Bearer cst_stolen.token")
	request.Header.Set("X-Core-AI-Client", "sdk-python/1.2.3")
	record := httptest.NewRecorder()
	handleHubProxy(newHubReverseProxy())(record, request)

	if record.Code != http.StatusOK {
		t.Fatalf("proxied call = %d, want 200", record.Code)
	}
	if seen.path != "/api/sandbox-hub/tools/kie/upload_file/call" || seen.query != "x=1" {
		t.Fatalf("upstream path = %q query = %q", seen.path, seen.query)
	}
	if seen.authorization != "Bearer cst_token.sig" {
		t.Fatalf("upstream authorization = %q, want the bound token", seen.authorization)
	}
	if seen.client != "sandbox" || seen.sdk != "sdk-python/1.2.3" {
		t.Fatalf("upstream client = %q sdk = %q", seen.client, seen.sdk)
	}
	if seen.body != `{"arguments":"{}"}` {
		t.Fatalf("upstream body = %q", seen.body)
	}
}

func TestHubProxyRejectsOtherMethods(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	record := httptest.NewRecorder()
	handleHubProxy(newHubReverseProxy())(record, httptest.NewRequest(http.MethodDelete, "/hub/me", nil))
	if record.Code != http.StatusMethodNotAllowed {
		t.Fatalf("DELETE /hub/me = %d, want 405", record.Code)
	}
}

func TestMinimalEnvAdvertisesLoopbackHub(t *testing.T) {
	currentBinding.Store(nil)
	defer currentBinding.Store(nil)

	env := envMap(minimalEnv())
	if env["CORE_AI_HUB"] != "http://127.0.0.1:8081/hub" {
		t.Fatalf("CORE_AI_HUB = %q", env["CORE_AI_HUB"])
	}
	if _, ok := env["CORE_AI_SESSION_ID"]; ok {
		t.Fatal("session id must not be exposed before binding")
	}
	currentBinding.Store(&binding{ServerURL: "http://x", Token: "t", SessionID: "s-1", AgentName: "seo"})
	env = envMap(minimalEnv())
	if env["CORE_AI_SESSION_ID"] != "s-1" || env["CORE_AI_AGENT_NAME"] != "seo" {
		t.Fatalf("bound env = %q/%q", env["CORE_AI_SESSION_ID"], env["CORE_AI_AGENT_NAME"])
	}
}

func envMap(env []string) map[string]string {
	values := map[string]string{}
	for _, entry := range env {
		if name, value, found := strings.Cut(entry, "="); found {
			values[name] = value
		}
	}
	return values
}

// ---------- CLI ----------

type hubStub struct {
	requests []string
	handler  func(w http.ResponseWriter, r *http.Request)
}

func (stub *hubStub) start(t *testing.T) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		stub.requests = append(stub.requests, r.Method+" "+r.URL.RequestURI())
		if stub.handler != nil {
			stub.handler(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{}`))
	}))
	t.Cleanup(server.Close)
	t.Setenv("CORE_AI_HUB", server.URL)
}

func runCLI(t *testing.T, args ...string) (int, string, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := runCli(args, &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

func TestCliCatalogJSON(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"session_id":"s-1","agent_name":"seo","sandbox_id":"sbx","sandbox_state":"ready",` +
			`"groups":[{"kind":"mcp","group":"kie","count":1}],` +
			`"tools":[{"name":"kie/upload_file","kind":"mcp","group":"kie","description":"Upload a file"}]}`))
	}}
	stub.start(t)

	code, stdout, _ := runCLI(t, "catalog", "--json")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0", code)
	}
	var catalog hubCatalogResponse
	if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &catalog); err != nil {
		t.Fatalf("stdout is not one json line: %q (%v)", stdout, err)
	}
	if catalog.SessionID != "s-1" || len(catalog.Tools) != 1 {
		t.Fatalf("catalog = %+v", catalog)
	}
	if stub.requests[0] != "GET /catalog" {
		t.Fatalf("request = %q", stub.requests[0])
	}
}

func TestCliCatalogHumanOutput(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"session_id":"s-1","agent_name":"seo","sandbox_id":"sbx","sandbox_state":"ready",` +
			`"tools":[{"name":"kie/upload_file","kind":"mcp","description":"Upload a file"},` +
			`{"name":"kb.search","kind":"api","path":"GET /kb/search","description":"Search"}]}`))
	}}
	stub.start(t)

	code, stdout, _ := runCLI(t, "catalog")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0", code)
	}
	for _, expected := range []string{"mcp (1 tools)", "api (1 tools)", "kie/upload_file", "[GET /kb/search]"} {
		if !strings.Contains(stdout, expected) {
			t.Fatalf("catalog output missing %q:\n%s", expected, stdout)
		}
	}
}

func TestCliToolsSearchPassesFilters(t *testing.T) {
	stub := &hubStub{}
	stub.start(t)

	code, _, _ := runCLI(t, "tools", "upload", "--kind", "mcp", "--json")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0", code)
	}
	if stub.requests[0] != "GET /tools?kind=mcp&query=upload" {
		t.Fatalf("request = %q", stub.requests[0])
	}
}

func TestCliCallSuccessAndToolError(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "ok") {
			w.Write([]byte(`{"call_id":"c1","status":"completed","success":true,"text":"done","duration_ms":12}`))
			return
		}
		w.Write([]byte(`{"call_id":"c2","status":"completed","success":false,"is_error":true,` +
			`"text":"boom","error_message":"tool exploded","duration_ms":7}`))
	}}
	stub.start(t)

	code, stdout, _ := runCLI(t, "call", "ok", "--raw")
	if code != exitSuccess || stdout != "done\n" {
		t.Fatalf("raw call = (%d, %q)", code, stdout)
	}

	code, stdout, stderr := runCLI(t, "call", "bad", "--json")
	if code != exitToolError {
		t.Fatalf("failing call exit = %d, want 1", code)
	}
	if !strings.Contains(stdout, `"is_error":true`) || !strings.Contains(stdout, "tool exploded") {
		t.Fatalf("failing call stdout = %q", stdout)
	}
	// --json keeps stdout to a single parseable line, so nothing else is written to it
	if lines := strings.Count(strings.TrimSpace(stdout), "\n"); lines != 0 {
		t.Fatalf("json mode printed %d extra lines: %q", lines, stdout)
	}
	if !strings.Contains(stderr, "calling bad") {
		t.Fatalf("failing call stderr = %q", stderr)
	}

	// human mode prints the tool text and explains the failure on stderr
	code, stdout, stderr = runCLI(t, "call", "bad")
	if code != exitToolError || !strings.Contains(stdout, "boom") {
		t.Fatalf("failing human call = (%d, %q)", code, stdout)
	}
	if !strings.Contains(stderr, "tool exploded") {
		t.Fatalf("failing human call stderr = %q", stderr)
	}
}

func TestCliCallPollsPendingTask(t *testing.T) {
	calls := 0
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls == 1 {
			w.Write([]byte(`{"call_id":"c1","status":"pending","task_id":"t1"}`))
			return
		}
		w.Write([]byte(`{"call_id":"c1","status":"completed","success":true,"text":"finished","duration_ms":1500}`))
	}}
	stub.start(t)

	code, stdout, _ := runCLI(t, "call", "slow", "--raw", "--timeout", "30")
	if code != exitSuccess || stdout != "finished\n" {
		t.Fatalf("polled call = (%d, %q)", code, stdout)
	}
	if stub.requests[1] != "GET /tasks/t1" {
		t.Fatalf("poll request = %q", stub.requests[1])
	}
}

func TestCliExitCodesFromHTTPStatus(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/tools/gone":
			w.WriteHeader(http.StatusNotFound)
			w.Write([]byte(`{"error":"tool_not_found","message":"no such tool"}`))
		case "/me":
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"error":"not_bound","message":"this sandbox is not bound to a session yet"}`))
		default:
			w.WriteHeader(http.StatusForbidden)
			w.Write([]byte(`{"error":"forbidden","message":"nope"}`))
		}
	}}
	stub.start(t)

	if code, _, _ := runCLI(t, "describe", "gone"); code != exitNotFound {
		t.Fatalf("404 exit = %d, want 5", code)
	}
	if code, _, _ := runCLI(t, "me", "--json"); code != exitUnauthenticated {
		t.Fatalf("not_bound exit = %d, want 3", code)
	}
	if code, _, _ := runCLI(t, "describe", "other"); code != exitForbidden {
		t.Fatalf("403 exit = %d, want 4", code)
	}
}

func TestCliNotBoundIsRetried(t *testing.T) {
	attempts := 0
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts < 2 {
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"error":"not_bound","message":"this sandbox is not bound to a session yet"}`))
			return
		}
		w.Write([]byte(`{"session_id":"s-1","tool_count":3}`))
	}}
	stub.start(t)

	code, stdout, _ := runCLI(t, "me", "--json")
	if code != exitSuccess {
		t.Fatalf("retried call exit = %d, want 0", code)
	}
	if !strings.Contains(stdout, `"tool_count":3`) {
		t.Fatalf("retried call stdout = %q", stdout)
	}
}

func TestCliUsageErrors(t *testing.T) {
	stub := &hubStub{}
	stub.start(t)

	if code, _, _ := runCLI(t, "nope"); code != exitUsage {
		t.Fatalf("unknown command exit = %d, want 2", code)
	}
	if code, _, _ := runCLI(t, "call"); code != exitUsage {
		t.Fatalf("call without a tool exit = %d, want 2", code)
	}
	if code, _, _ := runCLI(t, "call", "t", "--arg", "novalue"); code != exitUsage {
		t.Fatalf("malformed --arg exit = %d, want 2", code)
	}
	if code, _, _ := runCLI(t, "describe", "a", "b"); code != exitUsage {
		t.Fatalf("describe with two names exit = %d, want 2", code)
	}
	if len(stub.requests) != 0 {
		t.Fatalf("usage errors must not reach the hub, got %v", stub.requests)
	}
}

func TestCliHelpPrintsUsageWithoutTouchingTheHub(t *testing.T) {
	stub := &hubStub{}
	stub.start(t)

	for _, args := range [][]string{{"--help"}, {"-h"}, {"call", "--help"}, {"call", "some_tool", "-h"}} {
		code, stdout, _ := runCLI(t, args...)
		if code != exitSuccess {
			t.Fatalf("args %v exit = %d, want 0", args, code)
		}
		if !strings.Contains(stdout, "core-ai-sandbox call <tool> [options]") {
			t.Fatalf("args %v did not print the usage:\n%s", args, stdout)
		}
	}
	if len(stub.requests) != 0 {
		t.Fatalf("help must not reach the hub, got %v", stub.requests)
	}
}

func TestCliRejectsUnknownKind(t *testing.T) {
	stub := &hubStub{}
	stub.start(t)

	code, _, stderr := runCLI(t, "tools", "--kind", "bogus")
	if code != exitUsage {
		t.Fatalf("unknown --kind exit = %d, want 2", code)
	}
	if !strings.Contains(stderr, "--kind must be one of mcp|api|llm_call|agent|builtin") {
		t.Fatalf("unknown --kind stderr = %q", stderr)
	}
	if len(stub.requests) != 0 {
		t.Fatalf("rejected --kind must not reach the hub, got %v", stub.requests)
	}
}

func TestCliArgCoercionUsesSchema(t *testing.T) {
	schema := `{"properties":{"limit":{"type":"integer"},"flag":{"type":"boolean"},` +
		`"query":{"type":"string"},"filters":{"type":"object"}}}`
	var received string
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			fmt.Fprintf(w, `{"name":"t","kind":"mcp","input_schema":%s}`, strconv.Quote(schema))
			return
		}
		payload, _ := io.ReadAll(r.Body)
		var request struct {
			Arguments string `json:"arguments"`
		}
		json.Unmarshal(payload, &request)
		received = request.Arguments
		w.Write([]byte(`{"call_id":"c1","status":"completed","success":true,"text":"ok"}`))
	}}
	stub.start(t)

	code, _, _ := runCLI(t, "call", "t", "--arg", "limit=10", "--arg", "flag=true",
		"--arg", "query=hello", "--arg", `filters={"a":1}`, "--arg", "raw=7")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0", code)
	}
	if received != `{"filters":{"a":1},"flag":true,"limit":10,"query":"hello","raw":"7"}` {
		t.Fatalf("arguments = %s", received)
	}
}

func TestCliArgsFileStdinWinsOverArgs(t *testing.T) {
	var received string
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		payload, _ := io.ReadAll(r.Body)
		var request struct {
			Arguments string `json:"arguments"`
		}
		json.Unmarshal(payload, &request)
		received = request.Arguments
		w.Write([]byte(`{"call_id":"c1","status":"completed","success":true,"text":"ok"}`))
	}}
	stub.start(t)

	file := t.TempDir() + "/args.json"
	if err := os.WriteFile(file, []byte(`{"b":"file","c":3}`), 0o600); err != nil {
		t.Fatalf("write args file: %v", err)
	}
	code, _, _ := runCLI(t, "call", "t", "--args", `{"a":1,"b":"args"}`, "--args-file", file)
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0", code)
	}
	if received != `{"a":1,"b":"file","c":3}` {
		t.Fatalf("arguments = %s", received)
	}
}

func TestCliMaxOutputTruncates(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"call_id":"c1","status":"completed","success":true,"text":"0123456789"}`))
	}}
	stub.start(t)

	code, stdout, stderr := runCLI(t, "call", "t", "--raw", "--max-output", "4")
	if code != exitSuccess || stdout != "0123\n" {
		t.Fatalf("truncated call = (%d, %q)", code, stdout)
	}
	if !strings.Contains(stderr, "truncated") {
		t.Fatalf("stderr = %q, want a truncation warning", stderr)
	}
}
