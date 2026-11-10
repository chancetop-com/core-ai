package ai.core.decision;

import core.framework.http.HTTPRequest;

import java.util.Map;

/**
 * The core-ai server's decision gateway ({@code /api/gateway/v1/decisions}). Requests are authenticated
 * with the signed-in user's hub token, so the same provider works in the CLI, the desktop engine and any
 * other hub client without a per-vendor key.
 *
 * @author stephen
 */
public class CoreAiGatewayDecisionProvider extends SystemOneDecisionProvider {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final String SESSION_HEADER = "x-session-id";

    private final String sessionId;

    public CoreAiGatewayDecisionProvider(String url, String token) {
        this(url, token, null);
    }

    public CoreAiGatewayDecisionProvider(String url, String token, String sessionId) {
        super(url, token, "Authorization", "/decisions", Map.of(CLIENT_HEADER, "cli"));
        this.sessionId = sessionId;
    }

    private CoreAiGatewayDecisionProvider(CoreAiGatewayDecisionProvider source, String sessionId) {
        super(source.baseUrl(), source.credential(), "Authorization", "/decisions", Map.of(CLIENT_HEADER, "cli"));
        this.sessionId = sessionId;
    }

    public CoreAiGatewayDecisionProvider withSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.equals(this.sessionId)) return this;
        return new CoreAiGatewayDecisionProvider(this, sessionId);
    }

    @Override
    protected void setHeaders(HTTPRequest request) {
        super.setHeaders(request);
        if (sessionId != null && !sessionId.isBlank()) request.headers.put(SESSION_HEADER, sessionId);
    }
}
