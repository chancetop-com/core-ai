package ai.core.server;

import ai.core.api.server.foryou.ForYouWebService;
import ai.core.server.home.HomeTodoDigestJob;
import ai.core.server.home.HomeTodoDigestService;
import ai.core.server.home.HomeTodoDigestStore;
import ai.core.server.home.MongoHomeTodoDigestStore;
import ai.core.server.home.PgHomeTodoDigestStore;
import ai.core.server.web.foryou.ForYouService;
import ai.core.server.web.foryou.ForYouWebServiceImpl;
import core.framework.module.Module;

import java.time.Duration;

/**
 * For-you reads plus the daily todo digest. The job wakes every 15 minutes and the service's
 * Shanghai-time guard decides whether the day's run is due, so a pod restart never skips the day
 * and no global scheduler timezone has to change.
 *
 * @author stephen
 */
public class ForYouModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(HomeTodoDigestStore.class, bind(PgHomeTodoDigestStore.class));
        } else {
            bind(HomeTodoDigestStore.class, bind(MongoHomeTodoDigestStore.class));
        }
        bind(HomeTodoDigestService.class);
        schedule().fixedRate("home-todo-digest", bind(HomeTodoDigestJob.class), Duration.ofMinutes(15));
        bind(ForYouService.class);
        api().service(ForYouWebService.class, bind(ForYouWebServiceImpl.class));
    }
}
