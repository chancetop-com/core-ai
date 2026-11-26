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
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

/**
 * Serves the full input/output body of one span when the detail response kept a lightweight
 * {@code $coreai_blob_ref} instead of materializing a multi-megabyte payload.
 *
 * @author core-ai
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class TraceBodyController implements Controller {
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
        var field = request.queryParams().getOrDefault("field", "input");
        if (!"input".equals(field) && !"output".equals(field)) {
            throw new BadRequestException("field must be input or output");
        }
        var traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (trace == null || !traceAccessControl.canRead(trace, userId, traceVisibilityResolver.resolve(userId))) {
            throw new NotFoundException("trace not found");
        }
        var body = traceService.spanBody(traceId, request.pathParam("spanId"), field);
        if (body == null) throw new NotFoundException("span body not found");
        var trimmed = body.stripLeading();
        var json = trimmed.startsWith("{") || trimmed.startsWith("[");
        return Response.text(body)
            .contentType(json ? ContentType.APPLICATION_JSON : ContentType.TEXT_PLAIN)
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=300");
    }
}
