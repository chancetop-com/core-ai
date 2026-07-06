package ai.core.server.gateway;

import ai.core.sse.RawSseChannel;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

public class GatewayChatCompletionsChannelListener implements ChannelListener<GatewayChatCompletionsSseEvent> {
    @Inject
    GatewayProxyService gatewayProxyService;

    @Override
    public void onConnect(Request request, Channel<GatewayChatCompletionsSseEvent> channel, String lastEventId) {
        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        gatewayProxyService.streamToChannel(body, GatewayEndpointType.CHAT_COMPLETIONS, (RawSseChannel<?>) channel);
        channel.close();
    }
}
