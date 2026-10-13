package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

import java.util.List;

/**
 * Shared authorization for the comparison feature: management is limited to the creator (validated as
 * agent owner/admin at create time) or an admin; testers are the explicit participant list.
 *
 * @author stephen
 */
public final class ComparisonAccess {
    public static boolean canManage(MongoCollection<User> userCollection, AgentComparison comparison, String userId) {
        if (userId == null) return false;
        if (userId.equals(comparison.createdBy)) return true;
        return isAdmin(userCollection, userId);
    }

    public static boolean isParticipant(AgentComparison comparison, String userId) {
        if (userId == null || comparison.participantUserIds == null) return false;
        return comparison.participantUserIds.contains(userId);
    }

    public static void requireManage(MongoCollection<User> userCollection, AgentComparison comparison, String userId) {
        if (!canManage(userCollection, comparison, userId)) throw new ForbiddenException("comparison is unavailable");
    }

    public static void requireParticipant(AgentComparison comparison, String userId) {
        if (!isParticipant(comparison, userId)) throw new ForbiddenException("comparison is unavailable");
    }

    public static boolean isAdmin(MongoCollection<User> userCollection, String userId) {
        if (userId == null) return false;
        return userCollection.get(userId).map(user -> "admin".equals(user.role)).orElse(Boolean.FALSE);
    }

    /** Clean, de-duplicate and keep the creator (a manager) on the list. */
    public static List<String> cleanParticipants(List<String> participantUserIds, String createdBy) {
        var result = new java.util.LinkedHashSet<String>();
        if (participantUserIds != null) {
            for (var participant : participantUserIds) {
                if (participant != null && !participant.isBlank()) result.add(participant);
            }
        }
        if (createdBy != null && !createdBy.isBlank()) result.add(createdBy);
        return List.copyOf(result);
    }

    private ComparisonAccess() {
    }
}
