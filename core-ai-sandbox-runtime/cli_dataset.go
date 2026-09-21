package main

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

// The dataset surface mirrors `core-ai-cli dataset` down to the flags, but reaches the server through the session's own
// tools rather than the hub's dataset endpoints: a sandbox holds no server credential, and the mounted tools are
// already the only write path the agent's dataset permissions open. A script therefore lands in the same place, with
// the same audit trail and the same refusal text, as the agent calling those tools.

const datasetUsage = `core-ai-sandbox dataset - read and write the datasets bound to this session

Usage:
  core-ai-sandbox dataset list                                    Datasets bound to this session
  core-ai-sandbox dataset show <dataset>                          One binding: type, permission, schema
  core-ai-sandbox dataset state get   <dataset> [--fields a,b]
  core-ai-sandbox dataset state set   <dataset> (--data <json> | --data-file <path|->)
  core-ai-sandbox dataset state patch <dataset> (--data <json> | --data-file <path|->)
  core-ai-sandbox dataset records query  <dataset> [--filter <json>] [--fields a,b]
                                         [--from <ISO>] [--to <ISO>] [--limit N] [--offset N]
  core-ai-sandbox dataset records insert <dataset> (--data <json> | --data-file <path|->)
  core-ai-sandbox dataset records update <dataset> --record-id <id> (--data <json> | --data-file <path|->)
  core-ai-sandbox dataset records delete <dataset> --record-id <id>

<dataset> is a dataset id, or a name that is unique inside this session: the server resolves it and answers with the
canonical id. Every operation is bounded by the datasets this session's agent was granted (READ/WRITE/FULL), so a
script can never reach further than the agent it runs for.

Options:
  --session ID      Accepted so a core-ai-cli command line runs here unchanged; it must name this sandbox's own
                    session, which the runtime decides and a script cannot change.
  --json, --raw     Print the operation's payload and nothing else (--quiet prints nothing)
  --timeout SEC     Wait budget for the underlying tool call (default 120, max 600)

Exit codes: 0 success  2 usage  3 not bound  6 timeout  1 anything the tool refused
`

// datasetFlagNames are captured by the shared option parser but consumed only by the dataset command, so every other
// command still rejects them as unknown options.
var datasetFlagNames = []string{"--session", "--fields", "--data", "--data-file", "--record-id", "--filter", "--from", "--to", "--limit", "--offset"}

func (env *cliEnv) showDatasetUsage() *cliError {
	fmt.Fprint(env.stdout, datasetUsage)
	return nil
}

func (env *cliEnv) datasetCommand() *cliError {
	if len(env.opts.positional) == 0 {
		return usageError("dataset expects a subcommand: list|show|state|records (see --help)")
	}
	if err := env.verifySessionFlag(); err != nil {
		return err
	}
	switch env.opts.positional[0] {
	case "list":
		return env.datasetList()
	case "show":
		return env.datasetShow()
	case "state":
		return env.datasetState()
	case "records":
		return env.datasetRecords()
	default:
		return usageError("unknown dataset subcommand: %s", env.opts.positional[0])
	}
}

// A sandbox only ever acts on the session it is bound to. --session exists so a command line written for core-ai-cli
// runs unchanged; an id that disagrees is refused instead of ignored, because the write would land on the bound
// session while the caller believed it named another one.
func (env *cliEnv) verifySessionFlag() *cliError {
	requested, ok := env.flag("--session")
	if !ok {
		return nil
	}
	requested = strings.TrimSpace(requested)
	if requested == "" {
		return usageError("--session requires a session id")
	}
	var me hubMeResponse
	if err := env.getJSON("/me", &me); err != nil {
		return err
	}
	if requested != me.SessionID {
		return usageError("--session %s does not match this sandbox's session %s", requested, me.SessionID)
	}
	return nil
}

// datasetCatalog reads the catalog's datasets section. The section is where a script discovers what it may touch, so
// list and show read it directly instead of calling a tool.
func (env *cliEnv) datasetCatalog() (*hubCatalogResponse, *cliError) {
	catalog := &hubCatalogResponse{}
	if err := env.getJSON("/catalog", catalog); err != nil {
		return nil, err
	}
	return catalog, nil
}

