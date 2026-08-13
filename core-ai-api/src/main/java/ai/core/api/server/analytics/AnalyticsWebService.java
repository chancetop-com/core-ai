package ai.core.api.server.analytics;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface AnalyticsWebService {
    @GET
    @Path("/api/admin/analytics/global")
    AnalyticsGlobalView global(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/trend")
    ListAnalyticsTrendResponse trend(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/by-source")
    AnalyticsDimensionView bySource(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/by-agent")
    AnalyticsDimensionView byAgent(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/by-user")
    AnalyticsDimensionView byUser(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/by-provider")
    AnalyticsDimensionView byProvider(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/by-model")
    AnalyticsDimensionView byModel(AnalyticsQueryRequest request);

    @GET
    @Path("/api/admin/analytics/:dimension/trend")
    ListAnalyticsDimensionTrendResponse dimensionTrend(@PathParam("dimension") String dimension, AnalyticsDimensionTrendRequest request);
}
