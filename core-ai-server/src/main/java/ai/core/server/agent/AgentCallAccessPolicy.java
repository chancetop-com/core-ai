package ai.core.server.agent;

import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.PermissionService;
import core.framework.inject.Inject;

/**
 * Who may call which agent — the shared closure of every agent execution entry point
 * ({@code /api/a2a/*}, {@code /api/hub/agents/*}, {@code /api/runs/*}).
 * <p>
 * Users without a configured resource whitelist are unrestricted; API users with one are limited
 * to the whitelisted agent ids ({@link PermissionService#checkResource}). Quota only applies to
 * users with a configured allowance ({@link ApiUserQuotaService#checkQuota}).
 * <p>
 * Bound in {@code AgentDefinitionModule} so that {@code A2AModule} (which injects it from
 * {@code ServerA2AService}) can resolve it.
 *
 * @author stephen
 */
public class AgentCallAccessPolicy {
    @Inject
    PermissionService permissionService;
    @Inject
    ApiUserQuotaService apiUserQuotaService;

    /** Throws when the caller may not run the given agent: not whitelisted (403) or out of quota (429). */
    public void checkCanRun(String userId, String agentId) {
        permissionService.check(userId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
        apiUserQuotaService.checkQuota(userId);
    }

    /** Non-throwing whitelist check, used to filter listings and to resolve names. */
    public boolean canAccess(String userId, String agentId) {
        return userId != null && permissionService.checkResource(userId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
    }

    /** Whether the caller is an API user (business system), used to classify audit rows. */
    public boolean isApiUser(String userId) {
        return userId != null && permissionService.isApiUser(userId);
    }
}
