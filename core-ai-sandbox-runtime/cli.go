package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
	"time"
)

const (
	cliBinaryName       = "core-ai-sandbox"
	defaultCLITimeout   = 120
	maxCLITimeout       = 600
	defaultCLIMaxOutput = 64 * 1024
)

// Exit codes follow the core-ai-cli hub commands so skill authors keep one table in mind.
// 7 (input required) is agent-only and can never come out of the sandbox hub.
const (
	exitSuccess         = 0
	exitToolError       = 1
	exitUsage           = 2
	exitUnauthenticated = 3
	exitForbidden       = 4
	exitNotFound        = 5
	exitTimeout         = 6
)

const cliUsage = `core-ai-sandbox - call this session's agent-configured capabilities from inside a sandbox

Usage:
  core-ai-sandbox me                       Show the bound session and its tool counts
  core-ai-sandbox catalog                  List every callable tool, grouped by kind
  core-ai-sandbox tools [query]            Search tools
  core-ai-sandbox describe <tool>          Show a tool's metadata and input_schema
  core-ai-sandbox call <tool> [options]    Call a tool
  core-ai-sandbox dataset <subcommand>     Read and write the datasets bound to this session
                                           (dataset --help lists the subcommands)

Call arguments (merged, later sources win):
  --args JSON            Arguments as a JSON object
  --args-file FILE|-     Read arguments JSON from a file or stdin
  --arg k=v              Repeatable; coerced using the tool's input_schema

Options:
  --kind K               tools: filter by kind (mcp|api|llm_call|agent|builtin)
  --timeout SEC          call: total wait budget (default 120, max 600)
  --max-output N         truncate printed text after N chars (default 65536)
  --json                 Print the raw server response as one JSON line
  --raw                  Print only the tool text content
  --quiet                Print nothing; the exit code is the result
  --help, -h             Print this usage

Exit codes:
  0 success  1 tool error  2 usage  3 not bound (or unauthenticated)  4 forbidden
  5 tool not found  6 timeout (the task may still be running; see task_id)

Environment:
  CORE_AI_HUB   Hub endpoint injected by the sandbox runtime (127.0.0.1:8081/hub)
`

type cliOptions struct {
	command    string
	positional []string
	kind       string
	argsJSON   string
	argsFile   string
	argPairs   []string
	flags      []flagAssignment
	timeout    int
	maxOutput  int
	json       bool
	raw        bool
	quiet      bool
	help       bool
}

// flagAssignment is one dataset option kept in the order it was written, so the last one still wins.
type flagAssignment struct {
	name  string
	value string
}

// cliToolKinds mirrors the kinds the hub reports; the CLI rejects anything else so a typo cannot
// look like a legitimately empty result.
var cliToolKinds = []string{"mcp", "api", "llm_call", "agent", "builtin"}

type cliError struct {
	code    int
	message string
	// reported marks a failure whose detail was already printed (a tool that answered with an
	// error still produced a response); only the exit code and a human note are left to emit.
	reported bool
}

func (e *cliError) Error() string { return e.message }

func usageError(format string, args ...any) *cliError {
	return &cliError{code: exitUsage, message: fmt.Sprintf(format, args...)}
}

func runCli(args []string, stdout, stderr io.Writer) int {
	opts, err := parseCliArgs(args)
	if err != nil {
		fmt.Fprintln(stderr, "error: "+err.message)
		fmt.Fprint(stderr, cliUsage)
		return err.code
	}
	env := &cliEnv{opts: opts, stdout: stdout, stderr: stderr, hubURL: coreAIHubURL()}
	if err := env.dispatch(); err != nil {
		return env.fail(err)
	}
	return exitSuccess
}

type cliEnv struct {
	opts   cliOptions
	stdout io.Writer
	stderr io.Writer
	hubURL string
	client *http.Client
}

