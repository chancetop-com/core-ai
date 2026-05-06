package ai.core.server.a2a;

import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;

import java.nio.charset.StandardCharsets;

/**
 * @author xander
 */
public class A2AStreamChannelListener implements ChannelListener<StreamResponse> {
    @Inject
    RequestAuthenticator requestAuthenticator;
    @Inject
    ServerA2AService a2aService;

    @Override
    public void onConnect(Request request, Channel<StreamResponse> channel, String lastEventId) {
        var userId = requestAuthenticator.authenticate(request);
        var messageRequest = parse(request);
        var agentId = A2AAgentIdResolver.resolveStream(request, messageRequest);
        a2aService.stream(agentId, messageRequest, userId, channel::send, channel::close);
    }

    private SendMessageRequest parse(Request request) {
        var body = request.body().orElseThrow(() -> new BadRequestException("request body required"));
        return JsonUtil.fromJson(SendMessageRequest.class, new String(body, StandardCharsets.UTF_8));
    }
}
