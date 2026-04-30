package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits files using hashline anchors (LINE#ID) produced by hash_read_file.
 *
 * <p>All edits in a call are validated before any write — if any anchor hash
 * does not match the current file content the entire operation is rejected.
 *
 * <p>Schema mirrors oh-my-pi hashline edit mode: each edit entry carries its
 * own {@code path} and a {@code loc} that selects the insertion/replacement
 * point, plus {@code content} for the new lines.
 *
 * @author lim chen
 */
public class HashEditFileTool extends ToolCall {
    public static final String TOOL_NAME = "hash_edit_file";

    private static final Logger LOGGER = LoggerFactory.getLogger(HashEditFileTool.class);
    private static final int MISMATCH_CONTEXT = 2;

    private static final String TOOL_DESC = """
            Applies precise file edits using LINE#ID anchors from hash_read_file output.
            
            Read the file first. Copy anchors exactly from the latest hash_read_file output.
            After any successful edit, re-read before editing that file again.
            
            **Top level**
            - `edits` — array of edit entries
            
            **Edit entry**: `{ path, loc, content }` or `{ path, delete: true }` or `{ path, move: "new/path" }`
            - `path` — file path
            - `loc` — where to apply the edit (see below)
            - `content` — replacement/inserted lines (array of strings preferred, null to delete)
            - `delete` — delete the file
            - `move` — move/rename the file
            
            **`loc` values**
            - `"append"` / `"prepend"` — insert at end/start of file
            - `{ "append": "N#ID" }` / `{ "prepend": "N#ID" }` — insert after/before anchored line
            - `{ "range": { "pos": "N#ID", "end": "N#ID" } }` — replace inclusive range pos..end with new content (set pos == end for single-line replace)
            
            If any anchor hash does not match current file content, ALL edits are rejected.
            Re-read with hash_read_file and retry using the corrected anchors shown in the error.
            
            - Make the minimum exact edit. Do not rewrite nearby code unless the range requires it.
            - Copy anchors exactly as N#ID from the latest hash_read_file output.
            - `content` must be literal file content with matching indentation.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    private static Op buildRangeOp(Map<?, ?> locMap, String[] content, List<String> lines) {
        var range = (Map<String, Object>) locMap.get("range");
        var pos = validateRef(HashLine.parseRef(range.get("pos").toString()), lines);
        var end = validateRef(HashLine.parseRef(range.get("end").toString()), lines);
        if (pos.lineNumber() > end.lineNumber()) {
            throw new IllegalArgumentException(
                    "range pos (" + pos.lineNumber() + ") must be <= end (" + end.lineNumber() + ")");
        }
        return new Op(OpKind.REPLACE_RANGE, pos.lineNumber(), pos.hash(),
                end.lineNumber(), end.hash(), content, pos.lineNumber(), 0);
    }

    @SuppressWarnings("unchecked")
    static List<Op> parseOps(List<Map<String, Object>> edits, List<String> lines) {
        var ops = new ArrayList<Op>();
        for (var edit : edits) {
            var locRaw = edit.get("loc");
            var content = HashLine.parseContent(edit.get("content"));

            if (locRaw == null) continue; // no loc = no-op content edit

            if (locRaw instanceof String locStr) {
                switch (locStr) {
                    case "append" ->
                        ops.add(new Op(OpKind.APPEND_FILE, 0, null, 0, null, content, lines.size() + 1, 1));
                    case "prepend" ->
                        ops.add(new Op(OpKind.PREPEND_FILE, 0, null, 0, null, content, 0, 2));
                    default -> throw new IllegalArgumentException("Unknown loc string: " + locStr);
                }
            } else if (locRaw instanceof Map<?, ?> locMap) {
                if (locMap.containsKey("range")) {
                    ops.add(buildRangeOp(locMap, content, lines));
                } else if (locMap.containsKey("append")) {
                    var ref = validateRef(HashLine.parseRef((String) locMap.get("append")), lines);
                    ops.add(new Op(OpKind.APPEND_AT, ref.lineNumber(), ref.hash(), 0, null, content, ref.lineNumber(), 1));
                } else if (locMap.containsKey("prepend")) {
                    var ref = validateRef(HashLine.parseRef((String) locMap.get("prepend")), lines);
                    ops.add(new Op(OpKind.PREPEND_AT, ref.lineNumber(), ref.hash(), 0, null, content, ref.lineNumber(), 2));
                } else {
                    throw new IllegalArgumentException("Unknown loc map keys: " + locMap.keySet());
                }
            }
        }
        return ops;
    }

    static HashLine.LineRef validateRef(HashLine.LineRef ref, List<String> lines) {
        if (ref.lineNumber() < 1 || ref.lineNumber() > lines.size()) {
            throw new IllegalArgumentException(
                    "Line " + ref.lineNumber() + " does not exist (file has " + lines.size() + " lines)");
        }
        String actual = HashLine.computeHash(lines.get(ref.lineNumber() - 1), ref.lineNumber());
        if (!actual.equals(ref.hash())) {
            throw new MismatchException(buildMismatchMessage(
                    List.of(new Mismatch(ref.lineNumber(), ref.hash(), actual)), lines));
        }
        return ref;
    }

    static void applyOp(Op op, List<String> lines) {
        switch (op.kind) {
            case REPLACE_RANGE -> {
                int from = op.posLine - 1;
                int to = op.endLine - 1;
                lines.subList(from, to + 1).clear();
                for (int i = op.content.length - 1; i >= 0; i--) lines.add(from, op.content[i]);
            }
            case APPEND_AT -> {
                int after = op.posLine; // insert after posLine (0-indexed = posLine)
                for (int i = op.content.length - 1; i >= 0; i--) lines.add(after, op.content[i]);
            }
            case PREPEND_AT -> {
                int before = op.posLine - 1;
                for (int i = op.content.length - 1; i >= 0; i--) lines.add(before, op.content[i]);
            }
            case APPEND_FILE -> {
                lines.addAll(Arrays.asList(op.content));
            }
            case PREPEND_FILE -> {
                for (int i = op.content.length - 1; i >= 0; i--) lines.add(0, op.content[i]);
            }
            default -> throw new IllegalArgumentException("Unknown OpKind: " + op.kind);
        }
    }

    static String buildMismatchMessage(List<Mismatch> mismatches, List<String> lines) {
        var mismatchLines = new java.util.HashSet<Integer>();
        for (var m : mismatches) mismatchLines.add(m.line);

        var displayLines = new java.util.TreeSet<Integer>();
        for (var m : mismatches) {
            for (int i = Math.max(1, m.line - MISMATCH_CONTEXT);
                 i <= Math.min(lines.size(), m.line + MISMATCH_CONTEXT); i++) {
                displayLines.add(i);
            }
        }

        var sb = new StringBuilder(256);
        sb.append("Edit rejected: ").append(mismatches.size()).append(" line")
                .append(mismatches.size() > 1 ? "s have" : " has")
                .append(" changed since the last read. The edit was NOT applied."
                        + " Use the updated LINE#ID references shown below (>>> marks changed lines) and retry the edit.\n\n");

        int prev = -1;
        for (int lineNum : displayLines) {
            if (prev != -1 && lineNum > prev + 1) sb.append("    ...\n");
            prev = lineNum;
            String lineText = lines.get(lineNum - 1);
            String hash = HashLine.computeHash(lineText, lineNum);
            if (mismatchLines.contains(lineNum)) {
                sb.append(">>> ").append(lineNum).append('#').append(hash).append(':').append(lineText).append('\n');
            } else {
                sb.append("    ").append(lineNum).append('#').append(hash).append(':').append(lineText).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Simulates edits on raw file content in memory and returns the resulting content.
     * Does not write to disk. Used by permission systems to generate diff previews.
     *
     * @throws MismatchException if any anchor hash does not match current content
     */
    public static String simulateEdits(String rawContent, List<Map<String, Object>> edits,
                                       boolean hasCRLF, boolean hasTrailingNewline) {
        String[] lineArray = rawContent.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> lines;
        if (hasTrailingNewline && lineArray.length > 0 && lineArray[lineArray.length - 1].isEmpty()) {
            lines = new ArrayList<>(Arrays.asList(Arrays.copyOf(lineArray, lineArray.length - 1)));
        } else {
            lines = new ArrayList<>(Arrays.asList(lineArray));
        }

        List<Op> ops = parseOps(edits, lines);
        ops.sort(Comparator.comparingInt((Op o) -> o.sortLine).reversed()
                .thenComparingInt(o -> o.precedence));
        for (var op : ops) applyOp(op, lines);

        String lineEnding = hasCRLF ? "\r\n" : "\n";
        String result = String.join(lineEnding, lines);
        if (hasTrailingNewline) result += lineEnding;
        return result;
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(text);
            var rawEdits = argsMap.get("edits");

            if (rawEdits == null) {
                return ToolCallResult.failed("Error: edits must be a non-empty array")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            var edits = JsonUtil.fromJson(new TypeReference<List<Map<String, Object>>>() {
            }, String.valueOf(rawEdits));

            var result = applyEdits(edits);
            return result.startsWith("Error:") || result.startsWith("Edit rejected:")
                    ? ToolCallResult.failed(result).withDuration(System.currentTimeMillis() - startTime)
                    : ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("editCount", edits.size());
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to parse hash_edit_file arguments: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String applyEdits(List<Map<String, Object>> rawEdits) {
        // Group edits by path so each file is read/written once
        var byPath = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var edit : rawEdits) {
            var path = (String) edit.get("path");
            if (Strings.isBlank(path)) return "Error: each edit must have a 'path' field";
            byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(edit);
        }

        var results = new ArrayList<String>();
        for (var entry : byPath.entrySet()) {
            var r = applyEditsToFile(entry.getKey(), entry.getValue());
            if (r.startsWith("Error:") || r.startsWith("Edit rejected:")) return r;
            results.add(r);
        }
        return String.join("\n", results);
    }

    private String applyEditsToFile(String path, List<Map<String, Object>> edits) {
        // Handle delete
        for (var edit : edits) {
            if (Boolean.TRUE.equals(edit.get("delete"))) {
                var file = new File(path);
                if (!file.exists()) return "Error: File does not exist: " + path;
                if (!file.delete()) return "Error: Failed to delete file: " + path;
                LOGGER.debug("Deleted file: {}", path);
                return "Deleted: " + path;
            }
        }

        // Handle move/rename
        for (var edit : edits) {
            var moveTo = (String) edit.get("move");
            if (!Strings.isBlank(moveTo)) {
                var src = new File(path);
                var dst = new File(moveTo);
                if (!src.exists()) return "Error: File does not exist: " + path;
                dst.getParentFile().mkdirs();
                if (!src.renameTo(dst)) return "Error: Failed to move " + path + " to " + moveTo;
                LOGGER.debug("Moved {} → {}", path, moveTo);
                return "Moved: " + path + " → " + moveTo;
            }
        }

        // Content edits
        var file = new File(path);
        if (!file.exists()) return "Error: File does not exist: " + path;
        if (!file.isFile()) return "Error: Path is not a file: " + path;

        String rawContent;
        try {
            rawContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        boolean hasCRLF = rawContent.contains("\r\n");
        boolean hasTrailingNewline = rawContent.endsWith("\n");

        String result;
        try {
            result = simulateEdits(rawContent, edits, hasCRLF, hasTrailingNewline);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (MismatchException e) {
            return e.getMessage();
        }

        try {
            Files.writeString(file.toPath(), result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }

        LOGGER.debug("Applied edit(s) to: {}", path);
        return "Successfully applied edit(s) to: " + path;
    }

    // ── Internal types ────────────────────────────────────────────────────────

    enum OpKind { REPLACE_RANGE, APPEND_AT, PREPEND_AT, APPEND_FILE, PREPEND_FILE }

    record Op(OpKind kind, int posLine, String posHash, int endLine, String endHash,
              String[] content, int sortLine, int precedence) {
    }

    private record Mismatch(int line, String expected, String actual) {
    }

    static class MismatchException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MismatchException(String message) {
            super(message);
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static class Builder extends ToolCall.Builder<Builder, HashEditFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public HashEditFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);

            var editItems = List.of(
                    ToolCallParameter.builder()
                            .name("path").description("File path")
                            .classType(String.class).required(true).build(),
                    ToolCallParameter.builder()
                            .name("loc")
                            .description("Insert location. One of: \"append\", \"prepend\", "
                                    + "{\"append\":\"N#ID\"}, {\"prepend\":\"N#ID\"}, "
                                    + "or {\"range\":{\"pos\":\"N#ID\",\"end\":\"N#ID\"}}")
                            .classType(String.class).required(false).build(),
                    ToolCallParameter.builder()
                            .name("content")
                            .description("Replacement/inserted lines. Array of strings preferred; null to delete.")
                            .classType(String.class).required(false).build(),
                    ToolCallParameter.builder()
                            .name("delete").description("Delete the file")
                            .classType(Boolean.class).required(false).build(),
                    ToolCallParameter.builder()
                            .name("move").description("Move/rename the file to this path")
                            .classType(String.class).required(false).build()
            );

            this.parameters(List.of(
                    ToolCallParameter.builder()
                            .name("edits")
                            .description("Array of edit entries applied atomically per file.")
                            .type(ToolCallParameterType.LIST).required(true)
                            .items(editItems).build()
            ));

            var tool = new HashEditFileTool();
            build(tool);
            return tool;
        }
    }
}
