package ai.core.session;

public record PermissionRule(
        String toolPattern,
        String argumentPattern,
        PermissionAction action
) {
    public enum PermissionAction {
        ALLOW,
        DENY,
        ASK
    }

    public boolean matches(String toolName, String arguments) {
        if (!globMatches(toolPattern, toolName)) return false;
        if (argumentPattern == null) return true;
        return arguments != null && arguments.matches(argumentPattern);
    }

    private static boolean globMatches(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("*")) return value.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(value);
    }
}
