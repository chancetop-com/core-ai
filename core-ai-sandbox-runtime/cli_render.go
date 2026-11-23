package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

type hubToolSummary struct {
	Name        string `json:"name"`
	Kind        string `json:"kind"`
	Group       string `json:"group,omitempty"`
	Path        string `json:"path,omitempty"`
	RefID       string `json:"ref_id,omitempty"`
	Description string `json:"description,omitempty"`
	Exposure    string `json:"exposure,omitempty"`
}

type hubToolDetail struct {
	Name           string `json:"name"`
	Kind           string `json:"kind"`
	Group          string `json:"group,omitempty"`
	Path           string `json:"path,omitempty"`
	RefID          string `json:"ref_id,omitempty"`
	Description    string `json:"description,omitempty"`
	Exposure       string `json:"exposure,omitempty"`
	Callable       bool   `json:"callable"`
	TimeoutSeconds int    `json:"timeout_seconds"`
	InputSchema    string `json:"input_schema,omitempty"`
}

type hubMeResponse struct {
	SessionID       string `json:"session_id"`
	AgentName       string `json:"agent_name"`
	SandboxID       string `json:"sandbox_id"`
	SandboxState    string `json:"sandbox_state"`
	ExpiresAt       string `json:"expires_at,omitempty"`
	ToolCount       int    `json:"tool_count"`
	ContractVersion string `json:"contract_version,omitempty"`
}

type hubCatalogResponse struct {
	SessionID    string            `json:"session_id"`
	AgentName    string            `json:"agent_name"`
	SandboxID    string            `json:"sandbox_id"`
	SandboxState string            `json:"sandbox_state"`
	ExpiresAt    string            `json:"expires_at,omitempty"`
	Groups       []hubCatalogGroup `json:"groups"`
	Tools        []hubToolSummary  `json:"tools"`
	// Datasets is nil on a server older than the datasets section; an empty list means the session has no bindings.
	Datasets *[]hubCatalogDataset `json:"datasets,omitempty"`
}

type hubCatalogDataset struct {
	DatasetID   string           `json:"dataset_id"`
	Name        string           `json:"name"`
	Type        string           `json:"type"`
	Permission  string           `json:"permission"`
	Description string           `json:"description,omitempty"`
	Schema      []hubSchemaField `json:"schema,omitempty"`
}

type hubSchemaField struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Label string `json:"label,omitempty"`
}

// hubDatasetListResponse is what `dataset list --json` prints, identical to the local CLI's list payload.
type hubDatasetListResponse struct {
	SessionID string              `json:"session_id"`
	Datasets  []hubCatalogDataset `json:"datasets"`
}

type hubCatalogGroup struct {
	Kind  string `json:"kind"`
	Group string `json:"group,omitempty"`
	Path  string `json:"path,omitempty"`
	Count int    `json:"count"`
}

type hubToolsResponse struct {
	Query string           `json:"query,omitempty"`
	Kind  string           `json:"kind,omitempty"`
	Total int              `json:"total"`
	Tools []hubToolSummary `json:"tools"`
}

type hubContentPart struct {
	Type     string `json:"type,omitempty"`
	Text     string `json:"text,omitempty"`
	MimeType string `json:"mime_type,omitempty"`
	Data     string `json:"data,omitempty"`
}

type hubCallResponse struct {
	CallID       string           `json:"call_id"`
	Status       string           `json:"status"`
	Success      bool             `json:"success"`
	IsError      bool             `json:"is_error"`
	Text         string           `json:"text,omitempty"`
	Content      []hubContentPart `json:"content,omitempty"`
	DurationMs   int64            `json:"duration_ms"`
	TaskID       string           `json:"task_id,omitempty"`
	ErrorCode    string           `json:"error_code,omitempty"`
	ErrorMessage string           `json:"error_message,omitempty"`
	LlmUsage     *hubLlmUsage     `json:"llm_usage,omitempty"`
}

type hubLlmUsage struct {
	Model        string  `json:"model,omitempty"`
	InputTokens  int     `json:"input_tokens,omitempty"`
	OutputTokens int     `json:"output_tokens,omitempty"`
	Cost         float64 `json:"cost,omitempty"`
}

// datasetBindings returns the catalog's datasets section, nil while the server is older than the section itself.
func (catalog *hubCatalogResponse) datasetBindings() []hubCatalogDataset {
	if catalog.Datasets == nil {
		return nil
	}
	return *catalog.Datasets
}

// nonNilDatasets keeps a printed array iterable without a null check, the way the hub reports its own section.
func nonNilDatasets(datasets []hubCatalogDataset) []hubCatalogDataset {
	if datasets == nil {
		return []hubCatalogDataset{}
	}
	return datasets
}

// filterExposed drops hidden tools. The server already leaves them out of the catalog; this keeps
// the CLI honest if one ever slips through, and matches the Python SDK.
func filterExposed(tools []hubToolSummary) []hubToolSummary {
	exposed := make([]hubToolSummary, 0, len(tools))
	for _, tool := range tools {
		if tool.Exposure == "hidden" {
			continue
		}
		exposed = append(exposed, tool)
	}
	return exposed
}

// renderTools prints one row per tool; kind headers come from the server's own ordering.
func (env *cliEnv) renderTools(tools []hubToolSummary) {
	width := 0
	for _, tool := range tools {
		if len(tool.Name) > width {
			width = len(tool.Name)
		}
	}
	if width > 48 {
		width = 48
	}
	env.renderToolRows(tools, width)
}

