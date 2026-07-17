package ai.core.server;

import ai.core.api.server.UserWebService;
import ai.core.server.user.UserService;
import ai.core.server.web.UserWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class UserModule extends Module {
    @Override
    protected void initialize() {
        bind(UserService.class);
        api().service(UserWebService.class, bind(UserWebServiceImpl.class));
    }
}
