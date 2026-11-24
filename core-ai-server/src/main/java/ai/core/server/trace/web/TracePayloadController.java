package ai.core.server.trace.web;

import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.web.auth.AuthContext;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

/**
 * Serves one deduplicated trace image blob. The trace detail response stops hydrating past 2 MB, so
 * the viewer fetches the remaining images here by their sha256 instead of turning the response into a
 * multi-megabyte document.
 *
 * @author core-ai
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class TracePayloadController implements Controller {
    @Inject
    TraceService traceService;
    @Inject
    TraceAccessControl traceAccessControl;
    @Inject
    TraceVisibilityResolver traceVisibilityResolver;
    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        var traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (trace == null || !traceAccessControl.canRead(trace, userId, traceVisibilityResolver.resolve(userId))) {
            throw new NotFoundException("trace not found");
        }
        var payload = traceService.payload(traceId, request.pathParam("hash"));
        if (payload == null) throw new NotFoundException("payload not found");
        var contentType = payload.contentType() == null ? null : ContentType.parse(payload.contentType());
        return Response.bytes(payload.bytes())
                .contentType(contentType == null ? ContentType.APPLICATION_OCTET_STREAM : contentType)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=300");
    }
}
