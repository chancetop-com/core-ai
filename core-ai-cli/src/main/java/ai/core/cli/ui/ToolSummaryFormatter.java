package ai.core.cli.ui;

import ai.core.utils.JsonUtil;

import java.util.List;
import java.util.Map;

final class ToolSummaryFormatter {

    @SuppressWarnings("unchecked")
    static String format(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.trim())) {
            return toolName;
        }
        try {
            Map<String, Object> argsMap = JsonUtil.fromJson(Map.class, arguments);
            String primaryValue = extractPrimaryArg(argsMap);
            if (primaryValue != null) {
                if (primaryValue.length() > 100) primaryValue = primaryValue.substring(0, 100) + "...";
                return toolName + "(" + primaryValue + ")";
            }
            var sb = new StringBuilder();
            sb.append(toolName).append('(');
            boolean first = true;
            for (var entry : argsMap.entrySet()) {
                if (!first) sb.append(", ");
                String value = String.valueOf(entry.getValue());
                if (value.length() > 60) value = value.substring(0, 60) + "...";
                sb.append(entry.getKey()).append(": ").append(value);
                first = false;
            }
            sb.append(')');
            return sb.toString();
        } catch (Exception e) {
            return toolName;
        }
    }

    private static String extractPrimaryArg(Map<String, Object> argsMap) {
        if (argsMap.size() == 1) {
            return String.valueOf(argsMap.values().iterator().next());
        }
        for (String key : List.of("command", "file_path", "pattern", "query", "prompt", "path", "url")) {
            if (argsMap.containsKey(key)) {
                return String.valueOf(argsMap.get(key));
            }
        }
        return null;
    }

    private ToolSummaryFormatter() {
    }
}
