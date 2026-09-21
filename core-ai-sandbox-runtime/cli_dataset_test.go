package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The sandbox dataset command is a thin shell over the session's dataset tools, so what has to be pinned is the
// mapping (subcommand -> tool + arguments), the payload it prints, and the way a refusal travels. The catalog comes
// from the shared contract fixture, the same bytes the Python SDK parses.

const (
	fixtureSession = "b7f1c0e2-3a44-4f7b-9c1d-8e2a5d6b7c80"
	menuStateID    = "d7a1f3c9-5b2e-4c81-9f60-1d4e8a2b6c33"
	reviewLogID    = "3f8c2b41-0d7a-4e59-8b16-92ac5ed7f204"
)

// datasetCall is one tool call the CLI sent, decoded from the request body.
type datasetCall struct {
	Tool      string
	Arguments map[string]any
	Timeout   int
}

func datasetStub(t *testing.T, text string, calls *[]datasetCall) *hubStub {
	t.Helper()
	return &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/me":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"session_id":"` + fixtureSession + `","agent_name":"restaurant-local-seo",` +
				`"sandbox_id":"sbx-9f0c1d2e","sandbox_state":"ready","tool_count":7}`))
		case r.URL.Path == "/catalog":
			w.Header().Set("Content-Type", "application/json")
			w.Write(contractFixture(t, "catalog.json"))
		case strings.HasSuffix(r.URL.Path, "/call"):
			payload, _ := io.ReadAll(r.Body)
			var body struct {
				Arguments      string `json:"arguments"`
				TimeoutSeconds int    `json:"timeout_seconds"`
			}
			if err := json.Unmarshal(payload, &body); err != nil {
				t.Errorf("call body is not json: %q (%v)", payload, err)
			}
			call := datasetCall{
				Tool:    strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/tools/"), "/call"),
				Timeout: body.TimeoutSeconds,
			}
			if err := json.Unmarshal([]byte(body.Arguments), &call.Arguments); err != nil {
				t.Errorf("call arguments are not json: %q (%v)", body.Arguments, err)
			}
			*calls = append(*calls, call)
			response, _ := json.Marshal(hubCallResponse{CallID: "c-1", Status: "completed", Success: true, Text: text})
			w.Header().Set("Content-Type", "application/json")
			w.Write(response)
		default:
			t.Errorf("unexpected hub request: %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
			w.Write([]byte(`{"message":"no such tool"}`))
		}
	}}
}

func assertArguments(t *testing.T, call datasetCall, expected map[string]any) {
	t.Helper()
	if len(call.Arguments) != len(expected) {
		t.Fatalf("%s arguments = %v, want %v", call.Tool, call.Arguments, expected)
	}
	for name, want := range expected {
		got := fmt.Sprint(call.Arguments[name])
		if got != fmt.Sprint(want) {
			t.Fatalf("%s argument %s = %s, want %v", call.Tool, name, got, want)
		}
	}
}

func TestCliDatasetListReadsTheCatalogSection(t *testing.T) {
	var calls []datasetCall
	datasetStub(t, "", &calls).start(t)

	code, stdout, stderr := runCLI(t, "dataset", "list", "--json")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0 (%s)", code, stderr)
	}
	var list hubDatasetListResponse
	if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &list); err != nil {
		t.Fatalf("list stdout is not one json line: %q (%v)", stdout, err)
	}
	if list.SessionID != fixtureSession || len(list.Datasets) != 3 {
		t.Fatalf("list = %+v", list)
	}
	state := list.Datasets[0]
	if state.DatasetID != menuStateID || state.Name != "menu-state" || state.Type != "SESSION" ||
		state.Permission != "FULL" || state.Description == "" || len(state.Schema) != 2 {
		t.Fatalf("first dataset = %+v", state)
	}
	if state.Schema[0].Name != "menuPublished" || state.Schema[0].Type != "boolean" || state.Schema[0].Label != "Published" {
		t.Fatalf("first field = %+v", state.Schema[0])
	}
	if log := list.Datasets[1]; log.Type != "GENERAL" || log.Permission != "WRITE" || log.Schema[1].Name != "replied_at" {
		t.Fatalf("second dataset = %+v", log)
	}
	if read := list.Datasets[2]; read.Name != "seo-history" || read.Type != "GENERAL" || read.Permission != "READ" {
		t.Fatalf("third dataset = %+v", read)
	}
	if len(calls) != 0 {
		t.Fatalf("list called tools: %+v", calls)
	}

	code, human, _ := runCLI(t, "dataset", "list")
	if code != exitSuccess {
		t.Fatalf("human exit = %d, want 0", code)
	}
	for _, expected := range []string{"menu-state", "SESSION", "FULL", "2 fields", reviewLogID, "review-log", "WRITE",
		"seo-history", "READ"} {
		if !strings.Contains(human, expected) {
			t.Fatalf("list output is missing %q:\n%s", expected, human)
		}
	}
}

