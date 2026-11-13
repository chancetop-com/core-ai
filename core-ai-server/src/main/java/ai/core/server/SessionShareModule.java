package ai.core.server;

import ai.core.api.server.share.PublicSessionShareWebService;
import ai.core.api.server.share.SessionShareWebService;
import ai.core.server.share.MongoSessionShareStore;
import ai.core.server.share.PgSessionShareStore;
import ai.core.server.share.PublicSessionShareWebServiceImpl;
import ai.core.server.share.SessionShareFileController;
import ai.core.server.share.SessionShareFileService;
import ai.core.server.share.SessionShareInterceptor;
import ai.core.server.share.SessionShareLogAppender;
import ai.core.server.share.SessionShareProjection;
import ai.core.server.share.SessionShareService;
import ai.core.server.share.SessionShareStore;
import ai.core.server.share.SessionShareTraceService;
import ai.core.server.share.SessionShareWebServiceImpl;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author Xander
 */
public class SessionShareModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SessionShareStore.class, bind(PgSessionShareStore.class));
        } else {
            bind(SessionShareStore.class, bind(MongoSessionShareStore.class));
        }
        bind(SessionShareFileService.class);
        bind(SessionShareProjection.class);
        bind(SessionShareService.class);
        bind(SessionShareTraceService.class);
        api().service(SessionShareWebService.class, bind(SessionShareWebServiceImpl.class));
        api().service(PublicSessionShareWebService.class, bind(PublicSessionShareWebServiceImpl.class));
        http().route(HTTPMethod.GET, "/api/public/session-shares/:token/files/:refId/content", bind(SessionShareFileController.class));
        http().intercept(bind(new SessionShareInterceptor()));
        log().maskFields("token");
        if (context.logManager.appender != null) {
            context.logManager.appender = new SessionShareLogAppender(context.logManager.appender);
        }
    }
}
