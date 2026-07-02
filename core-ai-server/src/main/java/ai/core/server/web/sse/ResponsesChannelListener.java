package ai.core.server.web.sse;

import ai.core.llm.LLMProviders;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.llm.responses.ResponsesBridge;
import ai.core.llm.responses.ResponsesValidationException;
import ai.core.server.web.auth.AuthContext;
import ai.core.sse.RawSseChannel;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class ResponsesChannelListener implements ChannelListener<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResponsesChannelListener.class);

    @Inject
    LLMProviders llmProviders;

    @Inject
    WebContext webContext;

    @Override
    public void onConnect(Request request, Channel<Object> channel, String lastEventId) {
        var rawChannel = (RawSseChannel<Object>) channel;
        try {
            var responsesRequest = parseRequest(request);
            var bridge = new ResponsesBridge(llmProviders.getProvider());
            bridge.stream(responsesRequest, rawChannel::sendRawEvent);
            var userId = AuthContext.userId(webContext);
            LOGGER.info("responses bridge request completed, userId={}", userId);
        } catch (ResponsesValidationException | UncheckedIOException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        } finally {
            channel.close();
        }
    }

    private ResponsesRequest parseRequest(Request request) {
        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        return JsonUtil.fromJson(ResponsesRequest.class, new String(body, StandardCharsets.UTF_8));
    }
}
