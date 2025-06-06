package ai.core.mcp.server;

import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

import java.util.UUID;

/**
 * @author stephen
 */
public class McpServerSseListener implements ChannelListener<JsonRpcResponse> {
    @Inject
    McpServerService mcpServerService;
    @Inject
    McpServerChannelService channelService;

    @Override
    public void onConnect(Request request, Channel<JsonRpcResponse> channel, @javax.annotation.Nullable String s) {
        if (request.body().isEmpty()) return;
        var json = new String(request.body().orElseThrow());
        ActionLogContext.put("mcp-server-sse-connect", json);
        var req = JsonUtil.fromJson(JsonRpcRequest.class, json);
        var requestId = UUID.randomUUID().toString();
        channelService.connect(requestId, channel);
        mcpServerService.handle(requestId, req);
        channelService.close(channel);
    }
}
