package ai.core.mcp.server;

import ai.core.api.mcp.Constants;
import ai.core.api.mcp.ErrorCodes;
import ai.core.api.mcp.JsonRpcError;
import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.schema.Implementation;
import ai.core.api.mcp.schema.InitializeResult;
import ai.core.api.mcp.schema.PromptCapabilities;
import ai.core.api.mcp.schema.ResourceCapabilities;
import ai.core.api.mcp.schema.ServerCapabilities;
import ai.core.api.mcp.schema.ToolCapabilities;
import ai.core.api.mcp.schema.tool.CallToolRequest;
import ai.core.api.mcp.schema.tool.CallToolResult;
import ai.core.api.mcp.schema.tool.Content;
import ai.core.api.mcp.schema.tool.ListToolRequest;
import ai.core.api.mcp.schema.tool.ListToolsResult;
import ai.core.api.mcp.schema.tool.Tool;
import ai.core.tool.ToolCall;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;
import core.framework.web.exception.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class McpServerService {
    @Inject
    McpServerChannelService mcpServerChannelService;

    private McpServerToolLoader toolLoader;
    private String name = "chancetop-mcp-server";
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
        ActionLogContext.put("mcp-server-response", JSON.toJSON(response));
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
                default -> {

                }
            }
        } catch (Exception e) {
            rsp.error = new JsonRpcError();
            rsp.error.message = e.getMessage();
            rsp.error.code = ErrorCodes.INTERNAL_ERROR;
        }
        return rsp;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleInitialize(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new InitializeResult();
        rst.serverInfo = serverInfo();
        rst.protocolVersion = Constants.LATEST_PROTOCOL_VERSION;
        rst.instructions = null;
        rst.capabilities = new ServerCapabilities();
        rst.capabilities.prompts = new PromptCapabilities();
        rst.capabilities.resources = new ResourceCapabilities();
        rst.capabilities.tools = new ToolCapabilities();
        rsp.result = JsonUtil.toMap(rst);
    }

    private Implementation serverInfo() {
        return Implementation.of(name, version);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationInitialized(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handlePing(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleToolsList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var namespaces = this.toolLoader.defaultNamespaces();
        if (request.params instanceof Map) {
            var req = JsonUtil.fromJson(ListToolRequest.class, (Map<?, ?>) request.params);
            namespaces = req.namespaces;
        }
        var rst = new ListToolsResult();
        if (namespaces != null) {
            List<String> finalNamespaces = namespaces;
            rst.tools = toolCalls.stream().filter(v -> finalNamespaces.contains(v.getNamespace())).map(this::toMcpTool).toList();
        } else {
            rst.tools = toolCalls.stream().map(this::toMcpTool).toList();
        }
        rsp.result = JsonUtil.toMap(rst);
    }

    private Tool toMcpTool(ToolCall toolCall) {
        var tool = new Tool();
        tool.name = toolCall.getName();
        tool.needAuth = toolCall.isNeedAuth();
        tool.description = toolCall.getDescription();
        tool.inputSchema = toolCall.toJsonSchema();
        return tool;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleToolsCall(String requestId, JsonRpcRequest request, JsonRpcResponse rsp) {
        var req = JsonUtil.fromJson(CallToolRequest.class, (Map<?, ?>) request.params);
        var map = toolCalls.stream().collect(Collectors.toMap(ToolCall::getName, Function.identity()));
        var tool = map.get(req.name);
        if (tool == null) throw new NotFoundException("Tool not existed: " + request.params);
        var rst = new CallToolResult();
        var content = new Content();
        content.type = Content.ContentType.TEXT;
        content.text = tool.call(JsonUtil.toJson(req.arguments));
        rst.content = List.of(content);
        rsp.result = JsonUtil.toMap(rst);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationToolsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {

    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleResourcesList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JsonUtil.toMap(rst);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleSamplingCreateMessage(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationRootsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleRootsList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JsonUtil.toMap(rst);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationMessage(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleLoggingSetLevel(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationPromptsListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handlePromptGet(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handlePromptList(JsonRpcRequest request, JsonRpcResponse rsp) {
        var rst = new ListToolsResult();
        rst.tools = List.of();
        rsp.result = JsonUtil.toMap(rst);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleResourcesUnsubscribe(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleResourcesSubscribe(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleResourcesTemplatesList(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleNotificationResourcesListChanged(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleResourcesRead(JsonRpcRequest request, JsonRpcResponse rsp) {
        
    }
}