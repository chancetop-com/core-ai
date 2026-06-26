package ai.core.server.web.sse;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.streaming.StreamingCallback;
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

import java.nio.charset.StandardCharsets;

/**
* author cyril
* description
* createTime  2026/6/10
**/
public class LiteLLMProxyChannelListener implements ChannelListener<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiteLLMProxyChannelListener.class);

    @Inject
    LLMProviders llmProviders;

    @Inject
    WebContext webContext;

    @Override
    public void onConnect(Request request, Channel<Object> channel, String lastEventId) {
        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        var completionRequest = parseRequest(body);
        var model = completionRequest.model;
        if (model == null || model.isBlank()) throw new BadRequestException("model is required");

        var rawChannel = (RawSseChannel<Object>) channel;

        llmProviders.getProvider().completionStream(completionRequest, new StreamingCallback() {
            @Override
            public void onChunk(String chunk) {
            }

            @Override
            public void onRawData(String sseData) {
                rawChannel.sendRawData(sseData);
            }
        }, null, false);
        var userId = AuthContext.userId(webContext);
        LOGGER.info(userId);
        rawChannel.sendRawData("[DONE]");
        channel.close();
    }

    private CompletionRequest parseRequest(byte[] body) {
        var bodyStr = new String(body, StandardCharsets.UTF_8);
        return JsonUtil.fromJson(CompletionRequest.class, bodyStr);
    }
}
