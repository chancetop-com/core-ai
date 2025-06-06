package ai.core.mcp.server;

import ai.core.api.mcp.JsonRpcResponse;
import core.framework.async.Executor;
import core.framework.inject.Inject;
import core.framework.web.sse.Channel;

import java.time.Duration;
import java.util.Map;

/**
 * @author stephen
 */
public class McpServerChannelService {
    @Inject
    Executor executor;

    private final Map<String, Channel<JsonRpcResponse>> channelMap = new java.util.HashMap<>();

    public void connect(String requestId, Channel<JsonRpcResponse> channel) {
        channelMap.put(requestId, channel);
    }

    public void close(Channel<JsonRpcResponse> channel) {
        executor.submit("close-finished-channel", () -> {
            channelMap.values().removeIf(c -> c.equals(channel));
            channel.close();
        }, Duration.ofSeconds(5));
    }

    public Channel<JsonRpcResponse> getChannel(String requestId) {
        return channelMap.get(requestId);
    }
}
