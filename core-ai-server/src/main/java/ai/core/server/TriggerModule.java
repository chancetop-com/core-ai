package ai.core.server;

import ai.core.api.server.trigger.TriggerWebService;
import ai.core.server.trigger.TriggerController;
import ai.core.server.trigger.MongoTriggerStore;
import ai.core.server.trigger.PgTriggerStore;
import ai.core.server.trigger.TriggerService;
import ai.core.server.trigger.TriggerStore;
import ai.core.server.trigger.action.RunAgentAction;
import ai.core.server.web.TriggerWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class TriggerModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(TriggerStore.class, bind(PgTriggerStore.class));
        } else {
            bind(TriggerStore.class, bind(MongoTriggerStore.class));
        }
        var triggerService = bind(TriggerService.class);
        triggerService.publicUrl = property("sys.public.url").orElse("http://localhost:8080");

        bind(RunAgentAction.class);
        api().service(TriggerWebService.class, bind(TriggerWebServiceImpl.class));

        var controller = bind(TriggerController.class);
        http().route(HTTPMethod.POST, "/api/webhook-triggers/:id", controller);
        http().route(HTTPMethod.GET, "/api/webhook-triggers/:id", controller);
    }
}
