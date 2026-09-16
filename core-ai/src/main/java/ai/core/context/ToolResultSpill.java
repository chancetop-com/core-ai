package ai.core.context;

import ai.core.document.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * @author xander
 */
final class ToolResultSpill {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolResultSpill.class);
    private static final int HEAD_TOKENS = 500;
    private static final int TAIL_TOKENS = 500;
    private static final String TEMP_DIR_NAME = "core-ai";

    static boolean exceedsLimit(String result, int maxToolResultTokens) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        return Tokenizer.tokenCount(result) > maxToolResultTokens;
    }

    static String compress(String toolName, String result, String sessionId, int maxToolResultTokens) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        int tokenCount = Tokenizer.tokenCount(result);
        if (tokenCount <= maxToolResultTokens) {
            return result;
        }

        try {
            Path filePath = writeToFile(toolName, result, sessionId);
            LOGGER.debug("Long tool result from {} saved to file: {} ({} tokens, max: {})",
                toolName, filePath, tokenCount, maxToolResultTokens);
            return buildSummary(toolName, result, tokenCount, filePath, maxToolResultTokens);
        } catch (IOException e) {
            LOGGER.error("Failed to write long tool result to file, keeping original", e);
            return result;
        }
    }

    private static Path writeToFile(String toolName, String content, String sessionId) throws IOException {
        String sid = sessionId != null ? sessionId : "default";
        Path sessionDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME).resolve(sid);
        if (!Files.exists(sessionDir)) {
            Files.createDirectories(sessionDir);
        }

        String fileName = String.format("%s_%d.txt", sanitizeFileName(toolName), Instant.now().toEpochMilli());
        Path filePath = sessionDir.resolve(fileName);
        Files.writeString(filePath, content);
        return filePath;
    }

    private static String buildSummary(String toolName, String content, int tokenCount, Path filePath, int maxToolResultTokens) {
        String headContent = truncateToTokens(content);
        String tailContent = extractTailTokens(content);

        return String.format("[Tool result truncated - full content saved to file]%n"
            + "Tool: %s%n"
            + "File: %s%n"
            + "Total: %d tokens (exceeds %d token limit)%n%n"
            + "=== HEAD (first %d tokens) ===%n"
            + "%s%n%n"
            + "=== ... truncated ... ===%n%n"
            + "=== TAIL (last %d tokens) ===%n"
            + "%s%n%n"
            + "[WARNING: This is a large file. Do NOT read the full file directly as it will be truncated again."
            + " Use file related tools to read specific parts of the file as needed.],%n"
            + "File path: %s",
            toolName, filePath, tokenCount, maxToolResultTokens,
            HEAD_TOKENS, headContent,
            TAIL_TOKENS, tailContent,
            filePath);
    }

    private static String truncateToTokens(String content) {
        List<Integer> tokens = Tokenizer.encode(content);
        if (tokens.size() <= HEAD_TOKENS) {
            return content;
        }
        return Tokenizer.decode(tokens.subList(0, HEAD_TOKENS));
    }

    private static String extractTailTokens(String content) {
        List<Integer> tokens = Tokenizer.encode(content);
        if (tokens.size() <= TAIL_TOKENS) {
            return content;
        }
        int startIndex = tokens.size() - TAIL_TOKENS;
        return Tokenizer.decode(tokens.subList(startIndex, tokens.size()));
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private ToolResultSpill() {
    }
}
