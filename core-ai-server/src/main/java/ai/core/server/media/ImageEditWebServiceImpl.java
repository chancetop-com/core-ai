package ai.core.server.media;

import ai.core.api.server.media.ImageEditModelListResponse;
import ai.core.api.server.media.ImageEditRequest;
import ai.core.api.server.media.ImageEditResponse;
import ai.core.api.server.media.ImageEditWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class ImageEditWebServiceImpl implements ImageEditWebService {
    @Inject
    ImageEditService imageEditService;
    @Inject
    WebContext webContext;

    @Override
    public ImageEditModelListResponse models() {
        return imageEditService.models();
    }

    @Override
    public ImageEditResponse edit(ImageEditRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return imageEditService.edit(userId, request);
    }
}
