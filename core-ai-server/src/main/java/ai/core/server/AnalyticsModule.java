package ai.core.server;

import ai.core.server.analytics.AdminAnalyticsController;
import ai.core.server.analytics.AdminAnalyticsService;
import ai.core.server.analytics.AnalyticsMappingService;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AnalyticsModule extends Module {
    @Override
    protected void initialize() {
        bind(AnalyticsMappingService.class);
        bind(AdminAnalyticsService.class);

        var analytics = bind(AdminAnalyticsController.class);
        http().route(HTTPMethod.GET, "/api/admin/analytics/global", analytics::global);
        http().route(HTTPMethod.GET, "/api/admin/analytics/trend", analytics::trend);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-source", analytics::bySource);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-agent", analytics::byAgent);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-user", analytics::byUser);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-provider", analytics::byProvider);
        http().route(HTTPMethod.GET, "/api/admin/analytics/by-model", analytics::byModel);
        http().route(HTTPMethod.GET, "/api/admin/analytics/:dimension/trend", analytics::dimensionTrend);
    }
}
