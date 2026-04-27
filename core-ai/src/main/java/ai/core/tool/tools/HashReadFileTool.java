package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Reads a file and annotates each line with a hashline reference.
 * Output format per line: {@code LINENUM#HASH:content} — e.g. {@code 5#VK:void foo() {}
 *
 * Use hash_edit_file after reading to make precise, conflict-safe edits.
 *
 * @author lim chen
 */
public class HashReadFileTool extends ToolCall {
    public static final String TOOL_NAME = "hash_read_file";

    private static final Logger LOGGER = LoggerFactory.getLogger(HashReadFileTool.class);
    private static final int DEFAULT_LINE_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;

    private static final String TOOL_DESC = """
            Reads a file and returns each line prefixed with a hashline anchor: LINE#ID:content
            (e.g. 41#ZZ:def alpha():).

            These anchors uniquely identify each line by position and content.
            Use hash_edit_file (not edit_file) after reading with this tool.
            After any successful edit, re-read the file before editing again.

            - file_path must be an absolute path
            - Reads up to 2000 lines by default; use offset and limit for large files
            - Lines longer than 2000 characters will be truncated
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var filePath = (String) argsMap.get("file_path");
            var offset = argsMap.get("offset") != null ? ((Number) argsMap.get("offset")).intValue() : null;
            var limit = argsMap.get("limit") != null ? ((Number) argsMap.get("limit")).intValue() : null;

            var result = readFile(filePath, offset, limit);
            return ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("filePath", filePath);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to parse hash_read_file arguments: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String readFile(String filePath, Integer offset, Integer limit) {
        if (Strings.isBlank(filePath)) return "Error: file_path parameter is required";

        var file = new File(filePath);
        if (!file.exists()) return "Error: File does not exist: " + filePath;
        if (!file.isFile()) return "Error: Path is not a file: " + filePath;

        int startLine = (offset != null && offset > 0) ? offset : 1;
        int maxLines = (limit != null && limit > 0) ? limit : DEFAULT_LINE_LIMIT;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            var result = new StringBuilder();
            int currentLine = 1;
            int linesRead = 0;

            while (currentLine < startLine) {
                if (reader.readLine() == null) break;
                currentLine++;
            }

            while (linesRead < maxLines) {
                String line = reader.readLine();
                if (line == null) break;

                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "... [line truncated]";
                }

                String hash = HashLine.computeHash(line, currentLine);
                result.append(HashLine.formatLine(currentLine, hash, line)).append('\n');
                currentLine++;
                linesRead++;
            }

            if (result.isEmpty()) {
                return startLine == 1 ? "Warning: File exists but is empty"
                        : "Warning: File has fewer lines than the specified offset: " + startLine;
            }

            LOGGER.debug("Hash-read {} lines from: {}", linesRead, filePath);
            return result.toString();
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage(), e);
            return "Error reading file: " + e.getMessage();
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, HashReadFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public HashReadFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "file_path", "Absolute path of the file to read").required(),
                    ToolCallParameters.ParamSpec.of(Integer.class, "offset", "Line number to start reading from"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Number of lines to read")
            ));
            var tool = new HashReadFileTool();
            build(tool);
            return tool;
        }
    }
}
