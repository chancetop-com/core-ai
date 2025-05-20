package ai.core.mcp.server;

import ai.core.api.mcp.Constants;
import ai.core.api.mcp.ErrorCodes;
import ai.core.api.mcp.JsonRpcError;
import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.JsonSchema;
import ai.core.api.mcp.kafka.McpToolCallEvent;
import ai.core.api.mcp.schema.Implementation;
import ai.core.api.mcp.schema.InitializeRequest;
import ai.core.api.mcp.schema.InitializeResult;
import ai.core.api.mcp.schema.PromptCapabilities;
import ai.core.api.mcp.schema.ResourceCapabilities;
import ai.core.api.mcp.schema.ServerCapabilities;
import ai.core.api.mcp.schema.ToolCapabilities;
import ai.core.api.mcp.schema.tool.CallToolRequest;
import ai.core.api.mcp.schema.tool.ListToolsResult;
import ai.core.api.mcp.schema.tool.Tool;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import core.framework.async.Executor;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.kafka.MessagePublisher;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class McpServerService {
    @Inject
    McpServerChannelService mcpServerChannelService;
    @Inject
    Executor executor;
    @Inject
    MessagePublisher<McpToolCallEvent> messagePublisher;

    private McpServerToolLoader toolLoader;
    private String name = "chancetop-api-mcp-server";
    private String version = "1.0.0";
    private List<ToolCall> toolCalls;
    private boolean initialized = false;

    private void initialize() {
        if (toolLoader == null) {
            throw new RuntimeException("tool loader not initialized");
        }
        this.toolCalls = toolLoader.load();
        initialized = true;
    }

    public void setInfo(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public void setToolLoader(McpServerToolLoader toolLoader) {
        this.toolLoader = toolLoader;
    }

    public void reload() {
        initialize();
    }

    public void handle(String requestId, JsonRpcRequest req) {
        if (!initialized) initialize();
        var channel = mcpServerChannelService.getChannel(requestId);
        var response = handleEvent(requestId, req);
        channel.send(response);
    }

    private JsonRpcResponse handleEvent(String requestId, JsonRpcRequest request) {
        var rsp = JsonRpcResponse.of(Constants.JSONRPC_VERSION, request.id);
        try {
            switch (request.method) {
                case METHOD_INITIALIZE -> handleInitialize(request, rsp);
                case METHOD_NOTIFICATION_INITIALIZED -> handleNotificationInitialized(request, rsp);
                case METHOD_PING -> handlePing(request, rsp);
                case METHOD_TOOLS_LIST -> handleToolsList(request, rsp);
                case METHOD_TOOLS_CALL -> handleToolsCall(requestId, request, rsp);
                case METHOD_NOTIFICATION_TOOLS_LIST_CHANGED -> handleNotificationToolsListChanged(request, rsp);
                case METHOD_RESOURCES_LIST -> handleResourcesList(request, rsp);
                case METHOD_RESOURCES_READ -> handleResourcesRead(request, rsp);
                case METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED -> handleNotificationResourcesListChanged(request, rsp);
                case METHOD_RESOURCES_TEMPLATES_LIST -> handleResourcesTemplatesList(request, rsp);
                case METHOD_RESOURCES_SUBSCRIBE -> handleResourcesSubscribe(request, rsp);
                case METHOD_RESOURCES_UNSUBSCRIBE -> handleResourcesUnsubscribe(request, rsp);
                case METHOD_PROMPT_LIST -> handlePromptList(request, rsp);
                case METHOD_PROMPT_GET -> handlePromptGet(request, rsp);
                case METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED -> handleNotificationPromptsListChanged(request, rsp);
                case METHOD_LOGGING_SET_LEVEL -> handleLoggingSetLevel(request, rsp);
                case METHOD_NOTIFICATION_MESSAGE -> handleNotificationMessage(request, rsp);
                case METHOD_ROOTS_LIST -> handleRootsList(request, rsp);
                case METHOD_NOTIFICATION_ROOTS_LIST_CHANGED -> handleNotificationRootsListChanged(request, rsp);
                case METHOD_SAMPLING_CREATE_MESSAGE -> handleSamplingCreateMessage(request, rsp);
                default -> throw new NotFoundException("Method not found: " + request.method);
            }
        } catch (Exception e) {
            rsp.error = new JsonRpcError();
            rsp.error.message = e.getMessage();
            rsp.error.code = ErrorCodes.INTERNAL_ERROR;
        }
        return rsp;
    }

    private void handleInitialize(JsonRpcRequest request, JsonRpcResponse rsp) {
        var req = JSON.fromJSON(InitializeRequest.class, request.params);
        if (req.protocolVersion.startsWith("2024")) {
            throw new ConflictException("Protocol Version 2024-* Not Support");
        }
        var rst = new InitializeResult();
        rst.serverInfo = serverInfo();
        rst.protocolVersion = Constants.LATEST_PROTOCOL_VERSION;
        rst.instructions = null;
        rst.capabilities = new ServerCapabilities();
        rst.capabilities.prompts = new PromptCapabilities();
        rst.capabilities.resources = new ResourceCapabilities();
        rst.capabilities.tools = new ToolCapabilities();
        rsp.result = JSON.toJSON(rst);
    }

    private Implementation serverInfo() {
        return Implementation.of(name, version);
    }

    private void handleNotificationInitialized(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    private void handlePing(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    private void handleToolsList(JsonRpcRequest request, JsonRpcResponse rsp) {
//        var req = JSON.fromJSON(PaginationRequest.class, request.params);
        var rst = new ListToolsResult();
//        rst.nextCursor = req.cursor;
        rst.tools = toolCalls.stream().map(this::toMcpTool).toList();
        rsp.result = JSON.toJSON(rst);
    }

    private Tool toMcpTool(ToolCall toolCall) {
        var tool = new Tool();
        tool.name = toolCall.getName();
        tool.description = toolCall.getDescription();
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;
        schema.required = toolCall.getParameters().stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        schema.properties = toolCall.getParameters().stream().collect(Collectors.toMap(ToolCallParameter::getName, p -> {
            var property = new JsonSchema.PropertySchema();
            property.description = p.getDescription();
            property.type = JsonSchema.PropertyType.STRING;
            return property;
        }));
        tool.inputSchema = schema;
        return tool;
    }

    private void handleToolsCall(String requestId, JsonRpcRequest request, JsonRpcResponse rsp) {
        var req = JSON.fromJSON(CallToolRequest.class, request.params);
        var map = toolCalls.stream().collect(Collectors.toMap(ToolCall::getName, Function.identity()));
        var tool = map.get(req.name);
        if (tool == null) throw new NotFoundException("Tool not existed: " + request.params);
        rsp.result = tool.call(req.arguments);
        executor.submit("mcp-handle-tool-call", () -> {
            var rst = tool.call(req.arguments);
            messagePublisher.publish(requestId, McpToolCallEvent.of(requestId, rst));
        });
    }

    private void handleNotificationToolsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    private void handleResourcesList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JSON.toJSON(rst);
    }

    private void handleSamplingCreateMessage(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleNotificationRootsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleRootsList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JSON.toJSON(rst);
    }

    private void handleNotificationMessage(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleLoggingSetLevel(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleNotificationPromptsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handlePromptGet(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handlePromptList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JSON.toJSON(rst);
    }

    private void handleResourcesUnsubscribe(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleResourcesSubscribe(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleResourcesTemplatesList(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleNotificationResourcesListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    private void handleResourcesRead(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }
}