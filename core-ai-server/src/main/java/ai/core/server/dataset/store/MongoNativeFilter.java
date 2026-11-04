package ai.core.server.dataset.store;

import com.mongodb.client.model.Filters;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates the dataset filter JSON into a Mongo query. The matching rules of the existing in-memory
 * implementation are kept where Mongo's natural behavior differs: scalars are compared with a
 * "not an array" guard, object values with {@code $setEquals($objectToArray(...))} so key order does
 * not matter, and arrays with {@code $expr $eq} so an outer array element cannot satisfy the condition.
 *
 * @author stephen
 */
public final class MongoNativeFilter {
    private static final String PREFIX = "data.";
    private static final String SUPPORTED = "$eq, $ne, $gt, $gte, $lt, $lte, $in, $nin, $exists, $contains";

    public static Bson build(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return new Document();
        var conditions = new ArrayList<Bson>();
        for (var entry : filter.entrySet()) {
            conditions.add(condition(entry.getKey(), entry.getValue()));
        }
        return Filters.and(conditions);
    }

    private static Bson condition(String path, Object expected) {
        var field = field(path);
        if (expected == null) return Filters.eq(field, null);
        if (isOperatorObject(expected)) {
            var conditions = new ArrayList<Bson>();
            for (var entry : ((Map<?, ?>) expected).entrySet()) {
                conditions.add(operator(field, (String) entry.getKey(), entry.getValue()));
            }
            return Filters.and(conditions);
        }
        return valueEquals(field, expected);
    }

    private static boolean isOperatorObject(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return false;
        for (var key : map.keySet()) {
            if (!(key instanceof String name) || !name.startsWith("$")) return false;
        }
        return true;
    }

    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    private static Bson operator(String field, String name, Object expected) {
        return switch (name) {
            case "$eq" -> expected == null ? Filters.eq(field, null) : valueEquals(field, expected);
            case "$ne" -> Filters.ne(field, expected);
            case "$gt" -> Filters.gt(field, expected);
            case "$gte" -> Filters.gte(field, expected);
            case "$lt" -> Filters.lt(field, expected);
            case "$lte" -> Filters.lte(field, expected);
            case "$in" -> Filters.in(field, array(expected, "$in"));
            case "$nin" -> Filters.nin(field, array(expected, "$nin"));
            case "$exists" -> Filters.exists(field, (Boolean) expected);
            case "$contains" -> Filters.regex(field, Pattern.quote((String) expected), "i");
            default -> throw new IllegalArgumentException("unsupported filter operator " + name + ", supported: " + SUPPORTED);
        };
    }

    private static List<?> array(Object expected, String operator) {
        if (!(expected instanceof List<?> values)) {
            throw new IllegalArgumentException("filter operator " + operator + " requires an array value");
        }
        return values;
    }

    private static Bson valueEquals(String field, Object expected) {
        if (expected instanceof Map<?, ?> map) {
            var entries = new ArrayList<Document>(map.size());
            for (var entry : map.entrySet()) {
                entries.add(new Document("k", entry.getKey()).append("v", entry.getValue()));
            }
            return Filters.expr(new Document("$setEquals", List.of(new Document("$objectToArray", "$" + field),
                    new Document("$literal", entries))));
        }
        if (expected instanceof List<?>) {
            return Filters.expr(new Document("$eq", List.of("$" + field, new Document("$literal", expected))));
        }
        // Mongo matches an array field when one of its elements equals the scalar; the existing
        // implementation compares the value itself, so exclude array fields explicitly
        return Filters.and(Filters.eq(field, expected), Filters.not(Filters.type(field, "array")));
    }

    private static String field(String path) {
        for (var part : path.split("\\.")) {
            if (part.isEmpty() || part.startsWith("$")) {
                throw new IllegalArgumentException("invalid filter field path: " + path);
            }
        }
        return PREFIX + path;
    }

    /**
     * Validates a data object before it is written as a BSON sub-document: keys with a leading {@code $}
     * or a dot cannot be addressed by the dot-path filter syntax and are rejected.
     */
    public static void validateData(Map<String, Object> data, String location) {
        for (var entry : data.entrySet()) {
            var key = entry.getKey();
            if (key.isEmpty() || key.startsWith("$") || key.contains(".")) {
                throw new IllegalArgumentException("invalid field name at " + location + ": " + key);
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) nested;
                validateData(map, location + "." + key);
            }
            if (entry.getValue() instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> nested) {
                        @SuppressWarnings("unchecked")
                        var map = (Map<String, Object>) nested;
                        validateData(map, location + "." + key);
                    }
                }
            }
        }
    }

    /**
     * Flattens an RFC 7386 patch into {@code $set}/{@code $unset} dot paths. Returns null when the patch
     * contains an empty object, whose semantics (replace the target with an object) cannot be expressed as
     * a partial update.
     */
    public static Patch translate(Map<String, Object> patch) {
        var assignments = new LinkedHashMap<String, Object>();
        var removals = new LinkedHashMap<String, Object>();
        boolean complete = collect(patch, "data", assignments, removals);
        return complete ? new Patch(assignments, removals) : null;
    }

    private static boolean collect(Map<String, Object> patch, String prefix, Map<String, Object> assignments, Map<String, Object> removals) {
        for (var entry : patch.entrySet()) {
            var key = entry.getKey();
            if (key.startsWith("$") || key.contains(".")) throw new IllegalArgumentException("invalid field name: " + key);
            var path = prefix + "." + key;
            var value = entry.getValue();
            if (value == null) {
                removals.put(path, "");
            } else if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) return false;
                @SuppressWarnings("unchecked")
                var nested = (Map<String, Object>) map;
                if (!collect(nested, path, assignments, removals)) return false;
            } else {
                assignments.put(path, value);
            }
        }
        return true;
    }

    private MongoNativeFilter() {
    }

    public record Patch(Map<String, Object> assignments, Map<String, Object> removals) {
    }
}