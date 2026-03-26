package ai.core.session.permission;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class PermissionRule {
    private static final List<String> PRIMARY_KEYS = List.of("file_path", "path", "script_path", "directory", "command");

    public static String buildPattern(String toolName, Map<String, Object> arguments) {
        var primaryArg = extractPrimaryArg(arguments);
        return primaryArg.map(s -> toolName + "(" + s + ")").orElse(toolName);
    }

    private static Optional<String> extractPrimaryArgSupport(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return Optional.empty();
        var matched = PRIMARY_KEYS.stream()
                .map(arguments::get)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .sorted(java.util.Comparator.comparingInt(String::length))
                .toList();
        if (!matched.isEmpty()) return Optional.of(matched.getFirst());
        return arguments.keySet().stream()
                .sorted()
                .map(arguments::get)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst();
    }

    public static Optional<String> extractPrimaryArg(Map<String, Object> arguments) {
        return extractPrimaryArgSupport(arguments);
    }

    public static boolean matches(String pattern, String toolName, Map<String, Object> arguments) {
        int parenOpen = pattern.indexOf('(');
        if (parenOpen < 0) {
            return pattern.equals(toolName);
        }

        String patternTool = pattern.substring(0, parenOpen);
        if (!patternTool.equals(toolName)) return false;

        String argPattern = pattern.substring(parenOpen + 1, pattern.length() - 1);
        String primaryArg = extractPrimaryArgSupport(arguments).orElse("");
        return globMatch(argPattern, primaryArg);
    }

    static boolean globMatch(String pattern, String text) {
        String normalizedPattern = normalizePath(pattern);
        String normalizedText = normalizePath(text);
        if ("*".equals(normalizedPattern)) return true;
        if (normalizedPattern.endsWith("**")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 2);
            return normalizedText.startsWith(prefix);
        }
        if (normalizedPattern.endsWith("*")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
            return normalizedText.startsWith(prefix);
        }
        return normalizedPattern.equals(normalizedText);
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }
}
