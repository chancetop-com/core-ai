package ai.core.server;

import ai.core.api.server.analytics.AnalyticsWebService;
import ai.core.server.analytics.AdminAnalyticsService;
import ai.core.server.analytics.AnalyticsMappingService;
import ai.core.server.analytics.AnalyticsWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AnalyticsModule extends Module {
    @Override
    protected void initialize() {
        bind(AnalyticsMappingService.class);
        bind(AdminAnalyticsService.class);
        api().service(AnalyticsWebService.class, bind(AnalyticsWebServiceImpl.class));
    }
}