func TestCliDatasetListOnServerWithoutTheSection(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"session_id":"s-1","groups":[],"tools":[]}`))
	}}
	stub.start(t)

	code, stdout, stderr := runCLI(t, "dataset", "list", "--json")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0 (%s)", code, stderr)
	}
	if !strings.Contains(stdout, `"datasets":[]`) {
		t.Fatalf("stdout = %q, want an empty datasets array rather than null", stdout)
	}

	code, human, _ := runCLI(t, "dataset", "list")
	if code != exitSuccess {
		t.Fatalf("human exit = %d, want 0", code)
	}
	if !strings.Contains(human, "no datasets section") {
		t.Fatalf("human output = %q", human)
	}
}

func TestCliDatasetShowResolvesIdAndName(t *testing.T) {
	var calls []datasetCall
	datasetStub(t, "", &calls).start(t)

	for _, ref := range []string{"menu-state", menuStateID} {
		code, stdout, stderr := runCLI(t, "dataset", "show", ref, "--json")
		if code != exitSuccess {
			t.Fatalf("show %s exit = %d, want 0 (%s)", ref, code, stderr)
		}
		var view hubCatalogDataset
		if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &view); err != nil {
			t.Fatalf("show stdout is not one json line: %q (%v)", stdout, err)
		}
		if view.DatasetID != menuStateID || view.Name != "menu-state" {
			t.Fatalf("show %s = %+v", ref, view)
		}
	}

	code, human, _ := runCLI(t, "dataset", "show", "menu-state")
	if code != exitSuccess {
		t.Fatalf("human exit = %d, want 0", code)
	}
	for _, expected := range []string{"menu-state", menuStateID, "SESSION", "FULL", "menuPublished", "boolean", "Published"} {
		if !strings.Contains(human, expected) {
			t.Fatalf("show output is missing %q:\n%s", expected, human)
		}
	}

	if code, _, stderr := runCLI(t, "dataset", "show", "missing"); code != exitNotFound {
		t.Fatalf("unknown dataset exit = %d, want %d (%s)", code, exitNotFound, stderr)
	} else if !strings.Contains(stderr, "dataset not found in this session: missing") {
		t.Fatalf("stderr = %q", stderr)
	}
	if len(calls) != 0 {
		t.Fatalf("show called tools: %+v", calls)
	}
}

func TestCliDatasetShowFailsClosedOnAmbiguousName(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"session_id":"s-1","groups":[],"tools":[],"datasets":[` +
			`{"dataset_id":"ds-a","name":"dup","type":"SESSION","permission":"READ","schema":[]},` +
			`{"dataset_id":"ds-b","name":"dup","type":"GENERAL","permission":"READ","schema":[]}]}`))
	}}
	stub.start(t)

	code, stdout, stderr := runCLI(t, "dataset", "show", "dup")
	if code != exitUsage {
		t.Fatalf("exit = %d, want %d (%s)", code, exitUsage, stderr)
	}
	if stdout != "" {
		t.Fatalf("stdout = %q, want nothing on an ambiguous reference", stdout)
	}
	for _, expected := range []string{"ambiguous", "ds-a", "ds-b"} {
		if !strings.Contains(stderr, expected) {
			t.Fatalf("stderr is missing %q: %q", expected, stderr)
		}
	}
}

