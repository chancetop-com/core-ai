package ai.core.session.permission;

import java.util.Map;
import java.util.Optional;

public class PathExtractor {
    private static final Map<String, String> TOOL_PATH_PARAMS = Map.of(
            "read_file", "file_path",
            "write_file", "file_path",
            "edit_file", "file_path",
            "glob_file", "path",
            "grep_file", "path"
    );

    public static Optional<String> extractPath(String toolName, Map<String, Object> arguments) {
        if (arguments == null) return Optional.empty();
        var paramName = TOOL_PATH_PARAMS.get(toolName);
        if (paramName == null) return Optional.empty();
        return Optional.ofNullable(arguments.get(paramName)).map(Object::toString);
    }
}
