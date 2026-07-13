package ai.core.server.agent;

import ai.core.api.server.agent.ListAgentsRequest;
import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;

import java.util.regex.Pattern;

/**
 * Static query/filter helpers extracted from {@link AgentDefinitionService}
 * to keep file length under the checkstyle limit.
 *
 * @author stephen
 */
final class AgentQueryHelper {
    private static final String AIRAGENT_USER_ID_FIELD = "user_id";
    private static final String AIRAGENT_SYSTEM_DEFAULT_FIELD = "system_default";

    static Bson buildAccessFilter(String userId, ListAgentsRequest request) {
        Boolean myAgents = myAgentsFilter(request.myAgents);
        if (myAgents != null && myAgents) {
            if (Boolean.FALSE.equals(request.includeSystemDefault)) {
                return Filters.and(
                    Filters.eq(AIRAGENT_USER_ID_FIELD, userId),
                    Filters.ne(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
                );
            }
            return Filters.or(
                Filters.eq(AIRAGENT_USER_ID_FIELD, userId),
                Filters.eq(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            );
        } else if (myAgents != null) {
            return Filters.and(
                Filters.ne(AIRAGENT_USER_ID_FIELD, userId),
                Filters.ne(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            );
        }
        return Filters.empty();
    }

    static Boolean myAgentsFilter(String myAgents) {
        if (myAgents == null) return null;
        return "true".equalsIgnoreCase(myAgents) || "1".equals(myAgents);
    }

    static String sortField(String sort) {
        if ("created_at".equals(sort)) return "created_at";
        return "updated_at";
    }

    static Bson buildSearchFilter(String query) {
        if (query == null || query.isBlank()) return Filters.empty();
        var pattern = "(?i)" + Pattern.quote(query.trim());
        return Filters.or(
            Filters.regex("name", pattern),
            Filters.regex("description", pattern)
        );
    }

    static Bson combineFilters(Bson first, Bson second) {
        var firstEmpty = isFilterEmpty(first);
        var secondEmpty = isFilterEmpty(second);
        if (firstEmpty && secondEmpty) return Filters.empty();
        if (firstEmpty) return second;
        if (secondEmpty) return first;
        return Filters.and(first, second);
    }

    static boolean isFilterEmpty(Bson filter) {
        return filter == null
            || filter.getClass().getSimpleName().startsWith("Empty")
            || filter.toBsonDocument().isEmpty();
    }
}
