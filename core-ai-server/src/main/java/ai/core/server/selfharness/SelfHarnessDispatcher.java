package ai.core.server.selfharness;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.dataset.CreateDatasetRequest;
import ai.core.api.server.dataset.ListDatasetsRequest;
import ai.core.api.server.dataset.SchemaFieldView;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.UpdateSkillRequest;
import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.session.ChatMessageService;
import ai.core.server.skill.SkillFilter;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.trace.service.TraceListFilter;
import ai.core.server.trace.service.TraceService;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches self-harness operations to the corresponding service-layer methods.
 * Kept separate from {@link SelfHarnessApiCaller} so the caller class stays within
 * the checkstyle file-length limit.
 *
 * @author stephen
 */
public class SelfHarnessDispatcher {
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
    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;
    @Inject
    SystemSettingsService systemSettingsService;

    @SuppressWarnings("unchecked")
    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    Object dispatch(String name, String args, ExecutionContext context) {
        var userId = callerUserId(context);
        return switch (name) {
            case "list_agents", "create_agent", "get_agent", "update_agent", "publish_agent" ->
                dispatchAgent(name, args, userId);
            case "list_skills", "get_skill", "create_skill", "update_skill", "delete_skill", "download_skill" ->
                dispatchSkill(name, args, userId);
            case "list_datasets", "get_dataset", "list_dataset_records", "create_dataset" ->
                dispatchDataset(name, args, userId);
            case "list_tools" ->
                dispatchTool(args);
            case "list_models" ->
                dispatchModel(args);
            case "get_session_history", "list_traces", "get_trace", "get_trace_spans", "get_session_trace_summary" ->
                dispatchSessionTrace(name, args, userId);
            default -> throw new IllegalArgumentException("Unknown self-harness operation: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object dispatchAgent(String name, String args, String userId) {
        return switch (name) {
            case "list_agents" -> {
                var req = JSON.fromJSON(ListAgentsRequest.class, args);
                yield agentService.list(userId, req);
            }
            case "create_agent" -> {
                var req = JSON.fromJSON(CreateAgentRequest.class, args);
                yield agentService.create(req, userId);
            }
            case "get_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield agentService.get((String) params.get("id"));
            }
            case "update_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var id = (String) params.remove("id");
                var req = JSON.fromJSON(UpdateAgentRequest.class, JSON.toJSON(params));
                yield agentService.update(id, req, userId);
            }
            case "publish_agent" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield agentService.publish((String) params.get("id"), userId);
            }
            default -> throw new IllegalArgumentException("Unknown agent operation: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object dispatchSkill(String name, String args, String userId) {
        return switch (name) {
            case "list_skills" -> {
                var req = JSON.fromJSON(ListSkillsRequest.class, args);
                yield skillService.list(new SkillFilter(req.namespace, req.sourceType), null, req.query, req.searchIn, req.offset, req.limit);
            }
            case "get_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                yield skillService.get((String) params.get("id"));
            }
            case "create_skill" -> {
                var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
                var namespace = (String) params.get("namespace");
                if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
                var content = (String) params.get("content");
                if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
                yield skillService.upload(userId, namespace, content.getBytes(StandardCharsets.UTF_8), toResourceBytes(params.get("resources")));
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
    private Object dispatchDataset(String name, String args, String userId) {
        return switch (name) {
            case "list_datasets" -> {
                var req = JSON.fromJSON(ListDatasetsRequest.class, args);
                var list = datasetService.list(req.query, req.offset, req.limit);
                var total = datasetService.count(req.query);
                yield Map.of("datasets", list, "total", total);
            }
            case "create_dataset" -> {
                var req = JSON.fromJSON(CreateDatasetRequest.class, args);
                yield datasetService.create(req.name, req.description, userId, toSchemaFields(req.schema), resolveDatasetType(req.type));
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
                        (String) params.get("agent_id"),
                        null
                );
                var result = datasetRecordService.query(queryReq);
                yield Map.of("records", result.records(), "total", result.total());
            }
            default -> throw new IllegalArgumentException("Unknown dataset operation: " + name);
        };
    }

    private DatasetType resolveDatasetType(String type) {
        if (type == null || type.isBlank()) return null;
        return DatasetType.valueOf(type);
    }

    private List<SchemaField> toSchemaFields(List<SchemaFieldView> views) {
        if (views == null) return null;
        return views.stream().map(v -> {
            var field = new SchemaField();
            field.name = v.name;
            field.type = v.type != null ? SchemaFieldType.valueOf(v.type) : null;
            field.label = v.label;
            return field;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> toResourceBytes(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        var result = new LinkedHashMap<String, byte[]>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            var path = String.valueOf(map.get("path"));
            if (!path.isBlank()) {
                var content = String.valueOf(map.get("content"));
                result.put(path, content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Agents, skills and datasets created through the builder must belong to the person driving it —
     * owning them as a phantom "internal" user leaves the caller unable to open their own draft
     * ({@code AgentDependencyAccessPolicy} grants access to the owner or to a published config only).
     */
    private String callerUserId(ExecutionContext context) {
        var caller = context != null ? context.getCaller() : null;
        var userId = caller != null ? caller.userId() : null;
        return userId != null && !userId.isBlank() ? userId : INTERNAL_USER;
    }

    private Object dispatchTool(String args) {
        var req = JSON.fromJSON(ListToolsRequest.class, args);
        return toolRegistryService.listTools(req.category);
    }

    /**
     * The gateway model catalog is the only source of valid model names: without it the builder has to
     * guess when a user asks for "gemini omni 1.1", and silently writes an unroutable model onto the agent.
     */
    @SuppressWarnings("unchecked")
    private Object dispatchModel(String args) {
        var params = (Map<String, Object>) JSON.fromJSON(Map.class, args);
        var endpointType = stringParam(params, "endpoint_type");
        var keyword = stringParam(params, "keyword");
        var models = gatewayRoutingEngine.availableModels().stream()
                .filter(model -> matchesEndpoint(model, endpointType) && matchesKeyword(model, keyword))
                .map(this::modelRow)
                .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("models", models);
        result.put("defaults", defaultModels());
        return result;
    }

    private Map<String, Object> defaultModels() {
        var defaults = new LinkedHashMap<String, Object>();
        defaults.put("chat", gatewayRoutingEngine.defaultChatModelId());
        defaults.put("caption_image", systemSettingsService.captionImageModel());
        defaults.put("image_generation", systemSettingsService.imageGenerationModel());
        defaults.put("video_generation", systemSettingsService.videoGenerationModel());
        return defaults;
    }

    private Map<String, Object> modelRow(GatewayAvailableModelView model) {
        var row = new LinkedHashMap<String, Object>();
        row.put("model_id", model.modelId);
        row.put("display_name", model.displayName);
        row.put("provider", model.providerName);
        row.put("endpoint_types", model.endpointTypes);
        row.put("supports_vision", model.supportsVision);
        row.put("supports_video", model.supportsVideo);
        row.put("supports_file", model.supportsFile);
        return row;
    }

    private boolean matchesEndpoint(GatewayAvailableModelView model, String endpointType) {
        if (endpointType == null) return true;
        var types = model.endpointTypes == null ? List.<String>of() : model.endpointTypes;
        return switch (endpointType.toLowerCase(Locale.ROOT)) {
            case "chat", "chat.completion", "chat.completions" -> types.contains("chat.completions");
            case "response", "responses" -> types.contains("responses");
            case "image" -> types.stream().anyMatch(type -> type.startsWith("image."));
            case "video", "video.generation", "video.generations" -> types.contains("video.generations");
            default -> types.contains(endpointType.toLowerCase(Locale.ROOT));
        };
    }

    private boolean matchesKeyword(GatewayAvailableModelView model, String keyword) {
        if (keyword == null) return true;
        var value = keyword.toLowerCase(Locale.ROOT);
        return contains(model.modelId, value) || contains(model.displayName, value) || contains(model.providerName, value);
    }

    private boolean contains(String field, String keyword) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String stringParam(Map<String, Object> params, String name) {
        var value = params.get(name);
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private Object dispatchSessionTrace(String name, String args, String userId) {
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
                yield traceService.sessionSummary((String) params.get("session_id"), userId);
            }
            default -> throw new IllegalArgumentException("Unknown session/trace operation: " + name);
        };
    }
}