func TestCliDatasetOperationsMapToTools(t *testing.T) {
	statePayload := `{"state":{"menuPublished":true},"dataset_id":"` + menuStateID + `","session_id":"s-1"}`
	savedPayload := `{"status":"saved","dataset_id":"` + menuStateID + `","session_id":"s-1"}`
	patchedPayload := `{"status":"updated","dataset_id":"` + menuStateID + `","session_id":"s-1","updated_fields":["note"]}`
	recordsPayload := `{"records":[{"record_id":"rec-9","data":{"review_id":"r-1"}}],"total":1,"dataset_id":"` + reviewLogID + `"}`
	insertedPayload := `{"status":"created","dataset_id":"` + reviewLogID + `","inserted_fields":["review_id"],"message":"record created"}`
	updatedPayload := `{"status":"updated","record_id":"rec-9","dataset_id":"` + reviewLogID + `","updated_fields":["replied_at"],"message":"record updated"}`
	deletedPayload := `{"status":"deleted","record_id":"rec-9","dataset_id":"` + reviewLogID + `","message":"record deleted"}`
	filter := `{"replied_at":{"$exists":true}}`

	cases := []struct {
		name      string
		args      []string
		tool      string
		arguments map[string]any
		payload   string
	}{
		{
			name: "state get", args: []string{"state", "get", "menu-state", "--fields", "menuPublished"},
			tool: "get_session_state", payload: statePayload,
			arguments: map[string]any{"dataset_id": "menu-state", "fields": "menuPublished"},
		},
		{
			name: "state get without fields", args: []string{"state", "get", menuStateID},
			tool: "get_session_state", payload: statePayload,
			arguments: map[string]any{"dataset_id": menuStateID},
		},
		{
			name: "state set", args: []string{"state", "set", "menu-state", "--data", `{"menuPublished":true}`},
			tool: "set_session_state", payload: savedPayload,
			arguments: map[string]any{"dataset_id": "menu-state", "data": map[string]any{"menuPublished": true}},
		},
		{
			name: "state patch", args: []string{"state", "patch", "menu-state", "--data", `{"note":"starters first"}`},
			tool: "update_session_state", payload: patchedPayload,
			arguments: map[string]any{"dataset_id": "menu-state", "data": map[string]any{"note": "starters first"}},
		},
		{
			name: "records query",
			args: []string{"records", "query", "review-log", "--filter", filter, "--fields", "review_id",
				"--from", "2026-09-01T00:00:00Z", "--to", "2026-09-15T00:00:00Z", "--limit", "50", "--offset", "100"},
			tool: "query_dataset_records", payload: recordsPayload,
			arguments: map[string]any{
				"dataset_id": "review-log", "filter": filter, "fields": "review_id",
				"from": "2026-09-01T00:00:00Z", "to": "2026-09-15T00:00:00Z", "limit": 50, "offset": 100,
			},
		},
		{
			name: "records insert", args: []string{"records", "insert", reviewLogID, "--data", `{"review_id":"r-1"}`},
			tool: "insert_dataset_record", payload: insertedPayload,
			arguments: map[string]any{"dataset_id": reviewLogID, "data": map[string]any{"review_id": "r-1"}},
		},
		{
			name: "records update",
			args: []string{"records", "update", "review-log", "--record-id", "rec-9", "--data", `{"replied_at":"2026-09-15T10:00:00Z"}`},
			tool: "update_dataset_record", payload: updatedPayload,
			arguments: map[string]any{
				"dataset_id": "review-log", "record_id": "rec-9",
				"data": map[string]any{"replied_at": "2026-09-15T10:00:00Z"},
			},
		},
		{
			name: "records delete", args: []string{"records", "delete", "review-log", "--record-id", "rec-9"},
			tool: "delete_dataset_record", payload: deletedPayload,
			arguments: map[string]any{"dataset_id": "review-log", "record_id": "rec-9"},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var calls []datasetCall
			datasetStub(t, tc.payload, &calls).start(t)

			jsonArgs := append(append([]string{"dataset"}, tc.args...), "--json")
			code, stdout, stderr := runCLI(t, jsonArgs...)
			if code != exitSuccess {
				t.Fatalf("exit = %d, want 0 (%s)", code, stderr)
			}
			if len(calls) != 1 {
				t.Fatalf("calls = %+v, want exactly one", calls)
			}
			if calls[0].Tool != tc.tool {
				t.Fatalf("tool = %s, want %s", calls[0].Tool, tc.tool)
			}
			assertArguments(t, calls[0], tc.arguments)
			if calls[0].Timeout != defaultCLITimeout {
				t.Fatalf("timeout = %d, want %d", calls[0].Timeout, defaultCLITimeout)
			}
			// stdout carries the operation's own payload and nothing else, byte for byte
			if stdout != tc.payload+"\n" {
				t.Fatalf("stdout = %q, want the payload verbatim", stdout)
			}

			// human mode prints the same payload indented, with the running commentary on stderr
			var humanCalls []datasetCall
			datasetStub(t, tc.payload, &humanCalls).start(t)
			code, human, humanErr := runCLI(t, append([]string{"dataset"}, tc.args...)...)
			if code != exitSuccess {
				t.Fatalf("human exit = %d, want 0 (%s)", code, humanErr)
			}
			if human != indentJSON(tc.payload, "  ") {
				t.Fatalf("human stdout = %q, want the payload indented", human)
			}
			if !strings.Contains(humanErr, "calling "+tc.tool) {
				t.Fatalf("human stderr = %q, want the tool being called", humanErr)
			}
		})
	}
}

