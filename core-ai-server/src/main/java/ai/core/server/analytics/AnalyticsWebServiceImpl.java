package ai.core.server.analytics;

import ai.core.api.server.analytics.AnalyticsDimensionItemView;
import ai.core.api.server.analytics.AnalyticsDimensionTrendPointView;
import ai.core.api.server.analytics.AnalyticsDimensionTrendRequest;
import ai.core.api.server.analytics.AnalyticsDimensionView;
import ai.core.api.server.analytics.AnalyticsGlobalView;
import ai.core.api.server.analytics.AnalyticsQueryRequest;
import ai.core.api.server.analytics.AnalyticsTrendPointView;
import ai.core.api.server.analytics.AnalyticsWebService;
import ai.core.api.server.analytics.ListAnalyticsDimensionTrendResponse;
import ai.core.api.server.analytics.ListAnalyticsTrendResponse;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;

import java.util.List;

/**
 * Admin analytics API. All endpoints require admin role.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.ANALYTICS_VIEW)
public class AnalyticsWebServiceImpl implements AnalyticsWebService {
    private static String mode(AnalyticsQueryRequest request) {
        return request.mode != null ? request.mode : "history";
    }

    private static String range(AnalyticsQueryRequest request) {
        return request.range != null ? request.range : "7d";
    }

    private static String sort(AnalyticsQueryRequest request) {
        return request.sort != null ? request.sort : "tokens";
    }

    @Inject
    AdminAnalyticsService analyticsService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    WebContext webContext;

    @Override
    public AnalyticsGlobalView global(AnalyticsQueryRequest request) {
        requireAdmin();
        return toGlobalView(analyticsService.globalSummary(
            mode(request), range(request), request.from, request.to));
    }

    @Override
    public ListAnalyticsTrendResponse trend(AnalyticsQueryRequest request) {
        requireAdmin();
        var points = analyticsService.trend(mode(request), range(request), request.from, request.to);
        var response = new ListAnalyticsTrendResponse();
        response.points = points.stream().map(point -> {
            var view = new AnalyticsTrendPointView();
            view.timestamp = point.timestamp();
            view.inputTokens = point.inputTokens();
            view.outputTokens = point.outputTokens();
            view.cachedTokens = point.cachedTokens();
            view.costUsd = point.costUsd();
            view.callCount = point.callCount();
            return view;
        }).toList();
        return response;
    }

    @Override
    public AnalyticsDimensionView bySource(AnalyticsQueryRequest request) {
        requireAdmin();
        return toDimensionView(analyticsService.bySource(
            mode(request), range(request), request.from, request.to, sort(request)));
    }

    @Override
    public AnalyticsDimensionView byAgent(AnalyticsQueryRequest request) {
        requireAdmin();
        return toDimensionView(analyticsService.byAgent(
            mode(request), range(request), request.from, request.to, sort(request)));
    }

    @Override
    public AnalyticsDimensionView byUser(AnalyticsQueryRequest request) {
        requireAdmin();
        return toDimensionView(analyticsService.byUser(
            mode(request), range(request), request.from, request.to, sort(request)));
    }

    @Override
    public AnalyticsDimensionView byProvider(AnalyticsQueryRequest request) {
        requireAdmin();
        return toDimensionView(analyticsService.byProvider(
            mode(request), range(request), request.from, request.to, sort(request)));
    }

    @Override
    public AnalyticsDimensionView byModel(AnalyticsQueryRequest request) {
        requireAdmin();
        return toDimensionView(analyticsService.byModel(
            mode(request), range(request), request.from, request.to, sort(request)));
    }

    @Override
    public ListAnalyticsDimensionTrendResponse dimensionTrend(String dimension, AnalyticsDimensionTrendRequest request) {
        requireAdmin();
        List<String> keys = request.keys != null ? List.of(request.keys.split(",")) : List.of();
        var points = analyticsService.dimensionTrend(dimension,
            request.mode != null ? request.mode : "history",
            request.range != null ? request.range : "7d",
            request.from, request.to, keys);
        var response = new ListAnalyticsDimensionTrendResponse();
        response.points = points.stream().map(point -> {
            var view = new AnalyticsDimensionTrendPointView();
            view.key = point.key();
            view.timestamp = point.timestamp();
            view.inputTokens = point.inputTokens();
            view.outputTokens = point.outputTokens();
            view.cachedTokens = point.cachedTokens();
            view.costUsd = point.costUsd();
            view.callCount = point.callCount();
            return view;
        }).toList();
        return response;
    }

    private AnalyticsGlobalView toGlobalView(AnalyticsModels.GlobalSummary summary) {
        var view = new AnalyticsGlobalView();
        view.totalInputTokens = summary.totalInputTokens();
        view.totalOutputTokens = summary.totalOutputTokens();
        view.totalTokens = summary.totalTokens();
        view.totalCachedTokens = summary.totalCachedTokens();
        view.totalCostUsd = summary.totalCostUsd();
        view.totalCalls = summary.totalCalls();
        view.avgTokensPerCall = summary.avgTokensPerCall();
        view.avgCostPerCall = summary.avgCostPerCall();
        view.maxTokensPerCall = summary.maxTokensPerCall();
        view.maxCostPerCall = summary.maxCostPerCall();
        view.p90TokensPerCall = summary.p90TokensPerCall();
        view.prevTotalTokens = summary.prevTotalTokens();
        view.prevTotalCostUsd = summary.prevTotalCostUsd();
        return view;
    }

    private AnalyticsDimensionView toDimensionView(AnalyticsModels.DimensionAnalytics analytics) {
        var view = new AnalyticsDimensionView();
        view.items = analytics.items().stream().map(this::toItemView).toList();
        view.totals = toGlobalView(analytics.totals());
        return view;
    }

    private AnalyticsDimensionItemView toItemView(AnalyticsModels.DimensionItem item) {
        var view = new AnalyticsDimensionItemView();
        view.key = item.key();
        view.label = item.label();
        view.inputTokens = item.inputTokens();
        view.outputTokens = item.outputTokens();
        view.totalTokens = item.totalTokens();
        view.cachedTokens = item.cachedTokens();
        view.costUsd = item.costUsd();
        view.callCount = item.callCount();
        view.avgInputTokens = item.avgInputTokens();
        view.avgOutputTokens = item.avgOutputTokens();
        view.avgTotalTokens = item.avgTotalTokens();
        view.avgCostUsd = item.avgCostUsd();
        view.maxTotalTokens = item.maxTotalTokens();
        view.maxCostUsd = item.maxCostUsd();
        view.p90TotalTokens = item.p90TotalTokens();
        view.tokenShare = item.tokenShare();
        view.costShare = item.costShare();
        return view;
    }

    private void requireAdmin() {
        String userId = AuthContext.userId(webContext);
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }
}
