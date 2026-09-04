package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-memory search matching and paging for the {@code /api/skills} management list —
 * kept out of {@link SkillService} to stay under the file-length limit. Indexed
 * filters (namespace/source_type) run in Mongo; text/user-id filters match here.
 *
 * @author stephen
 */
public final class SkillQueryHelper {
    private static final String SEARCH_IN_NAME_DESCRIPTION = "name_description";
    private static final String SEARCH_IN_NAME = "name";
    private static final String SEARCH_IN_METADATA = "metadata";
    private static final String SEARCH_IN_CONTENT = "content";

    public static Bson indexedFilter(SkillFilter filter) {
        if (filter == null) return Filters.empty();
        var filters = new ArrayList<Bson>();
        if (filter.namespace() != null && !filter.namespace().isBlank()) {
            filters.add(Filters.eq("namespace", filter.namespace()));
        }
        if (filter.sourceType() != null && !filter.sourceType().isBlank()) {
            filters.add(Filters.eq("source_type", SkillSourceType.valueOf(filter.sourceType())));
        }
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }

    public static Query sortedQuery(Bson filter) {
        var dbQuery = new Query();
        dbQuery.filter = filter;
        dbQuery.sort = Sorts.descending("updated_at");
        return dbQuery;
    }

    public static void applyPaging(Query dbQuery, Integer offset, Integer limit) {
        if (offset != null || limit != null) {
            dbQuery.skip = Math.max(0, offset != null ? offset : 0);
            dbQuery.limit = normalizedLimit(limit);
        }
    }

    public static List<SkillDefinition> page(List<SkillDefinition> skills, Integer offset, Integer limit) {
        if (offset == null && limit == null) {
            return skills;
        }

        int start = Math.max(0, offset != null ? offset : 0);
        if (start >= skills.size()) {
            return List.of();
        }

        int end = Math.min(skills.size(), start + normalizedLimit(limit));
        return skills.subList(start, end);
    }

    public static boolean notInMemoryFilters(String userId, String query) {
        return noText(userId) && noText(query);
    }

    public static boolean matchesUserId(SkillDefinition skill, String userId) {
        if (noText(userId)) return true;
        return containsIgnoreCase(skill.userId, userId.trim());
    }

    public static boolean matchesQuery(SkillDefinition skill, String query, String searchIn) {
        if (noText(query)) return true;

        var needle = query.trim();
        return switch (searchIn) {
            case SEARCH_IN_NAME -> matchesName(skill, needle);
            case SEARCH_IN_METADATA -> matchesMetadata(skill, needle);
            case SEARCH_IN_CONTENT -> containsIgnoreCase(skill.content, needle);
            default -> matchesName(skill, needle) || containsIgnoreCase(skill.description, needle);
        };
    }

    public static String normalizedSearchIn(String searchIn) {
        if (noText(searchIn)) return SEARCH_IN_NAME_DESCRIPTION;
        var value = searchIn.trim().toLowerCase(Locale.getDefault());
        return switch (value) {
            case SEARCH_IN_NAME, SEARCH_IN_NAME_DESCRIPTION, SEARCH_IN_METADATA, SEARCH_IN_CONTENT -> value;
            default -> SEARCH_IN_NAME_DESCRIPTION;
        };
    }

    private static boolean matchesName(SkillDefinition skill, String needle) {
        return containsIgnoreCase(skill.name, needle) || containsIgnoreCase(skill.qualifiedName, needle);
    }

    private static boolean matchesMetadata(SkillDefinition skill, String needle) {
        if (matchesName(skill, needle)) return true;
        if (containsIgnoreCase(skill.description, needle)) return true;
        if (containsIgnoreCase(skill.namespace, needle)) return true;
        if (skill.sourceType != null && containsIgnoreCase(skill.sourceType.name(), needle)) return true;
        if (containsIgnoreCase(skill.userId, needle)) return true;
        if (containsIgnoreCase(skill.version, needle)) return true;
        if (skill.metadata != null) {
            for (var entry : skill.metadata.entrySet()) {
                if (containsIgnoreCase(entry.getKey(), needle) || containsIgnoreCase(entry.getValue(), needle)) return true;
            }
        }
        if (skill.allowedTools != null) {
            for (var tool : skill.allowedTools) {
                if (containsIgnoreCase(tool, needle)) return true;
            }
        }
        return false;
    }

    private static boolean noText(String value) {
        return value == null || value.isBlank();
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle.isEmpty() || needle.length() > value.length()) return false;
        for (int i = 0; i <= value.length() - needle.length(); i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    private static int normalizedLimit(Integer limit) {
        return Math.clamp(limit != null ? limit : 20, 1, 100);
    }

    private SkillQueryHelper() {
    }
}
