package ai.core.server.analytics;

import ai.core.server.domain.User;
import ai.core.server.web.auth.AuthContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;

import java.util.List;

/**
 * Admin analytics API controller. All endpoints require admin role.
 */
public class AdminAnalyticsController {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // === Static helpers ===

    private static String mode(Request request) {
        return request.queryParams().getOrDefault("mode", "history");
    }

    private static String range(Request request) {
        return request.queryParams().getOrDefault("range", "7d");
    }

    private static String from(Request request) {
        return request.queryParams().get("from");
    }

    private static String to(Request request) {
        return request.queryParams().get("to");
    }

    private static String sort(Request request) {
        return request.queryParams().getOrDefault("sort", "tokens");
    }

    @Inject
    AdminAnalyticsService analyticsService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    WebContext webContext;

    // === Global ===

    public Response global(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.globalSummary(
            mode(request), range(request), from(request), to(request)));
    }

    public Response trend(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.trend(
            mode(request), range(request), from(request), to(request)));
    }

    // === Dimension endpoints ===

    public Response bySource(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.bySource(
            mode(request), range(request), from(request), to(request), sort(request)));
    }

    public Response byAgent(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.byAgent(
            mode(request), range(request), from(request), to(request), sort(request)));
    }

    public Response byUser(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.byUser(
            mode(request), range(request), from(request), to(request), sort(request)));
    }

    public Response byProvider(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.byProvider(
            mode(request), range(request), from(request), to(request), sort(request)));
    }

    public Response byModel(Request request) {
        requireAdmin();
        return jsonResponse(analyticsService.byModel(
            mode(request), range(request), from(request), to(request), sort(request)));
    }

    public Response dimensionTrend(Request request) {
        requireAdmin();
        String dimension = request.pathParam("dimension");
        String keysParam = request.queryParams().get("keys");
        List<String> keys = keysParam != null ? List.of(keysParam.split(",")) : List.of();
        return jsonResponse(analyticsService.dimensionTrend(dimension,
            mode(request), range(request), from(request), to(request), keys));
    }

    // === Helpers ===

    private void requireAdmin() {
        String userId = AuthContext.userId(webContext);
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }

    private Response jsonResponse(Object data) {
        try {
            return Response.bytes(MAPPER.writeValueAsBytes(data)).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