func TestCliDatasetWriteSummaryGoesToStderr(t *testing.T) {
	var calls []datasetCall
	datasetStub(t, `{"status":"updated","record_id":"rec-9","dataset_id":"`+reviewLogID+`","updated_fields":["replied_at","note"],"message":"record updated"}`,
		&calls).start(t)

	code, stdout, stderr := runCLI(t, "dataset", "records", "update", "review-log", "--record-id", "rec-9",
		"--data", `{"replied_at":"2026-09-15T10:00:00Z"}`)
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0 (%s)", code, stderr)
	}
	if !strings.Contains(stderr, "updated (fields: replied_at, note; record: rec-9)") {
		t.Fatalf("stderr = %q", stderr)
	}
	if strings.Contains(stdout, "updated (fields:") {
		t.Fatalf("stdout = %q, want the summary on stderr", stdout)
	}

	code, stdout, stderr = runCLI(t, "dataset", "records", "update", "review-log", "--record-id", "rec-9",
		"--data", `{"replied_at":"2026-09-15T10:00:00Z"}`, "--quiet")
	if code != exitSuccess {
		t.Fatalf("quiet exit = %d, want 0", code)
	}
	if stdout != "" || stderr != "" {
		t.Fatalf("quiet printed: stdout=%q stderr=%q", stdout, stderr)
	}
}

func TestCliDatasetReadsDataFromFile(t *testing.T) {
	var calls []datasetCall
	datasetStub(t, `{"status":"created","dataset_id":"`+reviewLogID+`","inserted_fields":["review_id"],"message":"record created"}`,
		&calls).start(t)

	path := filepath.Join(t.TempDir(), "record.json")
	if err := os.WriteFile(path, []byte(`{"review_id":"r-7"}`), 0o600); err != nil {
		t.Fatalf("write data file: %v", err)
	}
	code, _, stderr := runCLI(t, "dataset", "records", "insert", "review-log", "--data-file", path, "--json", "--timeout", "45")
	if code != exitSuccess {
		t.Fatalf("exit = %d, want 0 (%s)", code, stderr)
	}
	if len(calls) != 1 {
		t.Fatalf("calls = %+v", calls)
	}
	assertArguments(t, calls[0], map[string]any{"dataset_id": "review-log", "data": map[string]any{"review_id": "r-7"}})
	if calls[0].Timeout != 45 {
		t.Fatalf("timeout = %d, want 45", calls[0].Timeout)
	}
}

