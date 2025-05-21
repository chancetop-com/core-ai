package ai.core.mcp.server;

import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.jetbrains.annotations.Nullable;

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
    public void onConnect(Request request, Channel<JsonRpcResponse> channel, @Nullable String s) {
        if (request.body().isEmpty()) return;
        ActionLogContext.put("mcp-server-sse-connect", new String(request.body().orElseThrow()));
        var newJsonText = JsonParamsConverter.convert(new String(request.body().orElseThrow()));
        var req = JSON.fromJSON(JsonRpcRequest.class, newJsonText);
        var requestId = UUID.randomUUID().toString();
        channelService.connect(requestId, channel);
        mcpServerService.handle(requestId, req);
        channelService.close(channel);
    }
}
