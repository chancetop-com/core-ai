package ai.core.server.selfharness;

import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.dataset.ListDatasetsRequest;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.UpdateSkillRequest;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.session.ChatMessageService;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.SkillFilter;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.trace.service.TraceListFilter;
import ai.core.server.trace.service.TraceService;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Direct-method-call dispatcher for self-harness tools.
 * Mirrors {@link ai.core.mcp.server.apiserver.DynamicApiCaller} but calls
 * service-layer methods instead of making HTTP requests.
 *
 * @author stephen
 */
public class SelfHarnessApiCaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(SelfHarnessApiCaller.class);
    private static final String INTERNAL_USER = "internal";

    @Inject
    AgentDefinitionService agentService;
    @Inject
    SkillService skillService;
    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    TraceService traceService;

    public String callApi(String name, String args) {
        ActionLogContext.put("self-harness-call", name);
        LOGGER.info("self-harness call, name={}, args={}", name, args);
        try {
            var result = dispatch(name, args);
            return JSON.toJSON(result);
        } catch (Exception e) {
            LOGGER.error("self-harness call failed, name={}, args={}", name, args, e);
            return JSON.toJSON(Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    private Object dispatch(String name, String args) {
        return switch (name) {
            case "list_agents", "create_agent", "get_agent", "update_agent", "publish_agent" ->
                dispatchAgent(name, args);
            case "list_skills", "get_skill", "update_skill", "delete_skill", "download_skill" ->
                dispatchSkill(name, args);
            case "list_datasets", "get_dataset", "list_dataset_records" ->
                dispatchDataset(name, args);
            case "list_tools" ->
                dispatchTool(args);
            case "get_session_history", "list_traces", "get_trace", "get_trace_spans", "get_session_trace_summary" ->
                dispatchSessionTrace(name, args);
            default -> throw new IllegalArgumentException("Unknown self-harness operation: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object dispatchAgent(String name, String args) {
        return switch (name) {
            case "list_agents" -> {
                var req = JSON.fromJSON(ListAgentsRequest.class, args);
                yield agentService.list(INTERNAL_USER, req);
            }
            case "create_agent" -> {
                var req = JSON.fromJSON(CreateAgentRequest.class, args);
                yield agentService.create(req, INTERNAL_USER);
            }
            case "get_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield agentService.get((String) params.get("id"));
            }
            case "update_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var id = (String) params.remove("id");
                var req = JSON.fromJSON(UpdateAgentRequest.class, JSON.toJSON(params));
                yield agentService.update(id, req, INTERNAL_USER);
            }
            case "publish_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield agentService.publish((String) params.get("id"), INTERNAL_USER);
            }
            default -> throw new IllegalArgumentException("Unknown agent operation: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object dispatchSkill(String name, String args) {
        return switch (name) {
            case "list_skills" -> {
                var req = JSON.fromJSON(ListSkillsRequest.class, args);
                yield skillService.list(new SkillFilter(req.namespace, req.sourceType), null, req.query, req.searchIn, req.offset, req.limit);
            }
            case "get_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield skillService.get((String) params.get("id"));
            }
            case "update_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var id = (String) params.remove("id");
                var req = JSON.fromJSON(UpdateSkillRequest.class, JSON.toJSON(params));
                yield skillService.update(id, req.description, req.content, req.allowedTools, null);
            }
            case "delete_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                skillService.delete((String) params.get("id"));
                yield Map.of("deleted", Boolean.TRUE);
            }
            case "download_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield skillService.download((String) params.get("id"));
            }
            default -> throw new IllegalArgumentException("Unknown skill operation: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object dispatchDataset(String name, String args) {
        return switch (name) {
            case "list_datasets" -> {
                var req = JSON.fromJSON(ListDatasetsRequest.class, args);
                var list = datasetService.list(req.query, req.offset, req.limit);
                var total = datasetService.count(req.query);
                yield Map.of("datasets", list, "total", total);
            }
            case "get_dataset" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield datasetService.get((String) params.get("id"));
            }
            case "list_dataset_records" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var queryReq = new DatasetRecordService.QueryRequest(
                        (String) params.get("id"),
                        null, null, null,
                        params.get("limit") != null ? ((Number) params.get("limit")).intValue() : null,
                        params.get("offset") != null ? ((Number) params.get("offset")).intValue() : null,
                        (String) params.get("agent_id")
                );
                var result = datasetRecordService.query(queryReq);
                yield Map.of("records", result.records(), "total", result.total());
            }
            default -> throw new IllegalArgumentException("Unknown dataset operation: " + name);
        };
    }

    private Object dispatchTool(String args) {
        var req = JSON.fromJSON(ListToolsRequest.class, args);
        return toolRegistryService.listTools(req.category);
    }

    @SuppressWarnings("unchecked")
    private Object dispatchSessionTrace(String name, String args) {
        return switch (name) {
            case "get_session_history" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield chatMessageService.history((String) params.get("session_id"));
            }
            case "list_traces" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var filter = new TraceListFilter();
                filter.sessionId = (String) params.get("session_id");
                filter.agentName = (String) params.get("agent_name");
                filter.status = (String) params.get("status");
                filter.source = (String) params.get("source");
                if (params.get("limit") != null) filter.limit = ((Number) params.get("limit")).intValue();
                if (params.get("offset") != null) filter.offset = ((Number) params.get("offset")).intValue();
                var traces = traceService.list(filter);
                var total = traceService.count(filter);
                yield Map.of("traces", traces, "total", total);
            }
            case "get_trace" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield traceService.get((String) params.get("trace_id"));
            }
            case "get_trace_spans" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield traceService.spans((String) params.get("trace_id"));
            }
            case "get_session_trace_summary" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield traceService.sessionSummary((String) params.get("session_id"), INTERNAL_USER);
            }
            default -> throw new IllegalArgumentException("Unknown session/trace operation: " + name);
        };
    }
}