func coreAIHubURL() string {
	if v := os.Getenv("CORE_AI_HUB"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return "http://127.0.0.1:" + envOrDefault("HUB_PORT", "8081") + hubPathPrefix
}

func parseCliArgs(args []string) (cliOptions, *cliError) {
	opts := cliOptions{timeout: defaultCLITimeout, maxOutput: defaultCLIMaxOutput}
	index := 0
	if len(args) > 0 {
		opts.command = args[0]
		index = 1
		if isHelpFlag(args[0]) {
			opts.help = true
		}
	}
	needsValue := func(flag string, rest []string) (string, *cliError) {
		if len(rest) == 0 {
			return "", usageError("%s requires a value", flag)
		}
		return rest[0], nil
	}
	for index < len(args) {
		arg := args[index]
		index++
		name, inline, hasInline := splitFlag(arg)
		value := func() (string, *cliError) {
			if hasInline {
				return inline, nil
			}
			v, err := needsValue(name, args[index:])
			if err == nil {
				index++
			}
			return v, err
		}
		switch name {
		case "--json":
			opts.json = true
		case "--raw":
			opts.raw = true
		case "--quiet":
			opts.quiet = true
		case "--kind":
			v, err := value()
			if err != nil {
				return opts, err
			}
			if !slices.Contains(cliToolKinds, v) {
				return opts, usageError("--kind must be one of %s, got: %s", strings.Join(cliToolKinds, "|"), v)
			}
			opts.kind = v
		case "--args":
			v, err := value()
			if err != nil {
				return opts, err
			}
			opts.argsJSON = v
		case "--args-file":
			v, err := value()
			if err != nil {
				return opts, err
			}
			opts.argsFile = v
		case "--arg":
			v, err := value()
			if err != nil {
				return opts, err
			}
			opts.argPairs = append(opts.argPairs, v)
		case "--timeout":
			v, err := value()
			if err != nil {
				return opts, err
			}
			seconds, convErr := strconv.Atoi(v)
			if convErr != nil || seconds <= 0 {
				return opts, usageError("--timeout must be a positive integer, got: %s", v)
			}
			opts.timeout = seconds
		case "--max-output":
			v, err := value()
			if err != nil {
				return opts, err
			}
			size, convErr := strconv.Atoi(v)
			if convErr != nil || size < 0 {
				return opts, usageError("--max-output must be a non-negative integer, got: %s", v)
			}
			opts.maxOutput = size
		case "--help", "-h":
			opts.help = true
		default:
			if slices.Contains(datasetFlagNames, name) {
				if opts.command != "dataset" {
					return opts, usageError("%s is only valid for the dataset command", name)
				}
				v, err := value()
				if err != nil {
					return opts, err
				}
				opts.flags = append(opts.flags, flagAssignment{name: name, value: v})
				continue
			}
			if strings.HasPrefix(arg, "-") {
				return opts, usageError("unknown option: %s", arg)
			}
			opts.positional = append(opts.positional, arg)
		}
	}
	if opts.timeout > maxCLITimeout {
		fmt.Fprintf(os.Stderr, "warning: --timeout capped at %ds\n", maxCLITimeout)
		opts.timeout = maxCLITimeout
	}
	return opts, nil
}

func splitFlag(arg string) (name, value string, hasValue bool) {
	if strings.HasPrefix(arg, "--") {
		if at := strings.Index(arg, "="); at > 0 {
			return arg[:at], arg[at+1:], true
		}
	}
	return arg, "", false
}

func isHelpFlag(arg string) bool {
	return arg == "--help" || arg == "-h"
}

func (env *cliEnv) dispatch() *cliError {
	if env.opts.help {
		if env.opts.command == "dataset" {
			return env.showDatasetUsage()
		}
		return env.showUsage()
	}
	switch env.opts.command {
	case "me":
		return env.showMe()
	case "catalog":
		return env.showCatalog()
	case "tools":
		return env.showTools()
	case "describe":
		return env.describeTool()
	case "call":
		return env.callTool()
	case "dataset":
		return env.datasetCommand()
	case "":
		return usageError("missing command")
	default:
		return usageError("unknown command: %s", env.opts.command)
	}
}

func (env *cliEnv) fail(err *cliError) int {
	switch {
	case err.reported && env.opts.json:
		// the response is already on stdout as one JSON line; keep it parseable
	case err.reported:
		fmt.Fprintln(env.stderr, "error: "+err.message)
	case env.opts.json && !env.opts.quiet:
		payload := map[string]any{"error": map[string]any{
			"code":    errorCodeName(err.code),
			"message": err.message,
			"status":  err.code,
		}}
		env.printJSON(payload)
	default:
		fmt.Fprintln(env.stderr, "error: "+err.message)
	}
	return err.code
}

func errorCodeName(code int) string {
	switch code {
	case exitUsage:
		return "USAGE"
	case exitUnauthenticated:
		return "NOT_BOUND"
	case exitForbidden:
		return "FORBIDDEN"
	case exitNotFound:
		return "NOT_FOUND"
	case exitTimeout:
		return "TIMEOUT"
	default:
		return "TOOL_ERROR"
	}
}

func (env *cliEnv) printJSON(value any) {
	encoded, err := json.Marshal(value)
	if err != nil {
		fmt.Fprintln(env.stderr, "error: failed to encode json: "+err.Error())
		return
	}
	fmt.Fprintln(env.stdout, string(encoded))
}

func (env *cliEnv) metadata(format string, args ...any) {
	if env.opts.quiet {
		return
	}
	fmt.Fprintf(env.stderr, format+"\n", args...)
}

// flag reads a dataset option; the parser keeps them in order, so the last one written wins like every other source.
func (env *cliEnv) flag(name string) (string, bool) {
	for index := len(env.opts.flags) - 1; index >= 0; index-- {
		if env.opts.flags[index].name == name {
			return env.opts.flags[index].value, true
		}
	}
	return "", false
}

// checkFlags refuses dataset options that do not belong to the leaf being run, so a mistyped flag cannot be swallowed
// as if it had been applied.
func (env *cliEnv) checkFlags(allowed ...string) *cliError {
	for _, assignment := range env.opts.flags {
		if !slices.Contains(allowed, assignment.name) {
			return usageError("%s is not valid for this command", assignment.name)
		}
	}
	return nil
}

func (env *cliEnv) requiredFlag(name string) (string, *cliError) {
	value, ok := env.flag(name)
	if !ok || strings.TrimSpace(value) == "" {
		return "", usageError("%s is required", name)
	}
	return strings.TrimSpace(value), nil
}

func (env *cliEnv) httpClient() *http.Client {
	if env.client == nil {
		budget := time.Duration(env.opts.timeout)*time.Second + 30*time.Second
		env.client = &http.Client{Timeout: budget}
	}
	return env.client
}

// request performs one hub call. A 503 not_bound is retried a few times because a script may
// start the moment its sandbox comes up, just before the server finishes binding it.
func (env *cliEnv) request(method, path string, body []byte) ([]byte, *cliError) {
	attempts := 3
	for attempt := 1; ; attempt++ {
		payload, err := env.doRequest(method, path, body)
		if err == nil || err.code != exitUnauthenticated || attempt >= attempts {
			return payload, err
		}
		time.Sleep(time.Duration(attempt) * time.Second)
	}
}

func (env *cliEnv) doRequest(method, path string, body []byte) ([]byte, *cliError) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequest(method, env.hubURL+path, reader)
	if err != nil {
		return nil, usageError("invalid request: %s", err)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := env.httpClient().Do(request)
	if err != nil {
		return nil, &cliError{code: exitTimeout, message: "hub request failed: " + err.Error()}
	}
	defer response.Body.Close()
	payload, readErr := io.ReadAll(response.Body)
	if readErr != nil {
		return nil, &cliError{code: exitTimeout, message: "failed to read hub response: " + readErr.Error()}
	}
	if response.StatusCode/100 == 2 {
		return payload, nil
	}
	return nil, httpStatusError(response.StatusCode, payload)
}

func httpStatusError(status int, payload []byte) *cliError {
	message := strings.TrimSpace(string(payload))
	var envelope struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(payload, &envelope) == nil && envelope.Message != "" {
		message = envelope.Message
	}
	code := exitToolError
	switch status {
	case http.StatusUnauthorized:
		code = exitUnauthenticated
	case http.StatusForbidden:
		code = exitForbidden
	case http.StatusNotFound:
		code = exitNotFound
	case http.StatusGatewayTimeout:
		code = exitTimeout
	case http.StatusServiceUnavailable:
		if strings.Contains(message, "not bound") {
			code = exitUnauthenticated
		}
	}
	return &cliError{code: code, message: fmt.Sprintf("hub returned %d: %s", status, message)}
}

func (env *cliEnv) getJSON(path string, target any) *cliError {
	payload, err := env.request(http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(payload, target); err != nil {
		return &cliError{code: exitToolError, message: "unexpected hub response: " + err.Error()}
	}
	return nil
}

func (env *cliEnv) toolPath(name string) string {
	return "/tools/" + url.PathEscape(name)
}

// ---------- commands ----------

func (env *cliEnv) showUsage() *cliError {
	fmt.Fprint(env.stdout, cliUsage)
	return nil
}

func (env *cliEnv) showMe() *cliError {
	var me hubMeResponse
	if err := env.getJSON("/me", &me); err != nil {
		return err
	}
	if env.opts.json {
		env.printJSON(me)
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	fmt.Fprintf(env.stdout, "  session:  %s\n    agent:  %s\n  sandbox:  %s (%s)\n    tools:  %d\n  expires:  %s\n",
		me.SessionID, me.AgentName, me.SandboxID, me.SandboxState, me.ToolCount, me.ExpiresAt)
	return nil
}

func (env *cliEnv) showCatalog() *cliError {
	var catalog hubCatalogResponse
	if err := env.getJSON("/catalog", &catalog); err != nil {
		return err
	}
	catalog.Tools = filterExposed(catalog.Tools)
	if env.opts.json {
		env.printJSON(catalog)
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	fmt.Fprintf(env.stdout, "  session: %s   agent: %s   sandbox: %s (%s)\n  tools: %d\n\n",
		catalog.SessionID, catalog.AgentName, catalog.SandboxID, catalog.SandboxState, len(catalog.Tools))
	env.renderToolsByKind(catalog.Tools)
	return nil
}

func (env *cliEnv) showTools() *cliError {
	query := strings.Join(env.opts.positional, " ")
	path := "/tools"
	params := url.Values{}
	if query != "" {
		params.Set("query", query)
	}
	if env.opts.kind != "" {
		params.Set("kind", env.opts.kind)
	}
	if encoded := params.Encode(); encoded != "" {
		path += "?" + encoded
	}
	var response hubToolsResponse
	if err := env.getJSON(path, &response); err != nil {
		return err
	}
	response.Tools = filterExposed(response.Tools)
	if env.opts.json {
		env.printJSON(response)
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	if len(response.Tools) == 0 {
		fmt.Fprintln(env.stdout, "  (no matching tools)")
		return nil
	}
	env.renderTools(response.Tools)
	return nil
}

func (env *cliEnv) describeTool() *cliError {
	if len(env.opts.positional) != 1 {
		return usageError("describe expects exactly one <tool> name")
	}
	var detail hubToolDetail
	if err := env.getJSON(env.toolPath(env.opts.positional[0]), &detail); err != nil {
		return err
	}
	if env.opts.json {
		env.printJSON(detail)
		return nil
	}
	if env.opts.raw {
		fmt.Fprintln(env.stdout, detail.InputSchema)
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	fmt.Fprintf(env.stdout, "  %s\n    kind:        %s\n    group:       %s\n    path:        %s\n"+
		"    ref_id:      %s\n    callable:    %t\n    timeout:     %ds\n",
		detail.Name, detail.Kind, detail.Group, detail.Path, detail.RefID, detail.Callable, detail.TimeoutSeconds)
	if detail.Description != "" {
		fmt.Fprintf(env.stdout, "    description: %s\n", detail.Description)
	}
	if detail.InputSchema != "" {
		fmt.Fprintf(env.stdout, "  input_schema:\n%s", indentJSON(detail.InputSchema, "    "))
	}
	return nil
}

func (env *cliEnv) callTool() *cliError {
	if len(env.opts.positional) != 1 {
		return usageError("call expects exactly one <tool> name")
	}
	name := env.opts.positional[0]
	arguments, err := env.buildArguments(name)
	if err != nil {
		return err
	}
	response, callErr := env.callToolWithArguments(name, arguments)
	if callErr != nil {
		return callErr
	}
	if response.IsError {
		env.printCall(response)
		return &cliError{code: exitToolError, message: firstNonBlank(response.ErrorMessage, "tool returned an error"), reported: true}
	}
	if response.Status == "pending" {
		env.printCall(response)
		return &cliError{code: exitTimeout, message: "still running; task_id=" + response.TaskID, reported: true}
	}
	if response.Status == "timeout" {
		env.printCall(response)
		return &cliError{code: exitTimeout, message: firstNonBlank(response.ErrorMessage, "tool call timed out"), reported: true}
	}
	env.printCall(response)
	return nil
}

// callToolWithArguments posts one call and follows an async task to its terminal response. Reporting stays with the
// caller, because a dataset operation prints its payload where a call prints the whole envelope.
func (env *cliEnv) callToolWithArguments(name, arguments string) (*hubCallResponse, *cliError) {
	env.metadata("calling %s ...", name)
	body, _ := json.Marshal(map[string]any{"arguments": arguments, "timeout_seconds": env.opts.timeout})
	deadline := time.Now().Add(time.Duration(env.opts.timeout) * time.Second)
	payload, requestErr := env.request(http.MethodPost, env.toolPath(name)+"/call", body)
	if requestErr != nil {
		return nil, requestErr
	}
	response := &hubCallResponse{}
	if unmarshalErr := json.Unmarshal(payload, response); unmarshalErr != nil {
		return nil, &cliError{code: exitToolError, message: "unexpected hub response: " + unmarshalErr.Error()}
	}
	for response.Status == "pending" && response.TaskID != "" && time.Now().Before(deadline) {
		time.Sleep(2 * time.Second)
		var task hubCallResponse
		if pollErr := env.getJSON("/tasks/"+url.PathEscape(response.TaskID), &task); pollErr != nil {
			return nil, pollErr
		}
		response = &task
	}
	return response, nil
}

func (env *cliEnv) printCall(response *hubCallResponse) {
	env.truncateCall(response)
	if env.opts.json {
		env.printJSON(response)
		return
	}
	if env.opts.quiet {
		return
	}
	if env.opts.raw {
		fmt.Fprint(env.stdout, ensureTrailingNewline(response.Text))
		return
	}
	fmt.Fprint(env.stdout, ensureTrailingNewline(response.Text))
	env.metadata("duration: %dms, status: %s", response.DurationMs, response.Status)
}

func (env *cliEnv) truncateCall(response *hubCallResponse) {
	limit := env.opts.maxOutput
	truncated := false
	if len(response.Text) > limit {
		response.Text = response.Text[:limit]
		truncated = true
	}
	if truncated {
		env.metadata("warning: output truncated at %d chars", limit)
	}
}

func ensureTrailingNewline(text string) string {
	if text == "" || strings.HasSuffix(text, "\n") {
		return text
	}
	return text + "\n"
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
