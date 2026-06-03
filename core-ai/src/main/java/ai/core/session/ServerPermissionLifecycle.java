package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.BatchToolStartEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.session.permission.PermissionRule;
import ai.core.tool.DiffGenerator;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lifecycle management for server-side tool call permissions.
 *
 * @author stephen
 */
public class ServerPermissionLifecycle extends AbstractLifecycle {
    private static final Set<String> DIFF_TOOLS = Set.of(EditFileTool.TOOL_NAME, WriteFileTool.TOOL_NAME);
    private static final int LARGE_ARGUMENTS_SIZE = 200_000; // skip full JSON parse + diff for arguments >200KB

    private static Map<String, Object> extractLightweightArgs(String arguments) {
        var map = new HashMap<String, Object>();
        extractStringField(arguments, "\"file_path\"", map);
        extractStringField(arguments, "\"task_id\"", map);
        return map;
    }

    private static void extractStringField(String json, String fieldName, Map<String, Object> target) {
        int idx = json.indexOf(fieldName);
        if (idx < 0) return;
        int colonIdx = json.indexOf(':', idx + fieldName.length());
        if (colonIdx < 0) return;
        // skip whitespace after colon
        int startQuote = -1;
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                startQuote = i;
                break;
            }
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
        }
        if (startQuote < 0) return;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return;
        // Use the JSON key without wrapping quotes as the map key
        String key = fieldName.startsWith("\"") ? fieldName.substring(1, fieldName.length() - 1) : fieldName;
        target.put(key, json.substring(startQuote + 1, endQuote));
    }

    private final Logger logger = LoggerFactory.getLogger(ServerPermissionLifecycle.class);
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final PermissionGate permissionGate;
    private final boolean autoApproveAll;
    private final ToolPermissionStore permissionStore;
    private final Set<String> sessionAllowedTools = ConcurrentHashMap.newKeySet();
    private final Function<String, String> toolTypeResolver;

    public ServerPermissionLifecycle(String sessionId, Consumer<AgentEvent> dispatcher, PermissionGate permissionGate, boolean autoApproveAll, ToolPermissionStore permissionStore, Function<String, String> toolTypeResolver) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.permissionGate = permissionGate;
        this.autoApproveAll = autoApproveAll;
        this.permissionStore = permissionStore;
        this.toolTypeResolver = toolTypeResolver;
    }

    @Override
    public void beforeBatch(String group, List<FunctionCall> tools, ExecutionContext context) {
        var toolInfos = tools.stream()
                .map(tc -> new BatchToolStartEvent.ToolInfo(tc.id, tc.function.name, tc.function.arguments))
                .toList();
        var taskId = resolveBatchTaskId(tools, context);
        dispatcher.accept(new BatchToolStartEvent(sessionId, group, toolInfos, taskId));
    }

    private String resolveBatchTaskId(List<FunctionCall> tools, ExecutionContext context) {
        if (!tools.isEmpty()) {
            var firstArgs = parseArguments(tools.getFirst().function.arguments);
            var taskId = (String) firstArgs.get("task_id");
            if (taskId != null) return taskId;
        }
        return context.getTaskId();
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        var callId = functionCall.id;
        var toolName = functionCall.function.name;
        var arguments = functionCall.function.arguments;

        logger.debug("beforeTool: tool={}, callId={}", toolName, callId);

        boolean largeArgs = arguments != null && arguments.length() > LARGE_ARGUMENTS_SIZE;
        Map<String, Object> argMap;
        if (largeArgs && DIFF_TOOLS.contains(toolName)) {
            argMap = extractLightweightArgs(arguments);
        } else {
            argMap = parseArguments(arguments);
        }
        dispatchStartEvent(callId, toolName, arguments, argMap, executionContext, largeArgs);

        String pattern = PermissionRule.buildPattern(toolName, argMap);
        if (shouldSkipApproval(toolName, argMap, executionContext)) return;

        permissionGate.prepare(callId);
        logger.debug("dispatching approval request: tool={}, callId={}", toolName, callId);
        dispatcher.accept(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments, pattern));

        logger.debug("waiting for approval: tool={}, callId={}", toolName, callId);
        var decision = permissionGate.waitForApproval(callId, 300_000);
        logger.debug("approval received: tool={}, callId={}, decision={}", toolName, callId, decision);
        applyDecision(decision, toolName, pattern);
    }

    private void dispatchStartEvent(String callId, String toolName, String arguments, Map<String, Object> argMap, ExecutionContext context, boolean skipDiff) {
        var startEvent = ToolStartEvent.of(sessionId, callId, toolName, arguments);
        if (!skipDiff) {
            startEvent.diff = generatePreviewDiff(toolName, argMap);
        }
        startEvent.taskId = (String) argMap.getOrDefault("task_id", context.getTaskId());
        startEvent.runInBackground = isBackground(argMap);
        if (TaskTool.TOOL_NAME.equals(toolName)) {
            startEvent.model = resolveSubagentModel(argMap, context);
        }
        dispatcher.accept(startEvent);
    }

    private String resolveSubagentModel(Map<String, Object> argMap, ExecutionContext context) {
        var subagentType = (String) argMap.get("subagent_type");
        if (subagentType == null || subagentType.isBlank()) return null;
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.model() != null && !config.model().isBlank()) {
                return config.model();
            }
        }
        return context.getModel();
    }

    private Boolean isBackground(Map<String, Object> argMap) {
        return Boolean.TRUE.equals(argMap.get("run_in_background"));
    }


    private boolean shouldSkipApproval(String toolName, Map<String, Object> argMap, ExecutionContext executionContext) {
        if (autoApproveAll) return true;
        if (executionContext.isSubagent()) return true;
        if (sessionAllowedTools.contains(toolName)) return true;
        if (permissionStore != null) {
            var result = permissionStore.checkPermission(toolName, argMap);
            if (result.isPresent() && result.get()) return true;
            if (result.isPresent()) throw new ToolCallDeniedException(toolName);
        }
        return false;
    }

    private void applyDecision(ApprovalDecision decision, String toolName, String pattern) {
        switch (decision) {
            case DENY -> throw new ToolCallDeniedException(toolName);
            case DENY_ALWAYS -> {
                if (permissionStore != null) permissionStore.deny(pattern);
                throw new ToolCallDeniedException(toolName);
            }
            case APPROVE_ALWAYS -> {
                if (permissionStore != null) permissionStore.allow(pattern);
            }
            case APPROVE_SESSION -> sessionAllowedTools.add(toolName);
            default -> {
            }
        }
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        var callId = functionCall.id;
        var toolName = functionCall.function.name;
        var arguments = functionCall.function.arguments;
        Map<String, Object> argMap = parseArguments(arguments);
        var status = toolResult.isCompleted() ? "success" : toolResult.isAsyncLaunched() ? "async_launched" : "failure";
        var result = toolResult.getResult();

        logger.debug("afterTool: tool={}, callId={}, status={}", toolName, callId, status);
        var event = ToolResultEvent.of(sessionId, callId, toolName, status, result);
        event.taskId = (String) argMap.getOrDefault("task_id", executionContext.getTaskId());
        event.toolType = toolTypeResolver.apply(toolName);
        dispatcher.accept(event);
    }

    @SuppressWarnings("unchecked")
    private String generatePreviewDiff(String toolName, Map<String, Object> argMap) {
        if (!DIFF_TOOLS.contains(toolName)) return null;
        try {
            if (EditFileTool.TOOL_NAME.equals(toolName)) {
                var filePath = (String) argMap.get("file_path");
                var oldString = (String) argMap.get("old_string");
                var newString = (String) argMap.get("new_string");
                if (filePath == null || oldString == null || newString == null) return null;
                var path = Path.of(filePath);
                if (!Files.isRegularFile(path)) return null;
                var content = Files.readString(path, StandardCharsets.UTF_8);
                var result = DiffGenerator.forEdit(filePath, content, oldString, newString);
                return result != null ? result.serialize() : null;
            }
            if (WriteFileTool.TOOL_NAME.equals(toolName)) {
                var filePath = (String) argMap.get("file_path");
                var newContent = (String) argMap.get("content");
                if (filePath == null || newContent == null) return null;
                var path = Path.of(filePath);
                var oldContent = Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
                var result = DiffGenerator.forWrite(filePath, oldContent, newContent);
                return result != null ? result.serialize() : null;
            }
        } catch (Exception e) {
            logger.debug("failed to generate preview diff for tool={}: {}", toolName, e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return JsonUtil.fromJson(Map.class, arguments);
        } catch (Exception e) {
            logger.debug("failed to parse arguments for rule matching: {}", e.getMessage());
            return Map.of();
        }
    }
}
