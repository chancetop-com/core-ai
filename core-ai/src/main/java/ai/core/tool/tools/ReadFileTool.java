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
 * @author stephen
 */
public class ReadFileTool extends ToolCall {
    public static final String TOOL_NAME = "read_file";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadFileTool.class);
    private static final int DEFAULT_LINE_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;

    private static final String TOOL_DESC = """
            Reads a file from the local filesystem. You can access any file directly by
            using this tool.

            Assume this tool is able to read all files on the machine. If the User
            provides a path to a file assume that path is valid. It is okay to read a file
            that does not exist; an error will be returned.


            Usage:

            - The file_path parameter must be an absolute path, not a relative path

            - By default, it reads up to 2000 lines starting from the beginning of the
            file

            - You can optionally specify a line offset and limit (especially handy for
            long files), but it's recommended to read the whole file by not providing
            these parameters

            - Any lines longer than 2000 characters will be truncated

            - Results are returned using cat -n format, with line numbers starting at 1

            - This tool allows agent to read images (eg PNG, JPG, etc). When reading
            an image file the contents are presented visually.

            - This tool can read PDF files (.pdf). PDFs are processed page by page,
            extracting both text and visual content for analysis.

            - For Jupyter notebooks (.ipynb files), use the NotebookRead instead

            - You have the capability to call multiple tools in a single response. It is
            always better to speculatively read multiple files as a batch that are
            potentially useful.

            - You will regularly be asked to read screenshots. If the user provides a path
            to a screenshot ALWAYS use this tool to view the file at the path. This tool
            will work with all temporary file paths like
            /var/folders/123/abc/T/TemporaryItems/NSIRD_ScreenCaptureUI_ZfB1tD/Screenshot.png

            - If you read a file that exists but has empty contents you will receive a
            system reminder warning in place of file contents.
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
            var error = "Failed to parse read file arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String readFile(String filePath, Integer offset, Integer limit) {
        // Validate file path
        if (Strings.isBlank(filePath)) {
            return "Error: file_path parameter is required";
        }

        var file = new File(filePath);

        // Check if file exists
        if (!file.exists()) {
            return "Error: File does not exist: " + filePath;
        }

        // Check if it's a file (not a directory)
        if (!file.isFile()) {
            return "Error: Path is not a file: " + filePath;
        }

        // Set default values for offset and limit
        var startLine = (offset != null && offset > 0) ? offset : 1;
        var maxLines = (limit != null && limit > 0) ? limit : DEFAULT_LINE_LIMIT;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            var result = new StringBuilder();
            var currentLine = 1;
            var linesRead = 0;

            LOGGER.info("Reading file: {}, startLine: {}, maxLines: {}", filePath, startLine, maxLines);

            // Skip lines until we reach the offset
            while (currentLine < startLine) {
                String skipLine = reader.readLine();
                if (skipLine == null) break;
                currentLine++;
            }

            // Read lines from offset up to limit
            while (linesRead < maxLines) {
                String line = reader.readLine();
                if (line == null) break;

                // Truncate long lines
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "... [line truncated]";
                }

                // Format as "line_number → content" (cat -n format with arrow)
                result.append(String.format("%6d→%s%n", currentLine, line));
                currentLine++;
                linesRead++;
            }

            // Check if file is empty
            if (result.isEmpty()) {
                String warning = startLine == 1 ? "Warning: File exists but is empty"
                        : "Warning: File has fewer lines than the specified offset: " + startLine;
                LOGGER.warn(warning);
                return warning;
            }

            LOGGER.info("Successfully read {} lines from file", linesRead);
            return result.toString();

        } catch (IOException e) {
            String error = "Error reading file: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ReadFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public ReadFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "file_path", "Absolute path of the file to read").required(),
                    ToolCallParameters.ParamSpec.of(Integer.class, "offset", "The line number to start reading from. Only provide if the file is too large to read at once"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "limit", "The number of lines to read. Only provide if the file is too large to read at once.")
            ));
            var tool = new ReadFileTool();
            build(tool);
            return tool;
        }
    }
}
