package ai.core.server.share;

import ai.core.api.server.share.CreateSessionShareRequest;
import ai.core.api.server.share.SessionShareStatusResponse;
import ai.core.api.server.share.SessionShareView;
import ai.core.api.server.share.SessionShareWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author Xander
 */
public class SessionShareWebServiceImpl implements SessionShareWebService {
    @Inject
    WebContext webContext;
    @Inject
    SessionShareService shareService;

    @Override
    @PermissionsRequired(PermissionCodes.TRACE_VIEW)
    public SessionShareView create(String sessionId, CreateSessionShareRequest request) {
        return shareService.create(sessionId, AuthContext.userId(webContext), request);
    }

    @Override
    @PermissionsBypass
    public SessionShareStatusResponse current(String sessionId) {
        return shareService.current(sessionId, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsBypass
    public void revoke(String sessionId, String shareId) {
        shareService.revoke(sessionId, shareId, AuthContext.userId(webContext));
    }
}
