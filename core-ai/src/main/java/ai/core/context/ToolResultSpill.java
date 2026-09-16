package ai.core.context;

import ai.core.document.Tokenizer;
import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author xander
 */
final class ToolResultSpill {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolResultSpill.class);
    private static final int HEAD_TOKENS = 500;
    private static final int TAIL_TOKENS = 500;
    private static final String TEMP_DIR_NAME = "core-ai";
    private static final String SANDBOX_DIR = "/tmp/" + TEMP_DIR_NAME;
    private static final Duration RETENTION = Duration.ofDays(7);

    static boolean exceedsLimit(String result, int maxToolResultTokens) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        return Tokenizer.tokenCount(result) > maxToolResultTokens;
    }

    static String compress(String toolName, String result, String sessionId, int maxToolResultTokens) {
        return compress(toolName, result, sessionId, maxToolResultTokens, null);
    }

    /**
     * The spilled file has to live where the agent can read it back: in the sandbox filesystem when the
     * tool ran there, on the local filesystem otherwise.
     */
    static String compress(String toolName, String result, String sessionId, int maxToolResultTokens, Sandbox sandbox) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        int tokenCount = Tokenizer.tokenCount(result);
        if (tokenCount <= maxToolResultTokens) {
            return result;
        }

        String storedPath = store(toolName, result, sessionId, sandbox);
        LOGGER.debug("Long tool result from {} ({} tokens, max: {}) truncated, stored at {}",
            toolName, tokenCount, maxToolResultTokens, storedPath);
        return buildSummary(toolName, result, tokenCount, storedPath, maxToolResultTokens);
    }

    private static String store(String toolName, String content, String sessionId, Sandbox sandbox) {
        String fileName = String.format("%s_%d.txt", sanitizeFileName(toolName), Instant.now().toEpochMilli());
        if (sandbox != null) {
            return uploadToSandbox(sandbox, content, fileName, sessionId);
        }
        return writeToFile(content, fileName, sessionId);
    }

    private static String uploadToSandbox(Sandbox sandbox, String content, String fileName, String sessionId) {
        String path = SANDBOX_DIR + "/" + sessionName(sessionId) + "/" + fileName;
        try {
            sandbox.uploadFile(path, content.getBytes(StandardCharsets.UTF_8));
            return path;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to upload truncated tool result to the sandbox, keeping the truncated summary only", e);
            return null;
        }
    }

    private static String writeToFile(String content, String fileName, String sessionId) {
        Path sessionDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME).resolve(sessionName(sessionId));
        try {
            Files.createDirectories(sessionDir);
            Path filePath = sessionDir.resolve(fileName);
            Files.writeString(filePath, content);
            purgeExpired();
            return filePath.toString();
        } catch (IOException e) {
            LOGGER.warn("Failed to write truncated tool result to file, keeping the truncated summary only", e);
            return null;
        }
    }

    private static String buildSummary(String toolName, String content, int tokenCount, String storedPath, int maxToolResultTokens) {
        String headContent = truncateToTokens(content);
        String tailContent = extractTailTokens(content);

        return String.format("[Tool result truncated]%n"
            + "Tool: %s%n"
            + "%s"
            + "Total: %d tokens (exceeds %d token limit)%n%n"
            + "=== HEAD (first %d tokens) ===%n"
            + "%s%n%n"
            + "=== ... truncated ... ===%n%n"
            + "=== TAIL (last %d tokens) ===%n"
            + "%s%n%n"
            + "%s",
            toolName,
            storedPath != null ? String.format("File: %s%n", storedPath) : "",
            tokenCount, maxToolResultTokens,
            HEAD_TOKENS, headContent,
            TAIL_TOKENS, tailContent,
            readBackHint(storedPath));
    }

    private static String readBackHint(String storedPath) {
        if (storedPath == null) {
            return "[WARNING: the dropped middle of this result is gone. Re-run the tool with a narrower"
                + " query, or ask it to write to a file, when you need that content.]";
        }
        return String.format("[WARNING: This is a large result. Do NOT read the full file directly as it will be"
            + " truncated again. Use file related tools to read specific parts of the file as needed.]%n"
            + "File path: %s", storedPath);
    }

    /**
     * Spilled results are only meaningful while the conversation that produced them lives on; older
     * files are swept so a long-lived machine does not accumulate them forever.
     */
    private static void purgeExpired() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(RETENTION);
        try (Stream<Path> sessions = Files.list(root)) {
            sessions.filter(Files::isDirectory).forEach(dir -> purgeSessionDir(dir, cutoff));
        } catch (IOException e) {
            LOGGER.debug("Failed to sweep spilled tool results under {}", root, e);
        }
    }

    private static void purgeSessionDir(Path dir, Instant cutoff) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> isExpired(path, cutoff)).forEach(ToolResultSpill::deleteQuietly);
        } catch (IOException e) {
            LOGGER.debug("Failed to sweep spilled tool results under {}", dir, e);
            return;
        }
        deleteIfEmpty(dir);
    }

    private static void deleteIfEmpty(Path dir) {
        try {
            Files.delete(dir);
        } catch (DirectoryNotEmptyException e) {
            LOGGER.debug("Spilled tool results are still in use under {}", dir);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete {}", dir, e);
        }
    }

    private static boolean isExpired(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            LOGGER.debug("Failed to read the timestamp of {}", path, e);
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete {}", path, e);
        }
    }

    private static String sessionName(String sessionId) {
        return sessionId != null ? sessionId : "default";
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
