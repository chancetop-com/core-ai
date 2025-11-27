package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * @author stephen
 */
public class WriteFileTool extends ToolCall {
    public static final String TOOL_NAME = "write_file";
    private static final Logger LOGGER = LoggerFactory.getLogger(WriteFileTool.class);

    private static final String TOOL_DESC = """
            Writes a file to the local filesystem.


            Usage:

            - This tool will overwrite the existing file if there is one at the provided
            path.

            - If this is an existing file, you MUST use the Read tool first to read the
            file's contents. This tool will fail if you did not read the file first.

            - ALWAYS prefer editing existing files in the codebase. NEVER write new files
            unless explicitly required.

            - NEVER proactively create documentation files (*.md) or README files. Only
            create documentation files if explicitly requested by the User.

            - Only use emojis if the user explicitly requests it. Avoid writing emojis to
            files unless asked.
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
            var content = (String) argsMap.get("content");

            var result = writeFile(filePath, content);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("filePath", filePath)
                .withStats("contentLength", content != null ? content.length() : 0);
        } catch (Exception e) {
            var error = "Failed to parse write file arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String writeFile(String filePath, String content) {
        // Validate file path
        if (Strings.isBlank(filePath)) {
            return "Error: file_path parameter is required";
        }

        // Validate content
        if (content == null) {
            return "Error: content parameter is required";
        }

        var file = new File(filePath);

        // Create parent directories if they don't exist
        var parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            LOGGER.info("Creating parent directories: {}", parentDir.getAbsolutePath());
            if (!parentDir.mkdirs()) {
                String error = "Error: Failed to create parent directories: " + parentDir.getAbsolutePath();
                LOGGER.error(error);
                return error;
            }
        }

        // Check if file exists (for logging purposes)
        boolean fileExists = file.exists();
        if (fileExists) {
            LOGGER.info("Overwriting existing file: {}", filePath);
        } else {
            LOGGER.info("Creating new file: {}", filePath);
        }

        // Write content to file
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();

            String successMsg = fileExists
                    ? "Successfully overwrote file: " + filePath + " (" + content.length() + " characters)"
                    : "Successfully created file: " + filePath + " (" + content.length() + " characters)";

            LOGGER.info(successMsg);
            return successMsg;

        } catch (IOException e) {
            String error = "Error writing file: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, WriteFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public WriteFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "file_path", "Absolute path to the file to write (must be absolute, not relative)").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "content", "The content to write to the file").required()
            ));
            var tool = new WriteFileTool();
            build(tool);
            return tool;
        }
    }
}
