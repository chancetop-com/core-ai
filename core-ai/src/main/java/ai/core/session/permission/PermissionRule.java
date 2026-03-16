package ai.core.session.permission;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class PermissionRule {
    public static String buildPattern(String toolName, Map<String, Object> arguments) {
        var primaryArg = extractPrimaryArg(arguments);
        return primaryArg.map(s -> toolName + "(" + s + ")").orElse(toolName);
    }

    public static Optional<String> extractPrimaryArg(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return Optional.empty();
        return arguments.keySet().stream()
                .sorted()
                .map(arguments::get)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst();
    }

    public static boolean matches(String pattern, String toolName, Map<String, Object> arguments) {
        int parenOpen = pattern.indexOf('(');
        if (parenOpen < 0) {
            return pattern.equals(toolName);
        }

        String patternTool = pattern.substring(0, parenOpen);
        if (!patternTool.equals(toolName)) return false;

        String argPattern = pattern.substring(parenOpen + 1, pattern.length() - 1);
        String primaryArg = extractPrimaryArg(arguments).orElse("");
        return globMatch(argPattern, primaryArg);
    }

    static boolean globMatch(String pattern, String text) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return text.startsWith(prefix);
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return text.startsWith(prefix);
        }
        return pattern.equals(text);
    }
}