func TestCliDatasetToolRefusalStaysOffStdout(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		response, _ := json.Marshal(hubCallResponse{CallID: "c-1", Status: "completed", IsError: true,
			ErrorMessage: "write access denied to dataset: review-log"})
		w.Header().Set("Content-Type", "application/json")
		w.Write(response)
	}}
	stub.start(t)

	code, stdout, stderr := runCLI(t, "dataset", "records", "insert", "review-log", "--data", `{"review_id":"r-1"}`, "--json")
	if code != exitToolError {
		t.Fatalf("exit = %d, want %d", code, exitToolError)
	}
	if !strings.Contains(stdout, "write access denied to dataset: review-log") {
		t.Fatalf("stdout = %q, want the refusal in the error envelope", stdout)
	}
	// the raw tool envelope is not a dataset payload: it must never become the parsed stdout of a dataset command
	for _, unwanted := range []string{"is_error", "call_id"} {
		if strings.Contains(stdout, unwanted) {
			t.Fatalf("stdout = %q, want no tool envelope field %q", stdout, unwanted)
		}
	}
	if strings.Contains(stderr, "denied") {
		t.Fatalf("stderr = %q, want json mode to keep the failure off stderr", stderr)
	}

	code, stdout, stderr = runCLI(t, "dataset", "records", "insert", "review-log", "--data", `{"review_id":"r-1"}`)
	if code != exitToolError {
		t.Fatalf("human exit = %d, want %d", code, exitToolError)
	}
	if stdout != "" {
		t.Fatalf("human stdout = %q, want nothing", stdout)
	}
	if !strings.Contains(stderr, "error: write access denied to dataset: review-log") {
		t.Fatalf("human stderr = %q", stderr)
	}
}

