package ai.core.cli.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.HashEditFileTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Observes the tool calls of a forked extraction agent and records what it wrote to the memory
 * store — knowledge pages, MEMORY.md, episodes, daily logs. Read calls are ignored on purpose:
 * the report only describes what was extracted, not what was re-read while merging.
 *
 * @author stephen
 */
final class MemoryExtractionObserver extends AbstractLifecycle {
    private static final Set<String> WRITE_TOOLS = Set.of(
            WriteFileTool.TOOL_NAME, EditFileTool.TOOL_NAME, HashEditFileTool.TOOL_NAME);
    private static final Pattern MEMORY_FILE =
            Pattern.compile("(^|/)\\.core-ai/((knowledge|episodes|daily-logs)/|memory\\.md$)");
    private static final int MAX_NOTE_CHARS = 4000;

    private static boolean isWriteTool(FunctionCall functionCall) {
        return functionCall != null && functionCall.function != null && functionCall.id != null
                && WRITE_TOOLS.contains(functionCall.function.name);
    }

    /**
     * @return the {@code .core-ai/...} path when the file belongs to the memory store, else null
     */
    static String memoryRelativePath(String filePath) {
        String normalized = filePath.replace('\\', '/');
        var matcher = MEMORY_FILE.matcher(normalized.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        // matcher.start() is the match itself; skip the leading "/" of a mid-path match
        int index = matcher.start() + (normalized.charAt(matcher.start()) == '/' ? 1 : 0);
        return normalized.substring(index);
    }

    private static String stringArgument(FunctionCall functionCall, String name) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(functionCall.function.arguments);
            var value = node == null ? null : node.get(name);
            return value != null && value.isTextual() ? value.asText() : null;
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    /** {@code write_file}/{@code edit_file} use {@code file_path}, the hash variant uses {@code path}. */
    private static String fileArgument(FunctionCall functionCall) {
        String file = stringArgument(functionCall, "file_path");
        return file != null ? file : stringArgument(functionCall, "path");
    }

    private static boolean exists(String file) {
        try {
            return Files.exists(Path.of(file));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private final Map<String, Boolean> existedBefore = new ConcurrentHashMap<>();
    private final Map<String, String> written = new LinkedHashMap<>();
    private final StringBuilder note = new StringBuilder();
    private volatile int cursor = -1;

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        if (!isWriteTool(functionCall)) {
            return;
        }
        String file = fileArgument(functionCall);
        if (file == null || memoryRelativePath(file) == null) {
            return;
        }
        existedBefore.put(functionCall.id, exists(file));
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult result) {
        if (functionCall == null || functionCall.function == null) {
            return;
        }
        if (result == null || result.isFailed()) {
            return;
        }
        String name = functionCall.function.name;
        if (WRITE_TOOLS.contains(name)) {
            recordWrite(functionCall);
        } else if (KnowledgeLogTool.ADD_TOOL_NAME.equals(name)) {
            recordNote(stringArgument(functionCall, "log_info"));
        } else if (ExtractionCursorTool.ADVANCE_TOOL_NAME.equals(name)) {
            recordCursor(result);
        }
    }

    List<String> added() {
        return written.entrySet().stream()
                .filter(entry -> "added".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    List<String> updated() {
        return written.entrySet().stream()
                .filter(entry -> "updated".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    String note() {
        return note.isEmpty() ? null : note.toString();
    }

    int cursor() {
        return cursor;
    }

    private void recordWrite(FunctionCall functionCall) {
        String file = fileArgument(functionCall);
        if (file == null) {
            return;
        }
        String path = memoryRelativePath(file);
        if (path == null) {
            return;
        }
        boolean existed = Boolean.TRUE.equals(existedBefore.remove(functionCall.id));
        // a file created and then edited in the same run stays "added"
        written.merge(path, existed ? "updated" : "added",
                (first, second) -> "added".equals(first) ? first : second);
    }

    private void recordNote(String logInfo) {
        if (logInfo == null || logInfo.isBlank() || note.length() >= MAX_NOTE_CHARS) {
            return;
        }
        if (!note.isEmpty()) {
            note.append("\n\n");
        }
        String text = logInfo.strip();
        int room = Math.max(0, MAX_NOTE_CHARS - note.length());
        note.append(text.length() <= room ? text : text.substring(0, room));
    }

    private void recordCursor(ToolCallResult result) {
        Map<String, Object> stats = result.getStats();
        Object value = stats == null ? null : stats.get("cursor");
        if (value instanceof Number number) {
            cursor = number.intValue();
        }
    }
}