func (env *cliEnv) renderToolsByKind(tools []hubToolSummary) {
	width := 0
	for _, tool := range tools {
		if len(tool.Name) > width {
			width = len(tool.Name)
		}
	}
	if width > 48 {
		width = 48
	}
	lastKind := ""
	for _, tool := range tools {
		if tool.Kind != lastKind {
			kind := tool.Kind
			if kind == "" {
				kind = "other"
			}
			count := 0
			for _, candidate := range tools {
				if candidate.Kind == tool.Kind {
					count++
				}
			}
			fmt.Fprintf(env.stdout, "%s (%d tools)\n", kind, count)
			lastKind = tool.Kind
		}
		fmt.Fprintln(env.stdout, toolRow(tool, width))
	}
}

func (env *cliEnv) renderToolRows(tools []hubToolSummary, width int) {
	for _, tool := range tools {
		fmt.Fprintln(env.stdout, toolRow(tool, width))
	}
}

func toolRow(tool hubToolSummary, width int) string {
	description := tool.Description
	if tool.Path != "" {
		description = "[" + tool.Path + "] " + description
	}
	if len(description) > 70 {
		description = description[:70] + "..."
	}
	return "  " + padRight(tool.Name, width+2) + description
}

func padRight(value string, width int) string {
	if len(value) >= width {
		return value + "  "
	}
	return value + strings.Repeat(" ", width-len(value))
}

func indentJSON(raw, indent string) string {
	var value any
	if json.Unmarshal([]byte(raw), &value) != nil {
		return indent + raw + "\n"
	}
	encoded, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return indent + raw + "\n"
	}
	return indent + strings.ReplaceAll(string(encoded), "\n", "\n"+indent) + "\n"
}

// ---------- call arguments ----------

// buildArguments merges --args, --args-file and --arg (later sources win) and coerces --arg
// values using the tool's input_schema. The server owns validation; this is best effort.
func (env *cliEnv) buildArguments(toolName string) (string, *cliError) {
	merged := map[string]any{}
	if env.opts.argsJSON != "" {
		parsed, err := parseJSONObject(env.opts.argsJSON)
		if err != nil {
			return "", usageError("--args must be a valid JSON object: %s", err)
		}
		merged = parsed
	}
	if env.opts.argsFile != "" {
		content, err := readArgsFile(env.opts.argsFile)
		if err != nil {
			return "", err
		}
		parsed, parseErr := parseJSONObject(content)
		if parseErr != nil {
			return "", usageError("--args-file must be a valid JSON object: %s", parseErr)
		}
		for key, value := range parsed {
			merged[key] = value
		}
	}
	if len(env.opts.argPairs) > 0 {
		assignments := make([]argAssignment, 0, len(env.opts.argPairs))
		for _, pair := range env.opts.argPairs {
			separator := strings.Index(pair, "=")
			if separator <= 0 {
				return "", usageError("--arg must be key=value, got: %s", pair)
			}
			assignments = append(assignments, argAssignment{
				name: strings.TrimSpace(pair[:separator]),
				raw:  pair[separator+1:],
			})
		}
		types := env.inputSchemaTypes(toolName)
		for _, assignment := range assignments {
			merged[assignment.name] = coerceArgValue(env, assignment.name, assignment.raw, types[assignment.name])
		}
	}
	encoded, err := json.Marshal(merged)
	if err != nil {
		return "", usageError("cannot encode arguments: %s", err)
	}
	return string(encoded), nil
}

type argAssignment struct {
	name string
	raw  string
}

func parseJSONObject(raw string) (map[string]any, error) {
	var parsed map[string]any
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return nil, err
	}
	if parsed == nil {
		return nil, fmt.Errorf("not an object")
	}
	return parsed, nil
}

func readArgsFile(path string) (string, *cliError) {
	return readOptionFile(path, "--args-file")
}

// readOptionFile reads a text option value from a file, where '-' means stdin so a long payload can be piped in.
func readOptionFile(path, flag string) (string, *cliError) {
	if path == "-" {
		content, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", usageError("cannot read stdin: %s", err)
		}
		return string(content), nil
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return "", usageError("cannot read %s: %s", flag, err)
	}
	return string(content), nil
}

// inputSchemaTypes fetches the tool's schema so --arg values can be typed; each --arg costs one
// extra round trip (same trade-off as core-ai-cli).
func (env *cliEnv) inputSchemaTypes(toolName string) map[string]string {
	types := map[string]string{}
	var detail hubToolDetail
	if err := env.getJSON(env.toolPath(toolName), &detail); err != nil || detail.InputSchema == "" {
		return types
	}
	var schema struct {
		Properties map[string]struct {
			Type string `json:"type"`
		} `json:"properties"`
	}
	if json.Unmarshal([]byte(detail.InputSchema), &schema) != nil {
		return types
	}
	for name, property := range schema.Properties {
		if property.Type != "" {
			types[name] = property.Type
		}
	}
	return types
}

func coerceArgValue(env *cliEnv, name, raw, kind string) any {
	if kind == "" {
		return raw
	}
	switch strings.ToLower(kind) {
	case "boolean":
		if strings.EqualFold(raw, "true") {
			return true
		}
		if strings.EqualFold(raw, "false") {
			return false
		}
	case "integer":
		if value, err := strconv.ParseInt(raw, 10, 64); err == nil {
			return value
		}
	case "number":
		if value, err := strconv.ParseFloat(raw, 64); err == nil {
			return value
		}
	case "object":
		var value map[string]any
		if json.Unmarshal([]byte(raw), &value) == nil {
			return value
		}
	case "array":
		var value []any
		if json.Unmarshal([]byte(raw), &value) == nil {
			return value
		}
	default:
		return raw
	}
	env.metadata("warning: could not parse --arg %s=%s as %s; sending as string", name, raw, kind)
	return raw
}
