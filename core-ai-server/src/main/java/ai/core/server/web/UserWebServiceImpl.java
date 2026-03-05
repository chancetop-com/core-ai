package ai.core.server.web;

import ai.core.api.server.UserWebService;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.api.server.user.UserView;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.user.UserService;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class UserWebServiceImpl implements UserWebService {
    @Inject
    WebContext webContext;
    @Inject
    UserService userService;

    @Override
    public UserView me() {
        var userId = AuthContext.userId(webContext);
        return userService.me(userId);
    }

    @Override
    public GenerateApiKeyResponse generateApiKey() {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return userService.generateApiKey(userId);
    }
}
