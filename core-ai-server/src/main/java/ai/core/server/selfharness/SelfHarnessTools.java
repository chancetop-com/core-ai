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
import java.util.List;

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

        registerAgentTools(builder, tools);
        registerSkillTools(builder, tools);
        registerDatasetTools(builder, tools);
        registerToolTools(builder, tools);
        registerSessionTraceTools(builder, tools);

        toolRegistryService.registerBuiltinToolGroup(TOOL_ENTRY_ID, "Self Harness",
                "Tools for managing agents, skills, datasets, tool registries, and inspecting session traces",
                tools);
    }

    private void registerAgentTools(SelfHarnessToolBuilder builder, List<ToolCall> tools) {
        tools.add(builder.build("list_agents", "List all agents with pagination and filtering.",
                ListAgentsRequest.class, false));
        tools.add(builder.build("create_agent", "Create a new agent draft.",
                CreateAgentRequest.class, false));
        tools.add(builder.buildWithPathParamOnly("get_agent", "Get agent detail by ID.",
                "id", "Agent ID"));
        tools.add(builder.build("update_agent", "Update an existing agent draft.",
                UpdateAgentRequest.class, true));
        tools.add(builder.buildWithPathParamOnly("publish_agent", "Publish an agent draft by ID.",
                "id", "Agent ID"));
    }

    private void registerSkillTools(SelfHarnessToolBuilder builder, List<ToolCall> tools) {
        tools.add(builder.build("list_skills", "List registered skills with filtering and search.",
                ListSkillsRequest.class, false));
        tools.add(builder.buildWithPathParamOnly("get_skill", "Get skill detail by ID.",
                "id", "Skill ID"));
        tools.add(builder.build("update_skill", "Update a skill's description, content, or allowed tools.",
                UpdateSkillRequest.class, true));
        tools.add(builder.buildWithPathParamOnly("delete_skill", "Delete a skill by ID.",
                "id", "Skill ID"));
        tools.add(builder.buildWithPathParamOnly("download_skill", "Download skill content including all resources.",
                "id", "Skill ID"));
    }

    private void registerDatasetTools(SelfHarnessToolBuilder builder, List<ToolCall> tools) {
        tools.add(builder.build("list_datasets", "List datasets with search and pagination.",
                ListDatasetsRequest.class, false));
        tools.add(builder.buildWithPathParamOnly("get_dataset", "Get dataset detail by ID.",
                "id", "Dataset ID"));
        tools.add(builder.buildCustom("list_dataset_records",
                "Query records within a dataset. Required: id (dataset ID). Optional: limit, offset, agent_id.",
                List.of(
                        ToolCallParameter.builder().name("id").description("Dataset ID").type(ToolCallParameterType.STRING).required(true).build(),
                        ToolCallParameter.builder().name("agent_id").description("Filter by agent ID").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("limit").description("Max records to return (default 100)").type(ToolCallParameterType.INTEGER).build(),
                        ToolCallParameter.builder().name("offset").description("Pagination offset").type(ToolCallParameterType.INTEGER).build()
                )));
    }

    private void registerToolTools(SelfHarnessToolBuilder builder, List<ToolCall> tools) {
        tools.add(builder.build("list_tools", "List tool registry entries, optionally filtered by category.",
                ListToolsRequest.class, false));
    }

    private void registerSessionTraceTools(SelfHarnessToolBuilder builder, List<ToolCall> tools) {
        tools.add(builder.buildCustom("get_session_history",
                "Get the full message history for a session, including content, thinking, tool calls, and trace IDs.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Session ID").type(ToolCallParameterType.STRING).required(true).build()
                )));
        tools.add(builder.buildCustom("list_traces",
                "List execution traces, filtered by session, agent name, status, or source. Useful for inspecting agent performance.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Filter by session ID").type(ToolCallParameterType.STRING).required(true).build(),
                        ToolCallParameter.builder().name("agent_name").description("Filter by agent name").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("status").description("Filter by status (e.g. SUCCESS, FAILED)").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("source").description("Filter by source (e.g. chat, api, scheduled)").type(ToolCallParameterType.STRING).build(),
                        ToolCallParameter.builder().name("limit").description("Max results (default 20)").type(ToolCallParameterType.INTEGER).build(),
                        ToolCallParameter.builder().name("offset").description("Pagination offset").type(ToolCallParameterType.INTEGER).build()
                )));
        tools.add(builder.buildCustom("get_trace",
                "Get a single trace with full input/output details.",
                List.of(
                        ToolCallParameter.builder().name("trace_id").description("Trace ID").type(ToolCallParameterType.STRING).required(true).build()
                )));
        tools.add(builder.buildCustom("get_trace_spans",
                "Get all spans (sub-operations) for a trace, showing detailed timing and token usage.",
                List.of(
                        ToolCallParameter.builder().name("trace_id").description("Trace ID").type(ToolCallParameterType.STRING).required(true).build()
                )));
        tools.add(builder.buildCustom("get_session_trace_summary",
                "Get an aggregated summary of all traces within a session: total tokens, cost, duration, error count.",
                List.of(
                        ToolCallParameter.builder().name("session_id").description("Session ID").type(ToolCallParameterType.STRING).required(true).build()
                )));
    }
}
