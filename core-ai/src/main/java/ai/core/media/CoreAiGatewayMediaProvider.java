package ai.core.media;

import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.reference.ManagedReferenceProvider;
import core.framework.http.HTTPRequest;

import java.time.Duration;

/**
 * The core-ai server's media gateway ({@code /api/gateway/v1}). Unlike a generic LiteLLM endpoint it owns the
 * media job index, so symbolic {@code media_id} references are passed through untouched and resolved server-side
 * after routing. The client is also told the session id, which scopes the {@code "last"} reference and the cost
 * records to this conversation instead of the whole account.
 *
 * @author stephen
 */
public class CoreAiGatewayMediaProvider extends LiteLLMMediaProvider implements ManagedReferenceProvider {
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final String SESSION_HEADER = "x-session-id";

    private final String sessionId;

    public CoreAiGatewayMediaProvider(String url, String token) {
        this(url, token, null);
    }

    public CoreAiGatewayMediaProvider(String url, String token, String sessionId) {
        super(url, token, true, GENERATION_TIMEOUT, CONTROL_TIMEOUT, DOWNLOAD_TIMEOUT);
        this.sessionId = sessionId;
    }

    private CoreAiGatewayMediaProvider(CoreAiGatewayMediaProvider source, String sessionId) {
        super(source);
        this.sessionId = sessionId;
    }

    /** A session-scoped view that shares the parent's clients; the base provider is returned when nothing changes. */
    public CoreAiGatewayMediaProvider withSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.equals(this.sessionId)) return this;
        return new CoreAiGatewayMediaProvider(this, sessionId);
    }

    @Override
    protected void setHeaders(HTTPRequest request) {
        super.setHeaders(request);
        request.headers.put(CLIENT_HEADER, "cli");
        if (sessionId != null && !sessionId.isBlank()) {
            request.headers.put(SESSION_HEADER, sessionId);
        }
    }
}
