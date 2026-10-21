package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The desktop engine's counterpart of the server's submit_artifacts: files the agent created under the
 * workspace are registered in {@link LocalArtifactManifest} and announced with an {@code artifact} event,
 * so the artifact panel lists them and the protocol serves them. Media files are captured automatically
 * by {@link LocalArtifactSink}; this tool is for everything the sink cannot see — reports, HTML pages,
 * PDFs, exports. The file must already exist inside the workspace; nothing is copied or uploaded.
 *
 * @author stephen
 */
public final class LocalSubmitArtifactsTool extends ToolCall {
    public static final String TOOL_NAME = "submit_artifacts";
    /** Marks the delivery instructions in a session prompt so they are appended only once. */
    static final String PROMPT_MARKER = "# Delivering generated files";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSubmitArtifactsTool.class);

    static final String SYSTEM_INSTRUCTIONS = """

        # Delivering generated files

        When you create files that the user should open or keep afterwards — reports, documents, PDFs,
        HTML pages, spreadsheets, charts, exports — call the `submit_artifacts` tool with their paths
        before your final response. That is what puts them in the workspace artifact panel, where the
        user can preview and open them; a file that was only written to the workspace is easy to miss.
        The paths must be inside the workspace. Submit each file once it is final, not on every edit,
        and do not submit scratch or intermediate files. Generated images and videos are captured
        automatically — there is no need to submit those.
        """;

    private static final String TOOL_DESC = """
            Register files you created in the workspace so the user can preview and open them from the
            artifact panel. Use this for files the user should keep or open — reports, documents, PDFs,
            HTML pages, spreadsheets, charts, exports. Paths (relative to the workspace or absolute)
            must point at existing files inside the workspace. Generated images and videos are captured
            automatically, and files that are still being edited should not be submitted yet.
            """;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".bmp", "image/bmp"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".html", "text/html"),
            Map.entry(".htm", "text/html"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".json", "application/json"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".log", "text/plain"),
            Map.entry(".xml", "application/xml"));

    public static String appendInstructions(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return SYSTEM_INSTRUCTIONS.strip();
        return systemPrompt + SYSTEM_INSTRUCTIONS;
    }

    public static LocalSubmitArtifactsTool create(Path workspace) {
        var tool = new LocalSubmitArtifactsTool(workspace);
        tool.setName(TOOL_NAME);
        tool.setDescription(TOOL_DESC);
        tool.setParameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(List.class, "artifacts", """
                    Array of objects, each with a required `path` field — the workspace path of a file the
                    user should open or keep. Example: [{"path":"reports/weekly.html"},{"path":"out/report.pdf"}]
                    Do NOT pass an array of strings like ["reports/weekly.html"].
                    """).required()));
        tool.setNeedAuth(Boolean.FALSE);
        tool.setDirectReturn(Boolean.FALSE);
        tool.setLlmVisible(Boolean.TRUE);
        tool.setDiscoverable(Boolean.FALSE);
        return tool;
    }

    private static String contentTypeOf(String name) {
        var dot = name.lastIndexOf('.');
        return dot < 0 ? null : CONTENT_TYPES.get(name.substring(dot).toLowerCase(Locale.ROOT));
    }

    private static List<String> parsePaths(String arguments) throws JsonProcessingException {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        var root = JsonUtil.OBJECT_MAPPER.readTree(arguments);
        var node = root == null ? null : root.get("artifacts");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return parseTextualArray(node.asText());
        }
        return node.isArray() ? itemPaths(node) : List.of();
    }

    /** Some LLMs double-encode the array as a JSON string; a bare non-JSON string is treated as one path. */
    private static List<String> parseTextualArray(String text) {
        try {
            var parsed = JsonUtil.OBJECT_MAPPER.readTree(text);
            if (parsed == null || parsed.isNull()) {
                return List.of();
            }
            if (parsed.isArray()) {
                return itemPaths(parsed);
            }
            if (parsed.isObject()) {
                return itemPaths(JsonUtil.OBJECT_MAPPER.createArrayNode().add(parsed));
            }
            return parsed.isTextual() ? List.of(parsed.asText()) : List.of();
        } catch (JsonProcessingException e) {
            LOGGER.debug("submit_artifacts artifacts field is not JSON and is treated as a path: {}", e.getMessage());
            return text.isBlank() ? List.of() : List.of(text);
        }
    }

    private static List<String> itemPaths(JsonNode array) {
        var paths = new ArrayList<String>(array.size());
        for (var item : array) {
            if (item.isTextual()) {
                paths.add(item.asText());
            } else if (item.isObject() && item.hasNonNull("path")) {
                paths.add(item.get("path").asText());
            } else {
                paths.add("");
            }
        }
        return paths;
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private final Path workspace;

    private LocalSubmitArtifactsTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        List<String> requested;
        try {
            requested = parsePaths(arguments);
        } catch (JsonProcessingException e) {
            return ToolCallResult.failed("failed to parse submit_artifacts arguments: " + e.getMessage(), e);
        }
        if (requested.isEmpty()) {
            return ToolCallResult.failed("artifacts is required and must be a JSON array of objects with a path");
        }
        var registered = new ArrayList<Map<String, Object>>();
        var failed = new ArrayList<Map<String, Object>>();
        for (var path : requested) {
            register(path, context, registered, failed);
        }
        var payload = JsonUtil.toJson(Map.of("registered", registered, "failed", failed));
        if (registered.isEmpty()) {
            return ToolCallResult.failed(payload);
        }
        return ToolCallResult.completed(payload);
    }

    private void register(String path, ExecutionContext context,
                          List<Map<String, Object>> registered, List<Map<String, Object>> failed) {
        if (path == null || path.isBlank()) {
            failed.add(Map.of("path", "", "error", "path is required"));
            return;
        }
        Path file;
        try {
            file = Path.of(path);
        } catch (InvalidPathException e) {
            failed.add(Map.of("path", path, "error", "invalid path"));
            return;
        }
        if (!file.isAbsolute()) {
            file = workspace.resolve(file);
        }
        try {
            var real = file.toRealPath();
            var root = workspace.toRealPath();
            var relative = root.relativize(real);
            if (relative.getNameCount() == 0 || relative.startsWith("..") || relative.isAbsolute()) {
                failed.add(Map.of("path", path, "error", "path must point at a file inside the workspace"));
                return;
            }
            if (!Files.isRegularFile(real)) {
                failed.add(Map.of("path", path, "error", "path is not a regular file"));
                return;
            }
            var relativePath = relative.toString().replace('\\', '/');
            LocalArtifactManifest.append(workspace, relativePath, context == null ? null : context.getSessionId());
            var fileName = real.getFileName();
            var name = fileName == null ? "" : fileName.toString();
            var size = Files.size(real);
            publish(context, relativePath, name, size);
            registered.add(Map.of("path", relativePath, "name", name, "size", size));
        } catch (NoSuchFileException e) {
            failed.add(Map.of("path", path, "error", "file does not exist"));
        } catch (IOException | IllegalArgumentException e) {
            failed.add(Map.of("path", path, "error", message(e)));
        }
    }

    private void publish(ExecutionContext context, String relativePath, String name, long size) {
        if (context == null) {
            return;
        }
        try {
            var emitter = context.getCustomVariable(CustomEventEmitter.CONTEXT_KEY, CustomEventEmitter.class);
            if (emitter == null) {
                return;
            }
            var payload = Params.object();
            payload.put("path", relativePath);
            payload.put("kind", LocalArtifacts.kindOf(name));
            payload.put("name", name);
            var contentType = contentTypeOf(name);
            if (contentType != null) {
                payload.put("contentType", contentType);
            }
            payload.put("size", size);
            payload.put("tool", TOOL_NAME);
            payload.put("createdAt", Instant.now().toString());
            var reason = emitter.emit("artifact", payload.toString(), null, context.getCurrentToolCallId());
            if (reason != null) {
                LOGGER.warn("artifact event rejected, path={}, reason={}", relativePath, reason);
            }
        } catch (RuntimeException e) {
            // the registration is already persisted; a client that misses the event sees the file on the next list
            LOGGER.warn("failed to publish artifact event, path={}, error={}", relativePath, e.getMessage());
        }
    }
}
