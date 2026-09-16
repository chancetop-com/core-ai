package ai.core.server.tool;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.User;
import ai.core.tool.OutboundCallerContext.Caller;
import core.framework.mongo.MongoCollection;

/**
 * Builds the caller identity from a user record. For API sub users the caller carries the
 * business-side external_id + owner (manager) id + custom metadata; for managers the caller
 * is the manager itself; for internal users all business fields are null (no injection).
 *
 * @author stephen
 */
public final class CallerContexts {
    public static Caller fromUser(User user) {
        if (user == null) return null;
        var isManager = user.ownerId == null && "api".equals(user.userType);
        var managerId = isManager ? user.id : user.ownerId;
        return new Caller(user.externalId, user.id, managerId, user.metadata);
    }

    /**
     * Resolves the caller identity from the authenticated user and attaches it to the execution context.
     *
     * @return the resolved user, so callers that need it for the prompt do not read it a second time
     */
    public static User attach(ExecutionContext context, MongoCollection<User> userCollection, String userId) {
        var user = userId != null ? userCollection.get(userId).orElse(null) : null;
        context.setCaller(fromUser(user));
        return user;
    }

    private CallerContexts() {
    }
}
