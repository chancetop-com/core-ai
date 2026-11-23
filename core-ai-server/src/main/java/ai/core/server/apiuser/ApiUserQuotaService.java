package ai.core.server.apiuser;

import ai.core.server.domain.User;
import ai.core.server.user.UserStore;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Daily token quota for all users. UTC day window, lazy reset.
 * Unconfigured users (no quota) are unrestricted; only configured users are checked and metered.
 *
 * @author stephen
 */
public class ApiUserQuotaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiUserQuotaService.class);

    @Inject
    UserStore userStore;

    public void checkQuota(String userId) {
        var user = userStore.get(userId).orElse(null);
        if (user == null) return;

        var now = ZonedDateTime.now();
        if (windowResetDue(user, now)) {
            userStore.resetQuota(userId, now);
            user.quotaConsumedInputTokens = 0L;
            user.quotaConsumedOutputTokens = 0L;
            user.quotaWindowStart = now;
        }
        if (user.quotaInputTokens != null && user.quotaInputTokens > 0) {
            long consumed = user.quotaConsumedInputTokens != null ? user.quotaConsumedInputTokens : 0L;
            if (consumed >= user.quotaInputTokens) {
                throw new QuotaExceededException("input quota exceeded");
            }
        }
        if (user.quotaOutputTokens != null && user.quotaOutputTokens > 0) {
            long consumed = user.quotaConsumedOutputTokens != null ? user.quotaConsumedOutputTokens : 0L;
            if (consumed >= user.quotaOutputTokens) {
                throw new QuotaExceededException("output quota exceeded");
            }
        }
    }

    /**
     * Records token consumption per LLM call. Invoked synchronously from the ExecutionContext
     * tokenCostCallback (wired in SessionContextBuilder / AgentRunBuilder) and from the direct
     * LLM_CALL paths (AgentRunService / AgentRunTracer), so usage is attributed to the user whose
     * quota was checked — independent of trace span attribution.
     * Conditionally $inc only for users with a configured quota, so unconfigured users are untouched.
     * The window counter is lazily reset by {@link #checkQuota} on the next day boundary.
     * <p>
     * Metering is a side effect of a live turn: a database failure here must not abort the run.
     * The consumed-token counter can lag by one call when it is unavailable.
     */
    public void recordUsage(String userId, long inputTokens, long outputTokens) {
        if (userId == null) return;
        try {
            userStore.incrementQuotaUsage(userId, inputTokens, outputTokens);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record quota usage, userId={}, inputTokens={}, outputTokens={}, error={}",
                userId, inputTokens, outputTokens, e.getMessage());
        }
    }

    /**
     * Manually resets the current quota window (admin action): consumed counters back to zero
     * and the window start moved to now, so the user immediately gets a fresh allowance.
     * Targeted $set so a concurrent quota $inc is not overwritten (same pattern as the lazy
     * day-boundary reset in {@link #checkQuota}).
     */
    public void resetQuota(String userId) {
        userStore.resetQuota(userId, ZonedDateTime.now());
    }

    private boolean windowResetDue(User user, ZonedDateTime now) {
        if (user.quotaWindowStart == null) return true;
        return !user.quotaWindowStart.toLocalDate().equals(now.toLocalDate());
    }
}
