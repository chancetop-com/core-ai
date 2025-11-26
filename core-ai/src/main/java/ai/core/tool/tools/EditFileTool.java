package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
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
public class EditFileTool extends ToolCall {
    private static final Logger LOGGER = LoggerFactory.getLogger(EditFileTool.class);

    private static final String EDIT_FILE_TOOL_DESC = """
            Performs exact string replacements in files.


            Usage:

            - You must use your `Read` tool at least once in the conversation before
            editing. This tool will error if you attempt an edit without reading the file.

            - When editing text from Read tool output, ensure you preserve the exact
            indentation (tabs/spaces) as it appears AFTER the line number prefix. The line
            number prefix format is: spaces + line number + tab. Everything after that tab
            is the actual file content to match. Never include any part of the line number
            prefix in the old_string or new_string.

            - ALWAYS prefer editing existing files in the codebase. NEVER write new files
            unless explicitly required.

            - Only use emojis if the user explicitly requests it. Avoid adding emojis to
            files unless asked.

            - The edit will FAIL if `old_string` is not unique in the file. Either provide
            a larger string with more surrounding context to make it unique or use
            `replace_all` to change every instance of `old_string`.

            - Use `replace_all` for replacing and renaming strings across the file. This
            parameter is useful if you want to rename a variable for instance.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String call(String text) {
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var filePath = (String) argsMap.get("file_path");
            var oldString = (String) argsMap.get("old_string");
            var newString = (String) argsMap.get("new_string");
            var replaceAll = argsMap.get("replace_all") != null && (Boolean) argsMap.get("replace_all");

            return editFile(filePath, oldString, newString, replaceAll);
        } catch (Exception e) {
            var error = "Failed to parse edit file arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private String validateEditParameters(String filePath, String oldString, String newString) {
        // Validate file path
        if (Strings.isBlank(filePath)) {
            return "Error: file_path parameter is required";
        }

        // Validate old_string
        if (oldString == null) {
            return "Error: old_string parameter is required";
        }

        // Validate new_string
        if (newString == null) {
            return "Error: new_string parameter is required";
        }

        // Check if old_string and new_string are the same
        if (oldString.equals(newString)) {
            return "Error: old_string and new_string must be different";
        }

        return null;  // No validation errors
    }

    private String editFile(String filePath, String oldString, String newString, Boolean replaceAll) {
        // Validate parameters first
        var validationError = validateEditParameters(filePath, oldString, newString);
        if (validationError != null) {
            return validationError;
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

        // Read file content
        String content;
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            String error = "Error reading file: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }

        // Check if old_string exists in file
        if (!content.contains(oldString)) {
            return "Error: old_string not found in file. Make sure to copy the exact text including whitespace and line breaks.";
        }

        // Count occurrences
        int occurrences = countOccurrences(content, oldString);
        LOGGER.info("Found {} occurrence(s) of old_string in file: {}", occurrences, filePath);

        // If not replace_all and more than one occurrence, fail
        if (!replaceAll && occurrences > 1) {
            return String.format("Error: old_string appears %d times in the file. Either provide a larger string with more surrounding context to make it unique, or set replace_all=true to replace all occurrences.", occurrences);
        }

        // Perform replacement
        String newContent;
        if (replaceAll) {
            newContent = content.replace(oldString, newString);
            LOGGER.info("Replacing all {} occurrence(s) in file", occurrences);
        } else {
            newContent = content.replace(oldString, newString);
            LOGGER.info("Replacing single occurrence in file");
        }

        // Write back to file
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(newContent);
            writer.flush();

            String successMsg = replaceAll
                    ? String.format("Successfully replaced %d occurrence(s) in file: %s", occurrences, filePath)
                    : String.format("Successfully replaced 1 occurrence in file: %s", filePath);

            LOGGER.info(successMsg);
            return successMsg;

        } catch (IOException e) {
            String error = "Error writing file: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private int countOccurrences(String text, String substring) {
        var count = 0;
        var index = 0;
        while (true) {
            index = text.indexOf(substring, index);
            if (index == -1) break;
            count++;
            index += substring.length();
        }
        return count;
    }

    public static class Builder extends ToolCall.Builder<Builder, EditFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public EditFileTool build() {
            this.name("edit_file");
            this.description(EDIT_FILE_TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "file_path", "Absolute path to the file to modify").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "old_string", "The text to replace").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "new_string", "The text to replace it with (must be different from old_string)").required(),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "replace_all", "Replace all occurrences of old_string (default false)")
            ));
            var tool = new EditFileTool();
            build(tool);
            return tool;
        }
    }
}
