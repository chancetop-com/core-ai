package ai.core.server.web.sse;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI-compatible chat completions endpoint for the core-ai CLI (and ACP runner):
 * always streams over SSE and routes through the internal LLM provider stack, so
 * models can fall back to the static provider when the gateway has no route.
 * <p>
 * Legacy path {@value #LEGACY_PATH} is kept as a deprecated alias for older CLI
 * binaries and will be removed once the CLI fleet has migrated.
 */
public class CliProxyChannelListener implements ChannelListener<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliProxyChannelListener.class);

    public static final String PATH = "/api/cli/v1/chat/completions";
    public static final String LEGACY_PATH = "/api/litellm/v1/chat/completions";

    @Inject
    LLMProviders llmProviders;

    @Inject
    WebContext webContext;

    @Override
    public void onConnect(Request request, Channel<Object> channel, String lastEventId) {
        if (request.requestURL().startsWith(LEGACY_PATH)) {
            LOGGER.warn("deprecated endpoint {} used, migrate to {}", LEGACY_PATH, PATH);
        }
        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        var completionRequest = parseRequest(body);
        // OpenAI-compatible proxy surface: forward the client's payload verbatim, upstream errors included
        completionRequest.setPassthrough(true);
        var model = completionRequest.model;
        if (model == null || model.isBlank()) throw new BadRequestException("model is required");

        var rawChannel = (RawSseChannel<Object>) channel;

        try {
            llmProviders.getProvider().completionStream(completionRequest, new StreamingCallback() {
                @Override
                public void onChunk(String chunk) {
                }

                @Override
                public void onRawData(String sseData) {
                    rawChannel.sendRawData(sseData);
                }
            }, null, false);
        } catch (RuntimeException e) {
            // The upstream rejected the request (e.g. 400 context-length exceeded). The SSE response is
            // already committed as 200, so surface the real error to the client as an OpenAI-style error
            // event instead of closing the stream silently — the client otherwise reports a misleading
            // "LLM stream returned no data" and the actual failure never reaches its trace.
            var errorBody = new LinkedHashMap<String, Object>();
            errorBody.put("error", Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    "type", "invalid_request_error"));
            rawChannel.sendRawData(JsonUtil.toJson(errorBody));
            throw e;
        }
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
