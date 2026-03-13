package ai.core.session.permission;

import java.util.Objects;

public class PermissionRule {
    private final String toolName;
    private final String pathPattern;
    private final PermissionLevel level;
    private final PermissionScope scope;
    private final int priority;

    public PermissionRule(String toolName, String pathPattern, PermissionLevel level, PermissionScope scope, int priority) {
        this.toolName = toolName;
        this.pathPattern = pathPattern;
        this.level = level;
        this.scope = scope;
        this.priority = priority;
    }

    public boolean matchesToolName(String name) {
        if ("*".equals(toolName)) return true;
        return Objects.equals(toolName, name);
    }

    public boolean matchesPath(String path) {
        if (pathPattern == null || pathPattern.isEmpty()) return true;
        if (path == null) return true;

        if (pathPattern.endsWith("/**")) {
            var prefix = pathPattern.substring(0, pathPattern.length() - 3);
            return path.startsWith(prefix);
        }
        if (pathPattern.endsWith("/*")) {
            var prefix = pathPattern.substring(0, pathPattern.length() - 2);
            return path.startsWith(prefix) && !path.substring(prefix.length() + 1).contains("/");
        }
        return Objects.equals(pathPattern, path);
    }

    public String getToolName() {
        return toolName;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public PermissionLevel getLevel() {
        return level;
    }

    public PermissionScope getScope() {
        return scope;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionRule that)) return false;
        return Objects.equals(toolName, that.toolName)
                && Objects.equals(pathPattern, that.pathPattern)
                && level == that.level
                && scope == that.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, pathPattern, level, scope);
    }
}
