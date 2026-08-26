package ai.core.server.trace.web.trace;

import ai.core.api.server.trace.StopTraceResponse;
import ai.core.api.server.trace.TraceControlWebService;
import ai.core.api.server.trace.TraceStatusView;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.trace.service.TraceStopService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;

/**
 * @author Xander
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class TraceControlWebServiceImpl implements TraceControlWebService {
    @Inject
    WebContext webContext;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    TraceStopService traceStopService;

    @Override
    public StopTraceResponse stop(String traceId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("trace_id", traceId);
        requireAdmin(userId);

        var outcome = traceStopService.stop(traceId, userId);
        var response = new StopTraceResponse();
        response.traceId = traceId;
        response.status = TraceStatusView.CANCELLED;
        response.target = outcome.target();
        response.signalled = outcome.signalled();
        return response;
    }

    private void requireAdmin(String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }
}
