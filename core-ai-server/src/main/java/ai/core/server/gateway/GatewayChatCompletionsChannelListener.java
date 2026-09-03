package ai.core.server.gateway;

import ai.core.server.web.auth.AuthContext;
import ai.core.sse.RawSseChannel;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

public class GatewayChatCompletionsChannelListener implements ChannelListener<GatewayChatCompletionsSseEvent> {
    @Inject
    GatewayProxyService gatewayProxyService;
    @Inject
    WebContext webContext;

    @Override
    public void onConnect(Request request, Channel<GatewayChatCompletionsSseEvent> channel, String lastEventId) {
        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        gatewayProxyService.streamToChannel(body, GatewayEndpointType.CHAT_COMPLETIONS, (RawSseChannel<?>) channel,
                AuthContext.userId(webContext), GatewaySupport.clientSessionId(request), GatewaySupport.agentName(request));
        channel.close();
    }
}