func TestCliDatasetMissingToolKeepsHubStatusCode(t *testing.T) {
	stub := &hubStub{handler: func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"message":"tool not found: get_session_state"}`))
	}}
	stub.start(t)

	code, _, stderr := runCLI(t, "dataset", "state", "get", "menu-state", "--json")
	if code != exitNotFound {
		t.Fatalf("exit = %d, want %d (%s)", code, exitNotFound, stderr)
	}
}

func TestCliDatasetSessionFlagMustMatchTheSandbox(t *testing.T) {
	var calls []datasetCall
	stub := datasetStub(t, "", &calls)
	stub.start(t)

	code, _, stderr := runCLI(t, "dataset", "list", "--session", fixtureSession)
	if code != exitSuccess {
		t.Fatalf("matching session exit = %d, want 0 (%s)", code, stderr)
	}
	if !strings.Contains(strings.Join(stub.requests, " "), "GET /me") {
		t.Fatalf("requests = %v, want the given session checked against /me", stub.requests)
	}

	code, stdout, stderr := runCLI(t, "dataset", "list", "--session", "s-other")
	if code != exitUsage {
		t.Fatalf("mismatched session exit = %d, want %d", code, exitUsage)
	}
	if stdout != "" {
		t.Fatalf("stdout = %q", stdout)
	}
	if !strings.Contains(stderr, "--session s-other does not match this sandbox's session "+fixtureSession) {
		t.Fatalf("stderr = %q", stderr)
	}

	if code, _, stderr := runCLI(t, "dataset", "list", "--session"); code != exitUsage {
		t.Fatalf("missing value exit = %d, want %d (%s)", code, exitUsage, stderr)
	} else if !strings.Contains(stderr, "--session requires a value") {
		t.Fatalf("stderr = %q", stderr)
	}
}

func TestCliDatasetRejectsInvalidInput(t *testing.T) {
	cases := []struct {
		name string
		args []string
		want string
	}{
		{name: "no subcommand", args: []string{"dataset"}, want: "dataset expects a subcommand"},
		{name: "unknown subcommand", args: []string{"dataset", "bogus"}, want: "unknown dataset subcommand: bogus"},
		{name: "unknown state leaf", args: []string{"dataset", "state", "clear", "menu-state"}, want: "unknown dataset state subcommand: clear"},
		{name: "state args", args: []string{"dataset", "state", "get"}, want: "dataset state expects"},
		{name: "unknown records leaf", args: []string{"dataset", "records", "purge", "review-log"}, want: "unknown dataset records subcommand: purge"},
		{name: "records args", args: []string{"dataset", "records", "query"}, want: "dataset records expects"},
		{
			name: "flag on the wrong leaf",
			args: []string{"dataset", "state", "get", "menu-state", "--data", `{}`},
			want: "--data is not valid for this command",
		},
		{
			name: "flag outside the dataset command",
			args: []string{"call", "web_search", "--data", `{}`},
			want: "--data is only valid for the dataset command",
		},
		{
			name: "data and data-file",
			args: []string{"dataset", "state", "set", "menu-state", "--data", `{}`, "--data-file", "x.json"},
			want: "pass either --data or --data-file, not both",
		},
		{name: "missing data", args: []string{"dataset", "state", "set", "menu-state"}, want: "missing data"},
		{name: "data is not an object", args: []string{"dataset", "state", "set", "menu-state", "--data", `[1]`}, want: "--data must be a JSON object"},
		{name: "record id missing", args: []string{"dataset", "records", "delete", "review-log"}, want: "--record-id is required"},
		{
			name: "record id blank",
			args: []string{"dataset", "records", "delete", "review-log", "--record-id", " "},
			want: "--record-id is required",
		},
		{
			name: "limit is not a number",
			args: []string{"dataset", "records", "query", "review-log", "--limit", "many"},
			want: "--limit must be an integer",
		},
		{
			name: "data file unreadable",
			args: []string{"dataset", "state", "set", "menu-state", "--data-file", filepath.Join("no", "such", "file.json")},
			want: "cannot read --data-file",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			code, stdout, stderr := runCLI(t, tc.args...)
			if code != exitUsage {
				t.Fatalf("exit = %d, want %d (stderr=%s)", code, exitUsage, stderr)
			}
			if stdout != "" {
				t.Fatalf("stdout = %q, want nothing", stdout)
			}
			if !strings.Contains(stderr, tc.want) {
				t.Fatalf("stderr = %q, want %q", stderr, tc.want)
			}
		})
	}
}

func TestCliDatasetHelpAndCatalogJSON(t *testing.T) {
	code, stdout, _ := runCLI(t, "dataset", "--help")
	if code != exitSuccess {
		t.Fatalf("help exit = %d, want 0", code)
	}
	for _, expected := range []string{"core-ai-sandbox dataset", "records query", "--data-file", "--session"} {
		if !strings.Contains(stdout, expected) {
			t.Fatalf("help output is missing %q:\n%s", expected, stdout)
		}
	}

	var calls []datasetCall
	datasetStub(t, "", &calls).start(t)
	code, stdout, stderr := runCLI(t, "catalog", "--json")
	if code != exitSuccess {
		t.Fatalf("catalog exit = %d, want 0 (%s)", code, stderr)
	}
	if !strings.Contains(stdout, `"datasets"`) {
		t.Fatalf("catalog --json dropped the section: %q", stdout)
	}
	var catalog hubCatalogResponse
	if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &catalog); err != nil {
		t.Fatalf("catalog stdout is not one json line: %q (%v)", stdout, err)
	}
	if len(catalog.datasetBindings()) != 3 {
		t.Fatalf("catalog datasets = %+v", catalog.Datasets)
	}
}
