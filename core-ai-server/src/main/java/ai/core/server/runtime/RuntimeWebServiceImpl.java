package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimeLimitsRequest;
import ai.core.api.server.runtime.RuntimeLimitsView;
import ai.core.api.server.runtime.RuntimePodsView;
import ai.core.api.server.runtime.RuntimeSeriesView;
import ai.core.api.server.runtime.RuntimeStatsView;
import ai.core.api.server.runtime.RuntimeWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.SYSTEM_MANAGE)
public class RuntimeWebServiceImpl implements RuntimeWebService {
    @Inject
    WebContext webContext;
    @Inject
    RuntimeSettingsService runtimeSettingsService;
    @Inject
    RuntimeStatsService runtimeStatsService;
    @Inject
    RuntimeSampleService runtimeSampleService;

    @Override
    public RuntimeLimitsView limits() {
        return runtimeSettingsService.get(userId());
    }

    @Override
    public RuntimeLimitsView updateLimits(RuntimeLimitsRequest request) {
        return runtimeSettingsService.update(request, userId());
    }

    @Override
    public RuntimeStatsView stats() {
        return runtimeStatsService.stats();
    }

    @Override
    public RuntimePodsView pods() {
        return runtimeSampleService.pods(userId());
    }

    @Override
    public RuntimeSeriesView series(Integer hours, Integer bucketMinutes) {
        return runtimeSampleService.series(hours, bucketMinutes, userId());
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
