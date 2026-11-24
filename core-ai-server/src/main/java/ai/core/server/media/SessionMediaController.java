package ai.core.server.media;

import ai.core.media.SessionMediaKey;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
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
 * Serves one session media object to its owner. This is what the trace viewer and the chat transcript
 * use to render a ref without ever putting bytes back into conversation state or a span.
 *
 * @author core-ai
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SessionMediaController implements Controller {
    @Inject
    BlobSessionMediaStore mediaStore;
    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("login required");
        var key = new SessionMediaKey(request.pathParam("sessionId"), userId);
        var ref = mediaStore.find(key, request.pathParam("mediaId"))
                .orElseThrow(() -> new NotFoundException("media not found"));
        var bytes = mediaStore.read(key, ref).orElseThrow(() -> new NotFoundException("media not found"));
        var contentType = ref.mediaType == null ? null : ContentType.parse(ref.mediaType);
        return Response.bytes(bytes)
                .contentType(contentType == null ? ContentType.APPLICATION_OCTET_STREAM : contentType)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=300");
    }
}
