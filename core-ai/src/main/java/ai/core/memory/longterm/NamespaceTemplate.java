package ai.core.memory.longterm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template for creating namespaces with dynamic variables.
 * Supports template variables like {user_id}, {org_id}, {session_id}.
 *
 * <p>Examples:
 * <pre>{@code
 * // Define template
 * NamespaceTemplate template = NamespaceTemplate.of("{org_id}", "{user_id}");
 *
 * // Resolve at runtime
 * Namespace ns = template.resolve(Map.of(
 *     "org_id", "org-123",
 *     "user_id", "user-456"
 * ));
 * // Result: Namespace("org-123", "user-456")
 *
 * // Common templates
 * NamespaceTemplate.USER_SCOPED       // ("user", "{user_id}")
 * NamespaceTemplate.SESSION_SCOPED    // ("session", "{session_id}")
 * NamespaceTemplate.ORG_USER_SCOPED   // ("{org_id}", "{user_id}")
 * }</pre>
 *
 * @author xander
 */
public final class NamespaceTemplate {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)}");

    /**
     * Common template: scope by user_id.
     */
    public static final NamespaceTemplate USER_SCOPED = of("user", "{user_id}");

    /**
     * Common template: scope by session_id.
     */
    public static final NamespaceTemplate SESSION_SCOPED = of("session", "{session_id}");

    /**
     * Common template: scope by org_id and user_id.
     */
    public static final NamespaceTemplate ORG_USER_SCOPED = of("{org_id}", "{user_id}");

    /**
     * Create a namespace template from parts.
     * Parts can contain variables like {user_id}.
     *
     * @param parts template parts
     * @return template instance
     */
    public static NamespaceTemplate of(String... parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("Template must have at least one part");
        }
        return new NamespaceTemplate(Arrays.asList(parts));
    }

    /**
     * Create a template from a path string.
     *
     * @param path path like "user/{user_id}" or "{org_id}/{user_id}"
     * @return template instance
     */
    public static NamespaceTemplate fromPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Template path cannot be null or blank");
        }
        return of(path.split("/"));
    }

    private final List<String> templateParts;

    private NamespaceTemplate(List<String> templateParts) {
        this.templateParts = templateParts;
    }

    /**
     * Resolve template variables using provided values.
     *
     * @param variables map of variable names to values
     * @return resolved namespace
     * @throws IllegalArgumentException if required variables are missing
     */
    public Namespace resolve(Map<String, String> variables) {
        List<String> resolved = new ArrayList<>();
        for (String part : templateParts) {
            resolved.add(resolvePart(part, variables));
        }
        return Namespace.of(resolved);
    }

    /**
     * Resolve template using user_id only.
     * Convenience method for simple user-scoped namespaces.
     *
     * @param userId user identifier
     * @return resolved namespace
     */
    public Namespace resolveForUser(String userId) {
        return resolve(Map.of("user_id", userId));
    }

    /**
     * Resolve template using session_id only.
     *
     * @param sessionId session identifier
     * @return resolved namespace
     */
    public Namespace resolveForSession(String sessionId) {
        return resolve(Map.of("session_id", sessionId));
    }

    /**
     * Check if this template contains any variables.
     *
     * @return true if template has variables
     */
    public boolean hasVariables() {
        for (String part : templateParts) {
            if (VARIABLE_PATTERN.matcher(part).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all variable names in this template.
     *
     * @return list of variable names (without braces)
     */
    public List<String> getVariableNames() {
        List<String> names = new ArrayList<>();
        for (String part : templateParts) {
            Matcher matcher = VARIABLE_PATTERN.matcher(part);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Get the template parts.
     *
     * @return list of template parts
     */
    public List<String> getParts() {
        return templateParts;
    }

    private String resolvePart(String part, Map<String, String> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(part);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new IllegalArgumentException("Missing required variable: " + varName);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    @Override
    public String toString() {
        return "NamespaceTemplate(" + String.join("/", templateParts) + ")";
    }
}
