package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// ../sdk/core-ai-session/contract-fixtures is the single source of truth for the hub wire format: the Python SDK
// (../sdk/core-ai-session/tests) and this CLI are both tested against the same bytes, so a one-sided change
// to a field name or status value fails one of the two suites.
const contractFixtureDir = "../sdk/core-ai-session/contract-fixtures"

func contractFixture(t *testing.T, name string) []byte {
	t.Helper()
	payload, err := os.ReadFile(filepath.Join(contractFixtureDir, name))
	if err != nil {
		t.Fatalf("read contract fixture %s: %v", name, err)
	}
	return payload
}

func serveContractFixture(t *testing.T, w http.ResponseWriter, name string) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	w.Write(contractFixture(t, name))
}

func TestCliConsumesSharedContractFixtures(t *testing.T) {
	var callBody string
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/me":
			serveContractFixture(t, w, "me.json")
		case r.URL.Path == "/catalog":
			serveContractFixture(t, w, "catalog.json")
		case r.URL.Path == "/tools" && r.Method == http.MethodGet:
			serveContractFixture(t, w, "tools.json")
		case r.URL.Path == "/tools/google_gbp_list_reviews" && r.Method == http.MethodGet:
			serveContractFixture(t, w, "tool-detail.json")
		case r.URL.Path == "/tools/google_gbp_list_reviews/call":
			payload, _ := io.ReadAll(r.Body)
			callBody = string(payload)
			serveContractFixture(t, w, "call-success.json")
		case r.URL.Path == "/tools/google_gbp_reply_review/call":
			serveContractFixture(t, w, "call-error.json")
		case r.URL.Path == "/tools/review_responder/call":
			serveContractFixture(t, w, "pending-call.json")
		case strings.HasPrefix(r.URL.Path, "/tasks/"):
			if r.URL.Path != "/tasks/task-4f2a1b7c" {
				t.Errorf("polled an unexpected task id: %s", r.URL.Path)
			}
			serveContractFixture(t, w, "task-completed.json")
		default:
			t.Errorf("unexpected hub request: %s %s", r.Method, r.URL.Path)
			serveContractFixture(t, w, "forbidden.json")
		}
	}}
	stub.start(t)

	t.Run("me", func(t *testing.T) {
		code, stdout, _ := runCLI(t, "me", "--json")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var me hubMeResponse
		if err := json.Unmarshal([]byte(stdout), &me); err != nil {
			t.Fatalf("me stdout is not one json line: %q (%v)", stdout, err)
		}
		if me.SessionID == "" || me.AgentName == "" || me.SandboxState != "ready" {
			t.Fatalf("me = %+v", me)
		}
		if me.ToolCount != 9 || me.ContractVersion != "1.0" {
			t.Fatalf("me tool_count/contract_version = %d/%q, want 9/1.0", me.ToolCount, me.ContractVersion)
		}
	})

	t.Run("catalog", func(t *testing.T) {
		code, stdout, _ := runCLI(t, "catalog", "--json")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var catalog hubCatalogResponse
		if err := json.Unmarshal([]byte(stdout), &catalog); err != nil {
			t.Fatalf("catalog stdout is not one json line: %q (%v)", stdout, err)
		}
		// the fixture carries one hidden tool to pin the "never expose it" rule on both clients
		if len(catalog.Tools) != 8 || len(catalog.Groups) == 0 {
			t.Fatalf("catalog = %d tools / %d groups, want 8 / >0", len(catalog.Tools), len(catalog.Groups))
		}
		for _, tool := range catalog.Tools {
			if tool.Exposure == "hidden" {
				t.Fatalf("catalog leaked a hidden tool: %+v", tool)
			}
		}
		if catalog.ExpiresAt != "2026-09-15T11:30:00Z" {
			t.Fatalf("catalog expires_at = %q", catalog.ExpiresAt)
		}

		code, human, _ := runCLI(t, "catalog")
		if code != exitSuccess {
			t.Fatalf("human exit = %d, want 0", code)
		}
		for _, expected := range []string{"mcp (3 tools)", "llm_call (1 tools)", "google_gbp_list_reviews"} {
			if !strings.Contains(human, expected) {
				t.Fatalf("catalog output missing %q:\n%s", expected, human)
			}
		}
		if strings.Contains(human, "activate_tools") {
			t.Fatalf("catalog output listed a hidden tool:\n%s", human)
		}
	})

	t.Run("tools", func(t *testing.T) {
		code, stdout, _ := runCLI(t, "tools", "--json")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var tools hubToolsResponse
		if err := json.Unmarshal([]byte(stdout), &tools); err != nil {
			t.Fatalf("tools stdout is not one json line: %q (%v)", stdout, err)
		}
		if len(tools.Tools) != 3 || tools.Total != 3 || tools.Query != "reviews" {
			t.Fatalf("tools = %+v", tools)
		}
		if tools.Tools[1].RefID != "mcp:google-gbp:reply_review" {
			t.Fatalf("second tool ref_id = %q", tools.Tools[1].RefID)
		}
	})

	t.Run("describe", func(t *testing.T) {
		code, stdout, _ := runCLI(t, "describe", "google_gbp_list_reviews", "--json")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var detail hubToolDetail
		if err := json.Unmarshal([]byte(stdout), &detail); err != nil {
			t.Fatalf("describe stdout is not one json line: %q (%v)", stdout, err)
		}
		if detail.RefID != "mcp:google-gbp:list_reviews" || !detail.Callable || detail.TimeoutSeconds != 120 {
			t.Fatalf("describe = %+v", detail)
		}
		// the schema is forwarded as-is; a script that feeds it to a JSON parser must keep working
		var schema map[string]any
		if err := json.Unmarshal([]byte(detail.InputSchema), &schema); err != nil {
			t.Fatalf("input_schema is not valid json: %v", err)
		}
		required, _ := schema["required"].([]any)
		if len(required) != 1 || required[0] != "location" {
			t.Fatalf("input_schema required = %v", schema["required"])
		}
	})

	t.Run("call", func(t *testing.T) {
		var request struct {
			Arguments      string `json:"arguments"`
			TimeoutSeconds int    `json:"timeout_seconds"`
		}
		if err := json.Unmarshal(contractFixture(t, "call-request.json"), &request); err != nil {
			t.Fatalf("call-request fixture: %v", err)
		}

		code, stdout, _ := runCLI(t, "call", "google_gbp_list_reviews",
			"--args", request.Arguments, "--timeout", "120", "--raw")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var success hubCallResponse
		if err := json.Unmarshal(contractFixture(t, "call-success.json"), &success); err != nil {
			t.Fatalf("call-success fixture: %v", err)
		}
		if stdout != success.Text+"\n" {
			t.Fatalf("--raw stdout = %q, want the fixture text", stdout)
		}

		var sent struct {
			Arguments      string `json:"arguments"`
			TimeoutSeconds int    `json:"timeout_seconds"`
		}
		if err := json.Unmarshal([]byte(callBody), &sent); err != nil {
			t.Fatalf("request body = %q (%v)", callBody, err)
		}
		if sent.Arguments != request.Arguments || sent.TimeoutSeconds != request.TimeoutSeconds {
			t.Fatalf("request body = %+v, want %+v", sent, request)
		}
	})

	t.Run("call error", func(t *testing.T) {
		var failure hubCallResponse
		if err := json.Unmarshal(contractFixture(t, "call-error.json"), &failure); err != nil {
			t.Fatalf("call-error fixture: %v", err)
		}
		code, stdout, stderr := runCLI(t, "call", "google_gbp_reply_review", "--json")
		if code != exitToolError {
			t.Fatalf("exit = %d, want 1", code)
		}
		if !strings.Contains(stdout, failure.ErrorMessage) || !strings.Contains(stdout, `"is_error":true`) {
			t.Fatalf("stdout = %q", stdout)
		}
		if strings.Contains(stderr, failure.ErrorMessage) {
			t.Fatalf("json mode must keep the failure off stderr, got %q", stderr)
		}
	})

	t.Run("call pending then poll", func(t *testing.T) {
		var pending hubCallResponse
		if err := json.Unmarshal(contractFixture(t, "pending-call.json"), &pending); err != nil {
			t.Fatalf("pending-call fixture: %v", err)
		}
		var completed hubCallResponse
		if err := json.Unmarshal(contractFixture(t, "task-completed.json"), &completed); err != nil {
			t.Fatalf("task-completed fixture: %v", err)
		}
		code, stdout, _ := runCLI(t, "call", "review_responder", "--json", "--timeout", "30")
		if code != exitSuccess {
			t.Fatalf("exit = %d, want 0", code)
		}
		var polled hubCallResponse
		if err := json.Unmarshal([]byte(stdout), &polled); err != nil {
			t.Fatalf("poll stdout is not one json line: %q (%v)", stdout, err)
		}
		if polled.Status != "completed" || polled.TaskID != pending.TaskID || polled.Text != completed.Text {
			t.Fatalf("polled = %+v", polled)
		}
		if polled.LlmUsage == nil || polled.LlmUsage.InputTokens != 1284 || polled.LlmUsage.Model == "" {
			t.Fatalf("polled llm_usage = %+v", polled.LlmUsage)
		}
		if polled.CallID != pending.CallID {
			t.Fatalf("polled call_id = %q, want %q", polled.CallID, pending.CallID)
		}
	})
}

func TestCliMapsSharedErrorFixturesToExitCodes(t *testing.T) {
	status := 0
	body := "not-bound.json"
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		serveContractFixture(t, w, body)
	}}
	stub.start(t)

	status, body = http.StatusServiceUnavailable, "not-bound.json"
	if code, _, _ := runCLI(t, "me", "--json"); code != exitUnauthenticated {
		t.Fatalf("not_bound exit = %d, want 3", code)
	}

	status, body = http.StatusForbidden, "forbidden.json"
	if code, _, _ := runCLI(t, "me", "--json"); code != exitForbidden {
		t.Fatalf("forbidden exit = %d, want 4", code)
	}
}