func (env *cliEnv) datasetList() *cliError {
	if len(env.opts.positional) != 1 {
		return usageError("dataset list takes no arguments")
	}
	if err := env.checkFlags("--session"); err != nil {
		return err
	}
	catalog, err := env.datasetCatalog()
	if err != nil {
		return err
	}
	datasets := catalog.datasetBindings()
	if env.opts.json {
		env.printJSON(hubDatasetListResponse{SessionID: catalog.SessionID, Datasets: nonNilDatasets(datasets)})
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	if catalog.Datasets == nil {
		fmt.Fprintln(env.stdout, "  (this hub reports no datasets section; the server may predate it)")
		return nil
	}
	env.renderDatasetList(datasets)
	return nil
}

func (env *cliEnv) datasetShow() *cliError {
	if len(env.opts.positional) != 2 {
		return usageError("dataset show expects exactly one <dataset>")
	}
	if err := env.checkFlags("--session"); err != nil {
		return err
	}
	catalog, err := env.datasetCatalog()
	if err != nil {
		return err
	}
	view, resolveErr := resolveCatalogDataset(catalog.datasetBindings(), env.opts.positional[1])
	if resolveErr != nil {
		return resolveErr
	}
	if env.opts.json {
		env.printJSON(view)
		return nil
	}
	if env.opts.quiet {
		return nil
	}
	env.renderDatasetDetail(view)
	return nil
}

// resolveCatalogDataset picks the dataset behind a reference out of one session's bindings, using the rules the server
// applies to a tool call: the id wins, a name works only while it is unique inside the session, and an ambiguous name
// fails closed instead of silently picking one.
func resolveCatalogDataset(datasets []hubCatalogDataset, ref string) (hubCatalogDataset, *cliError) {
	for _, view := range datasets {
		if ref == view.DatasetID {
			return view, nil
		}
	}
	matches := make([]hubCatalogDataset, 0, 1)
	for _, view := range datasets {
		if ref == view.Name {
			matches = append(matches, view)
		}
	}
	switch len(matches) {
	case 1:
		return matches[0], nil
	case 0:
		return hubCatalogDataset{}, &cliError{code: exitNotFound, message: "dataset not found in this session: " + ref}
	default:
		candidates := make([]string, 0, len(matches))
		for _, view := range matches {
			candidates = append(candidates, view.DatasetID)
		}
		return hubCatalogDataset{}, usageError("dataset name is ambiguous, pass the dataset id: %s (candidates: %s)",
			ref, strings.Join(candidates, ", "))
	}
}

func (env *cliEnv) datasetState() *cliError {
	if len(env.opts.positional) != 3 {
		return usageError("dataset state expects: state <get|set|patch> <dataset>")
	}
	leaf, ref := env.opts.positional[1], env.opts.positional[2]
	switch leaf {
	case "get":
		if err := env.checkFlags("--session", "--fields"); err != nil {
			return err
		}
		arguments := map[string]any{"dataset_id": ref}
		if fields, ok := env.flag("--fields"); ok && strings.TrimSpace(fields) != "" {
			arguments["fields"] = fields
		}
		return env.datasetOperation("get_session_state", arguments, false)
	case "set", "patch":
		if err := env.checkFlags("--session", "--data", "--data-file"); err != nil {
			return err
		}
		data, err := env.datasetData()
		if err != nil {
			return err
		}
		tool := "set_session_state"
		if leaf == "patch" {
			tool = "update_session_state"
		}
		return env.datasetOperation(tool, map[string]any{"dataset_id": ref, "data": data}, true)
	default:
		return usageError("unknown dataset state subcommand: %s", leaf)
	}
}

func (env *cliEnv) datasetRecords() *cliError {
	if len(env.opts.positional) != 3 {
		return usageError("dataset records expects: records <query|insert|update|delete> <dataset>")
	}
	leaf, ref := env.opts.positional[1], env.opts.positional[2]
	switch leaf {
	case "query":
		return env.datasetQuery(ref)
	case "insert":
		if err := env.checkFlags("--session", "--data", "--data-file"); err != nil {
			return err
		}
		data, err := env.datasetData()
		if err != nil {
			return err
		}
		return env.datasetOperation("insert_dataset_record", map[string]any{"dataset_id": ref, "data": data}, true)
	case "update":
		if err := env.checkFlags("--session", "--data", "--data-file", "--record-id"); err != nil {
			return err
		}
		recordID, err := env.requiredFlag("--record-id")
		if err != nil {
			return err
		}
		data, dataErr := env.datasetData()
		if dataErr != nil {
			return dataErr
		}
		return env.datasetOperation("update_dataset_record",
			map[string]any{"dataset_id": ref, "record_id": recordID, "data": data}, true)
	case "delete":
		if err := env.checkFlags("--session", "--record-id"); err != nil {
			return err
		}
		recordID, err := env.requiredFlag("--record-id")
		if err != nil {
			return err
		}
		return env.datasetOperation("delete_dataset_record", map[string]any{"dataset_id": ref, "record_id": recordID}, true)
	default:
		return usageError("unknown dataset records subcommand: %s", leaf)
	}
}

func (env *cliEnv) datasetQuery(ref string) *cliError {
	if err := env.checkFlags("--session", "--filter", "--fields", "--from", "--to", "--limit", "--offset"); err != nil {
		return err
	}
	arguments := map[string]any{"dataset_id": ref}
	for _, name := range []string{"--filter", "--fields", "--from", "--to"} {
		if value, ok := env.flag(name); ok && strings.TrimSpace(value) != "" {
			arguments[strings.TrimPrefix(name, "--")] = value
		}
	}
	for _, name := range []string{"--limit", "--offset"} {
		value, ok := env.flag(name)
		if !ok || strings.TrimSpace(value) == "" {
			continue
		}
		number, convErr := strconv.Atoi(strings.TrimSpace(value))
		if convErr != nil {
			return usageError("%s must be an integer, got: %s", name, value)
		}
		arguments[strings.TrimPrefix(name, "--")] = number
	}
	return env.datasetOperation("query_dataset_records", arguments, false)
}

// datasetOperation runs one dataset operation through its tool. The payload is the operation's own JSON, so it stays
// the only thing on stdout; a write adds a one-line outcome summary on stderr.
func (env *cliEnv) datasetOperation(tool string, arguments map[string]any, write bool) *cliError {
	encoded, encodeErr := json.Marshal(arguments)
	if encodeErr != nil {
		return usageError("cannot encode arguments: %s", encodeErr)
	}
	response, callErr := env.callToolWithArguments(tool, string(encoded))
	if callErr != nil {
		return callErr
	}
	switch {
	case response.IsError:
		return &cliError{code: exitToolError, message: firstNonBlank(response.ErrorMessage, "tool returned an error")}
	case response.Status == "pending":
		return &cliError{code: exitTimeout, message: "still running; task_id=" + response.TaskID}
	case response.Status == "timeout":
		return &cliError{code: exitTimeout, message: firstNonBlank(response.ErrorMessage, "tool call timed out")}
	}
	env.printDatasetPayload(response.Text)
	if write {
		env.datasetSummary(response.Text)
	}
	return nil
}

// datasetData reads the write payload from --data or --data-file ('-' is stdin) and parses it, because the record tools
// take the value as a JSON object rather than as text.
func (env *cliEnv) datasetData() (map[string]any, *cliError) {
	inline, hasInline := env.flag("--data")
	inline = strings.TrimSpace(inline)
	file, hasFile := env.flag("--data-file")
	file = strings.TrimSpace(file)
	if hasInline && inline != "" && hasFile && file != "" {
		return nil, usageError("pass either --data or --data-file, not both")
	}
	var text string
	switch {
	case hasInline && inline != "":
		if inline == "-" {
			content, err := readOptionFile("-", "--data")
			if err != nil {
				return nil, err
			}
			text = content
		} else {
			text = inline
		}
	case hasFile && file != "":
		content, err := readOptionFile(file, "--data-file")
		if err != nil {
			return nil, err
		}
		text = content
	default:
		return nil, usageError("missing data: pass --data <json> or --data-file <path|->")
	}
	parsed, parseErr := parseJSONObject(text)
	if parseErr != nil {
		return nil, usageError("--data must be a JSON object: %s", parseErr)
	}
	return parsed, nil
}

func (env *cliEnv) printDatasetPayload(payload string) {
	if env.opts.quiet {
		return
	}
	if env.opts.json || env.opts.raw {
		fmt.Fprint(env.stdout, ensureTrailingNewline(payload))
		return
	}
	fmt.Fprint(env.stdout, indentJSON(payload, "  "))
}

// datasetSummary reports what a write did, derived from the payload the server returned.
func (env *cliEnv) datasetSummary(payload string) {
	if env.opts.quiet || env.opts.json || env.opts.raw {
		return
	}
	var body map[string]any
	if json.Unmarshal([]byte(payload), &body) != nil {
		return
	}
	status, _ := body["status"].(string)
	if status == "" {
		return
	}
	fields := datasetFieldNames(body["updated_fields"])
	if len(fields) == 0 {
		fields = datasetFieldNames(body["inserted_fields"])
	}
	parts := make([]string, 0, 2)
	if len(fields) > 0 {
		parts = append(parts, "fields: "+strings.Join(fields, ", "))
	}
	if recordID, _ := body["record_id"].(string); recordID != "" {
		parts = append(parts, "record: "+recordID)
	}
	if len(parts) == 0 {
		env.metadata("%s", status)
		return
	}
	env.metadata("%s (%s)", status, strings.Join(parts, "; "))
}

func datasetFieldNames(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}
	names := make([]string, 0, len(items))
	for _, item := range items {
		if name, isText := item.(string); isText && name != "" {
			names = append(names, name)
		}
	}
	return names
}

