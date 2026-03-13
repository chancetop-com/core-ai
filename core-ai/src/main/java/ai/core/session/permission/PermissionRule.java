package ai.core.session.permission;

import core.framework.api.json.Property;

import java.util.Objects;

public class PermissionRule {
    @Property(name = "tool_name")
    public String toolName;

    @Property(name = "path_pattern")
    public String pathPattern;

    public PermissionRule() {
    }

    public PermissionRule(String toolName, String pathPattern) {
        this.toolName = toolName;
        this.pathPattern = pathPattern;
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

    public boolean matches(String toolName, String path) {
        return matchesToolName(toolName) && matchesPath(path);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionRule that)) return false;
        return Objects.equals(toolName, that.toolName)
                && Objects.equals(pathPattern, that.pathPattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, pathPattern);
    }
}
