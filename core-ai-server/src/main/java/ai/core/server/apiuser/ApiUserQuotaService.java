package ai.core.server.apiuser;

import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.TooManyRequestsException;

import java.time.ZonedDateTime;

/**
 * Daily token quota for API users. UTC day window, lazy reset.
 *
 * @author stephen
 */
public class ApiUserQuotaService {
    @Inject
    MongoCollection<User> userCollection;

    public void checkQuota(String userId) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null || !"api".equals(user.userType)) return;
        if (user.quotaTokens == null || user.quotaTokens <= 0) return;

        var now = ZonedDateTime.now();
        if (windowResetDue(user, now)) {
            // targeted $set so concurrent quota $inc is not overwritten by a full replace
            userCollection.update(Filters.eq("_id", userId), Updates.combine(
                    Updates.set("quota_consumed_tokens", 0L),
                    Updates.set("quota_window_start", now)));
            user.quotaConsumedTokens = 0L;
            user.quotaWindowStart = now;
        }
        long consumed = user.quotaConsumedTokens != null ? user.quotaConsumedTokens : 0L;
        if (consumed >= user.quotaTokens) {
            throw new TooManyRequestsException("quota exceeded");
        }
    }

    /**
     * Records token consumption at the LLM trace write point (idempotent per span via span_id unique index).
     * Conditionally $inc only for api users with a configured quota, so internal users are untouched.
     * The window counter is lazily reset by {@link #checkQuota} on the next day boundary.
     */
    public void recordUsage(String userId, long tokens) {
        if (userId == null || tokens <= 0) return;
        userCollection.update(
            Filters.and(
                Filters.eq("_id", userId),
                Filters.eq("user_type", "api"),
                Filters.ne("quota_tokens", null),
                Filters.gt("quota_tokens", 0)),
            Updates.inc("quota_consumed_tokens", tokens)
        );
    }

    private boolean windowResetDue(User user, ZonedDateTime now) {
        if (user.quotaWindowStart == null) return true;
        return !user.quotaWindowStart.toLocalDate().equals(now.toLocalDate());
    }
}
