package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @author stephen
 */
public class EditFileTool extends ToolCall {
    public static final String TOOL_NAME = "edit_file";

    private static final long WARN_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger(EditFileTool.class);
    private static final String TOOL_DESC = """
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
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(text);
            var filePath = getStringValue(argsMap, "file_path");
            var oldString = getStringValue(argsMap, "old_string");
            var newString = getStringValue(argsMap, "new_string");
            var replaceAll = argsMap.get("replace_all") != null && (Boolean) argsMap.get("replace_all");

            var result = editFile(filePath, oldString, newString, replaceAll);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("filePath", filePath)
                .withStats("replaceAll", replaceAll);
        } catch (Exception e) {
            var error = "Failed to parse edit file arguments: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                .withDuration(System.currentTimeMillis() - startTime);
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
        var validationError = validateEditParameters(filePath, oldString, newString);
        if (validationError != null) return validationError;

        var file = new File(filePath);
        if (!file.exists()) return "Error: File does not exist: " + filePath;
        if (!file.isFile()) return "Error: Path is not a file: " + filePath;

        long fileSize = file.length();
        if (fileSize > MAX_FILE_SIZE) {
            return String.format("Error: File too large to edit (%dMB), max 100MB: %s", fileSize / 1024 / 1024, filePath);
        }
        if (fileSize > WARN_FILE_SIZE) {
            LOGGER.warn("Large file being edited: {} ({}MB)", filePath, fileSize / 1024 / 1024);
        }

        String content;
        long readTimestamp;
        String contentHash;
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            readTimestamp = file.lastModified();
            contentHash = hashContent(content);
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage(), e);
            return "Error reading file: " + e.getMessage();
        }

        var normalizedOld = normalizeLineEndings(oldString, content);
        var normalizedNew = normalizeLineEndings(newString, content);

        var matches = FuzzyMatchReplacer.findMatches(content, normalizedOld);
        if (matches.isEmpty()) {
            return "Error: old_string not found in file. Make sure the text is similar to the actual file content including structure and line breaks.";
        }

        String matchedText = matches.getFirst().matched;
        String strategy = matches.getFirst().strategyName;

        int occurrences = countOccurrences(content, matchedText);
        if (!replaceAll && occurrences > 1) {
            return String.format("Error: old_string appears %d times in the file. Either provide a larger string with more surrounding context to make it unique, or set replace_all=true to replace all occurrences.", occurrences);
        }

        String resolvedMatchedText = resolveDeleteTarget(content, matchedText, normalizedNew);
        String newContent = content.replace(resolvedMatchedText, normalizedNew);
        if (!"exact".equals(strategy)) {
            LOGGER.info("Fuzzy matched using strategy '{}' in file: {}", strategy, filePath);
        }
        LOGGER.debug("Replacing {} occurrence(s) in file: {}", occurrences, filePath);

        String concurrentError = checkFileNotModified(file, readTimestamp, contentHash);
        if (concurrentError != null) return concurrentError;

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(newContent);
            writer.flush();

            String successMsg = replaceAll
                    ? String.format("Successfully replaced %d occurrence(s) in file: %s", occurrences, filePath)
                    : String.format("Successfully replaced 1 occurrence in file: %s", filePath);

            LOGGER.debug(successMsg);
            return successMsg + "\n" + buildMiniDiff(oldString, newString);

        } catch (IOException e) {
            String error = "Error writing file: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private String buildMiniDiff(String oldString, String newString) {
        int maxLines = 8;
        var sb = new StringBuilder(256);
        sb.append("```diff\n");
        String[] oldLines = oldString.split("\n", maxLines + 1);
        String[] newLines = newString.split("\n", maxLines + 1);
        int oldLimit = Math.min(oldLines.length, maxLines);
        int newLimit = Math.min(newLines.length, maxLines);
        for (int i = 0; i < oldLimit; i++) {
            sb.append("- ").append(oldLines[i]).append('\n');
        }
        if (oldLines.length > maxLines) sb.append("- ...\n");
        for (int i = 0; i < newLimit; i++) {
            sb.append("+ ").append(newLines[i]).append('\n');
        }
        if (newLines.length > maxLines) sb.append("+ ...\n");
        sb.append("```");
        return sb.toString();
    }

    private String resolveDeleteTarget(String content, String matchedText, String newString) {
        if (!newString.isEmpty()) return matchedText;
        if (!matchedText.endsWith("\n") && content.contains(matchedText + "\n")) {
            return matchedText + "\n";
        }
        return matchedText;
    }

    private String checkFileNotModified(File file, long readTimestamp, String contentHash) {
        long currentTimestamp = file.lastModified();
        if (currentTimestamp == readTimestamp) return null;
        try {
            String currentContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (hashContent(currentContent).equals(contentHash)) return null;
            return "Error: File was modified by another process between read and write, please re-read the file and retry: " + file.getPath();
        } catch (IOException e) {
            return "Error: Cannot verify file state before writing: " + e.getMessage();
        }
    }

    private String hashContent(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    private String normalizeLineEndings(String text, String fileContent) {
        boolean fileHasCRLF = fileContent.contains("\r\n");
        boolean textHasCRLF = text.contains("\r\n");
        if (fileHasCRLF && !textHasCRLF) {
            return text.replace("\n", "\r\n");
        }
        if (!fileHasCRLF && textHasCRLF) {
            return text.replace("\r\n", "\n");
        }
        return text;
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
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
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
