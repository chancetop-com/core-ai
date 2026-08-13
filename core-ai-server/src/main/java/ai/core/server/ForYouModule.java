package ai.core.server;

import ai.core.api.server.foryou.ForYouWebService;
import ai.core.server.web.foryou.ForYouService;
import ai.core.server.web.foryou.ForYouWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ForYouModule extends Module {
    @Override
    protected void initialize() {
        bind(ForYouService.class);
        api().service(ForYouWebService.class, bind(ForYouWebServiceImpl.class));
    }
}