func (env *cliEnv) renderDatasetList(datasets []hubCatalogDataset) {
	if len(datasets) == 0 {
		fmt.Fprintln(env.stdout, "  (no datasets bound to this session)")
		return
	}
	nameWidth := 1
	for _, view := range datasets {
		if len(view.Name)+2 > nameWidth {
			nameWidth = len(view.Name) + 2
		}
	}
	if nameWidth > 32 {
		nameWidth = 32
	}
	for _, view := range datasets {
		line := "  " + padRight(nz(view.Name), nameWidth) + padRight(nz(view.Type), 9) +
			padRight(nz(view.Permission), 9) + padRight(schemaSummary(view.Schema), 12) + nz(view.DatasetID)
		if strings.TrimSpace(view.Description) != "" {
			line += "  " + view.Description
		}
		fmt.Fprintln(env.stdout, line)
	}
}

func (env *cliEnv) renderDatasetDetail(view hubCatalogDataset) {
	fmt.Fprintf(env.stdout, "  %s\n    dataset_id:  %s\n    type:        %s\n    permission:  %s\n",
		nz(view.Name), nz(view.DatasetID), nz(view.Type), nz(view.Permission))
	if strings.TrimSpace(view.Description) != "" {
		fmt.Fprintf(env.stdout, "    description: %s\n", view.Description)
	}
	if len(view.Schema) == 0 {
		fmt.Fprintln(env.stdout, "    schema:      (none)")
		return
	}
	fmt.Fprintln(env.stdout, "    schema:")
	for _, field := range view.Schema {
		fmt.Fprintf(env.stdout, "      %s%s\n", padRight(nz(field.Name), 24), nz(field.Type))
	}
}

func schemaSummary(schema []hubSchemaField) string {
	switch len(schema) {
	case 0:
		return "no schema"
	case 1:
		return "1 field"
	default:
		return strconv.Itoa(len(schema)) + " fields"
	}
}

func nz(value string) string {
	if strings.TrimSpace(value) == "" {
		return "-"
	}
	return value
}
