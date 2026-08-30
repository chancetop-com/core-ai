package ai.core.server.selfharness;

import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.dataset.ListDatasetsRequest;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.UpdateSkillRequest;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the {@code self-harness} builtin tool group. Each tool's
 * parameter schema is auto-derived from the request DTO's annotations
 * ({@code @Property}, {@code @QueryParam}) rather than handwritten
 * per tool class.
 *
 * @author stephen
 */
public class SelfHarnessTools {
    public static final String TOOL_SET_NAME = "self-harness";
    private static final String TOOL_ENTRY_ID = "builtin:" + TOOL_SET_NAME;

    @Inject
    SelfHarnessApiCaller caller;
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var builder = new SelfHarnessToolBuilder(caller);
        var tools = new ArrayList<ToolCall>();
        var groups = new LinkedHashMap<String, String>();

        registerAgentTools(builder, tools, groups);
        registerModelTools(builder, tools, groups);
        registerSkillTools(builder, tools, groups);
        registerDatasetTools(builder, tools, groups);
        registerToolTools(builder, tools, groups);
        registerSessionTraceTools(builder, tools, groups);
        registerProjectQueryTools(builder, tools, groups);

        toolRegistryService.registerBuiltinToolGroup(TOOL_ENTRY_ID, "Self Harness",
                "Tools for managing agents, skills, datasets, tool registries, and inspecting session traces",
                tools, groups);
    }

    private void registerAgentTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.build("list_agents", "List all agents with pagination and filtering.",
                ListAgentsRequest.class, false));
        tools.add(builder.build("create_agent", "Create a new agent draft. The model and multi_modal_model fields must be exact "
                        + "model_id values taken from list_models — call it first instead of guessing a model name.",
                CreateAgentRequest.class, false));
        tools.add(builder.buildWithPathParamOnly("get_agent", "Get agent detail by ID.",
                "id", "Agent ID"));
        tools.add(builder.build("update_agent", "Update an existing agent draft. When changing model or multi_modal_model, "
                        + "call list_models first and use an exact model_id from it.",
                UpdateAgentRequest.class, true));
        tools.add(builder.buildWithPathParamOnly("publish_agent", "Publish an agent draft by ID.",
                "id", "Agent ID"));
        group("Agents", tools, groups);
    }

    private void registerModelTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.buildCustom("list_models",
                "List every model registered in the gateway with the endpoints each one serves "
                        + "(chat.completions, responses, image.generations, image.edits, video.generations), its provider, "
                        + "its vision/video/file support, and the current system default models. "
                        + "ALWAYS call this before setting a model on an agent: the model and multi_modal_model fields of "
                        + "create_agent/update_agent must be an exact model_id from this list. Never guess or invent a model "
                        + "name — when the user names a model informally (e.g. \"gemini omni 1.1\", \"the seedream one\"), look "
                        + "it up here and use the exact model_id. If nothing matches, say so instead of picking something close.",
                List.of(
                        string("endpoint_type", "Filter by endpoint: chat, responses, image, video, or a full endpoint id such as image.edits", null),
                        string("keyword", "Case-insensitive match against model_id, display name or provider name", null))));
        group("Models", tools, groups);
    }

    private void registerSkillTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.build("list_skills", "List registered skills with filtering and search.",
                ListSkillsRequest.class, false));
        tools.add(builder.buildCustom("create_skill",
                "Create a new skill in the skill catalog from SKILL.md content. "
                        + "Creating a good skill is a complex workflow — do NOT write SKILL.md directly. "
                        + "Delegate the research and drafting to a sub-agent via the `task` tool first: launch subagent_type=general-purpose-agent (or deep-research-agent for broader research) with a prompt that requires it to "
                        + "use web_search/web_fetch to research how well-crafted skills of this kind are structured — collect SOPs, best practices and common pitfalls from public skill collections (e.g. skills.sh, awesome-claude-skills, GitHub) and from existing skills in this catalog (list_skills/get_skill) — "
                        + "then design the skill and return the final SKILL.md content (YAML frontmatter with name and description, then step-by-step instructions) plus any resource files. "
                        + "Review the sub-agent's output, then call this tool with the produced content. "
                        + "content must be markdown with YAML frontmatter containing name and description (e.g. ---\\nname: my-skill\\ndescription: what it does\\n---\\ninstructions); qualified name becomes namespace/name. "
                        + "After creating, attach the returned id via skill_ids in create_agent/update_agent so agents can use it.",
                List.of(
                        ToolCallParameter.builder().name("namespace").description("Skill namespace (e.g. org/team name)").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
                        ToolCallParameter.builder().name("content").description("Full SKILL.md content with YAML frontmatter (name, description required)").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
                        ToolCallParameter.builder().name("resources").description("Optional resource files referenced by the skill, each {path, content}").type(ToolCallParameterType.LIST).itemType(Map.class).build()
                )));
        tools.add(builder.buildWithPathParamOnly("get_skill", "Get skill detail by ID.",
                "id", "Skill ID"));
        tools.add(builder.build("update_skill", "Update a skill's description, content, or allowed tools.",
                UpdateSkillRequest.class, true));
        tools.add(builder.buildWithPathParamOnly("delete_skill", "Delete a skill by ID.",
                "id", "Skill ID"));
        tools.add(builder.buildWithPathParamOnly("download_skill", "Download skill content including all resources.",
                "id", "Skill ID"));
        group("Skills", tools, groups);
    }

    private void registerDatasetTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.build("list_datasets", "List datasets with search and pagination.",
                ListDatasetsRequest.class, false));
        tools.add(builder.buildCustom("create_dataset",
                "Create a new dataset. Use when the agent needs to persist data across sessions and share it among all users of the agent — "
                        + "e.g. a resume/talent pool database, reviewed resume evaluations, interview results, product catalogs, ticket lists. "
                        + "Use type=SESSION for long conversations: each session gets its own state document (accessed via get_session_state/set_session_state/update_session_state) "
                        + "so the agent can persist progress, decisions and intermediate conclusions and does not forget key information in very long conversations. "
                        + "Otherwise GENERAL datasets hold records accessed via query/insert/update/delete_dataset_record. "
                        + "After creating, attach the returned id to the agent via dataset_config in create_agent/update_agent so the agent (and every user of it) can access the dataset.",
                List.of(
                        ToolCallParameter.builder().name("name").description("Dataset name").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
                        ToolCallParameter.builder().name("description").description("What data this dataset holds").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("type").description("GENERAL (default) or SESSION").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("schema").description("Optional field definitions, each {name, type: STRING|NUMBER|BOOLEAN, label}").type(ToolCallParameterType.LIST).itemType(ai.core.api.server.dataset.SchemaFieldView.class).build()
                )));
        tools.add(builder.buildWithPathParamOnly("get_dataset", "Get dataset detail by ID.",
                "id", "Dataset ID"));
        tools.add(builder.buildCustom("list_dataset_records",
                "Query records within a dataset. Required: id (dataset ID). Optional: limit, offset, agent_id.",
                List.of(
                        ToolCallParameter.builder().name("id").description("Dataset ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
                        ToolCallParameter.builder().name("agent_id").description("Filter by agent ID").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("limit").description("Max records to return (default 100)").type(ToolCallParameterType.INTEGER).build(),
                        ToolCallParameter.builder().name("offset").description("Pagination offset").type(ToolCallParameterType.INTEGER).build()
                )));
        group("Datasets", tools, groups);
    }

    private void registerToolTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.build("list_tools", "List tool registry entries, optionally filtered by category.",
                ListToolsRequest.class, false));
        group("Tools", tools, groups);
    }

    private void registerSessionTraceTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.buildCustom("get_session_history",
                "Get the full message history for a session, including content, thinking, tool calls, and trace IDs.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Session ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build()
                )));
        tools.add(builder.buildCustom("list_traces",
                "List execution traces, filtered by session, agent name, status, or source. Useful for inspecting agent performance.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Filter by session ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build(),
                        ToolCallParameter.builder().name("agent_name").description("Filter by agent name").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("status").description("Filter by status (e.g. SUCCESS, FAILED)").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("source").description("Filter by source (e.g. chat, api, scheduled)").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("limit").description("Max results (default 20)").type(ToolCallParameterType.INTEGER).build(),
                        ToolCallParameter.builder().name("offset").description("Pagination offset").type(ToolCallParameterType.INTEGER).build()
                )));
        tools.add(builder.buildCustom("get_trace",
                "Get a single trace with full input/output details.",
                List.of(
                        ToolCallParameter.builder().name("trace_id").description("Trace ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build()
                )));
        tools.add(builder.buildCustom("get_trace_spans",
                "Get all spans (sub-operations) for a trace, showing detailed timing and token usage.",
                List.of(
                        ToolCallParameter.builder().name("trace_id").description("Trace ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build()
                )));
        tools.add(builder.buildCustom("get_session_trace_summary",
                "Get an aggregated summary of all traces within a session: total tokens, cost, duration, error count.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Session ID").type(ToolCallParameterType.STRING).required(Boolean.TRUE).build()
                )));
        group("Sessions & Traces", tools, groups);
    }

    // ---- project material queries: combinable search conditions over the project's members,
    // with subject attribution as a filter; scoped to the executing user via the execution context ----

    private void registerProjectQueryTools(SelfHarnessToolBuilder builder, List<ToolCall> tools, Map<String, String> groups) {
        tools.add(builder.buildCustom("search_sessions",
                "Search chat sessions of a project's member agents with combinable filters. project_id selects the project (owner-scoped); agent_ids narrows the members; since is an ISO-8601 timestamp; subject_id filters sessions attributed to that subject; attributed=true lists only attributed sessions, false only unattributed ones. Returns id/title/agent/time/message_count (max 20).",
                List.of(
                        string("project_id", "Project ID", Boolean.TRUE),
                        list("agent_ids", "Member agent IDs (default: all members)"),
                        string("since", "Only sessions with last_message_at after this ISO-8601 timestamp", null),
                        string("subject_id", "Filter sessions attributed to this subject", null),
                        bool("attributed", "true=only attributed, false=only unattributed, omitted=any", null)),
                "searchSessions"));
        tools.add(builder.buildCustom("search_runs",
                "Search agent runs of a project's member agents with combinable filters (project_id, agent_ids, since, subject_id, attributed). Returns id/agent/status/input/output/time (max 20).",
                List.of(
                        string("project_id", "Project ID", Boolean.TRUE),
                        list("agent_ids", "Member agent IDs (default: all members)"),
                        string("since", "Only runs with started_at after this ISO-8601 timestamp", null),
                        string("subject_id", "Filter runs attributed to this subject", null),
                        bool("attributed", "true=only attributed, false=only unattributed, omitted=any", null)),
                "searchRuns"));
        tools.add(builder.buildCustom("search_workflow_runs",
                "Search workflow runs of a project's member workflows with combinable filters (project_id, workflow_ids, since, subject_id, attributed). Returns id/workflow/status/input/time (max 20).",
                List.of(
                        string("project_id", "Project ID", Boolean.TRUE),
                        list("workflow_ids", "Member workflow IDs (default: all members)"),
                        string("since", "Only runs with started_at after this ISO-8601 timestamp", null),
                        string("subject_id", "Filter runs attributed to this subject", null),
                        bool("attributed", "true=only attributed, false=only unattributed, omitted=any", null)),
                "searchWorkflowRuns"));
        tools.add(builder.buildCustom("list_files",
                "List the current user's files/artifacts (id, file_name, content_type, size, created_at), newest first.",
                List.of(
                        string("since", "Only files created after this ISO-8601 timestamp", null),
                        string("content_type", "Filter by content type (e.g. text/html)", null),
                        string("keyword", "Match file names containing this keyword", null),
                        integer("limit", "Max results (default 20, max 50)", null)),
                "listFiles"));
        tools.add(builder.buildCustom("get_file_content",
                "Read a file's content as text (HTML is converted to plain text, truncated). Only files owned by the current user are readable.",
                List.of(
                        string("file_id", "File ID", Boolean.TRUE)),
                "getFileContent"));
        group("Project Material", tools, groups);
    }

    /** Tags all tools added since the last group() call with the given subgroup label. */
    private void group(String label, List<ToolCall> tools, Map<String, String> groups) {
        for (var tool : tools) groups.putIfAbsent(tool.getName(), label);
    }

    private ToolCallParameter string(String name, String description, Boolean required) {
        return ToolCallParameter.builder().name(name).description(description).type(ToolCallParameterType.STRING).required(required).build();
    }

    private ToolCallParameter bool(String name, String description, Boolean required) {
        return ToolCallParameter.builder().name(name).description(description).type(ToolCallParameterType.BOOLEAN).required(required).build();
    }

    private ToolCallParameter integer(String name, String description, Boolean required) {
        return ToolCallParameter.builder().name(name).description(description).type(ToolCallParameterType.INTEGER).required(required).build();
    }

    private ToolCallParameter list(String name, String description) {
        return ToolCallParameter.builder().name(name).description(description).type(ToolCallParameterType.LIST).itemType(String.class).build();
    }
}
