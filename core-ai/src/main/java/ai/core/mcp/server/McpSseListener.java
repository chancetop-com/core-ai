package ai.core.mcp.server;

import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * @author stephen
 */
public class McpSseListener implements ChannelListener<JsonRpcResponse> {
    @Inject
    McpService mcpService;
    @Inject
    McpChannelService channelService;

    @Override
    public void onConnect(Request request, Channel<JsonRpcResponse> channel, @Nullable String s) {
        var req = JSON.fromJSON(JsonRpcRequest.class, new String(request.body().orElseThrow()));
        var requestId = UUID.randomUUID().toString();
        channelService.connect(requestId, channel);
        mcpService.handle(requestId, req);
    }
}
