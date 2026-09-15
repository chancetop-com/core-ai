package ai.core.server.hub;

import ai.core.api.server.hub.HubCallWebService;
import ai.core.api.server.hub.ListHubCallsRequest;
import ai.core.api.server.hub.ListHubCallsResponse;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.UnauthorizedException;

import java.time.ZonedDateTime;

/**
 * Resolves the request parameters of the hub call records list: relative range to an absolute window,
 * paging limits, and the visibility scope (non-admin callers only see their own calls).
 *
 * @author stephen
 */
public class HubCallWebServiceImpl implements HubCallWebService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_RANGE_DAYS = 7;

    @Inject
    HubCallQueryService queryService;
    @Inject
    WebContext webContext;
    @Inject
    MongoCollection<User> userCollection;

    @Override
    @PermissionsRequired(PermissionCodes.TRACE_VIEW)
    public ListHubCallsResponse list(ListHubCallsRequest request) {
        var effective = request == null ? new ListHubCallsRequest() : request;
        var filter = new HubCallListFilter();
        filter.kind = effective.kind;
        filter.source = effective.source;
        filter.userId = effective.userId;
        filter.state = effective.state;
        filter.limit = effective.limit == null ? DEFAULT_LIMIT : Math.min(Math.max(effective.limit, 1), MAX_LIMIT);
        filter.offset = effective.offset == null ? 0 : Math.max(effective.offset, 0);
        applyTimeWindow(effective, filter);
        applyScope(filter);
        return queryService.list(filter);
    }

    private void applyTimeWindow(ListHubCallsRequest request, HubCallListFilter filter) {
        if (hasText(request.range)) {
            filter.startFrom = relativeRangeStart(request.range.trim());
            if (filter.startFrom == null) throw new BadRequestException("range must be one of 1h, 24h, 7d, 30d, 90d");
            filter.startTo = parseDateTime(request.startTo);
            return;
        }
        filter.startFrom = parseDateTime(request.startFrom);
        filter.startTo = parseDateTime(request.startTo);
        if (filter.startFrom == null) filter.startFrom = ZonedDateTime.now().minusDays(DEFAULT_RANGE_DAYS);
    }

    private ZonedDateTime relativeRangeStart(String range) {
        var now = ZonedDateTime.now();
        return switch (range) {
            case "1h" -> now.minusHours(1);
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            default -> null;
        };
    }

    private ZonedDateTime parseDateTime(String value) {
        if (!hasText(value)) return null;
        return ZonedDateTime.parse(value.trim());
    }

    private void applyScope(HubCallListFilter filter) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("authentication required");
        if (!isAdmin(userId)) {
            // a non-admin caller can never widen the scope through the userId parameter
            filter.userId = userId;
        }
    }

    private boolean isAdmin(String userId) {
        return userCollection.get(userId)
                .map(user -> "admin".equals(user.role))
                .orElse(Boolean.FALSE);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
