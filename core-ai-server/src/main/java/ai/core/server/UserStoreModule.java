package ai.core.server;

import ai.core.server.user.MongoUserStore;
import ai.core.server.user.PgUserStore;
import ai.core.server.user.UserStore;
import core.framework.module.Module;

/**
 * Binds the user store before the modules that inject it (settings, auth, api users, project).
 *
 * @author stephen
 */
public class UserStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(UserStore.class, bind(PgUserStore.class));
        } else {
            bind(UserStore.class, bind(MongoUserStore.class));
        }
    }
}
