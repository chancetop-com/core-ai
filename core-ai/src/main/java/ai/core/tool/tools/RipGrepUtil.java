package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class RipGrepUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(RipGrepUtil.class);

    static ProcessResult executeProcess(Process process, int maxLines, long timeoutMs) throws InterruptedException {
        var output = new StringBuilder();
        var readerThread = getReaderThread(process, maxLines, output);

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            readerThread.interrupt();
            throw new RipGrepTimeoutException("Ripgrep process timed out after " + timeoutMs + "ms");
        }

        readerThread.join(5000);
        return new ProcessResult(process.exitValue(), output.toString());
    }

    @NotNull
    private static Thread getReaderThread(Process process, int maxLines, StringBuilder output) {
        var readerThread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                int count = 0;
                String line;
                while (true) {
                    line = reader.readLine();
                    if (line == null || count >= maxLines) break;
                    output.append(line).append('\n');
                    count++;
                }
            } catch (IOException e) {
                LOGGER.debug("Stream closed when process destroyed", e);
            }
        }, "rg-output-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        return readerThread;
    }

    static String resolveWorkspaceDir(ExecutionContext context, String toolName) {
        var workspace = context.getCustomVariable("workspace");
        if (workspace != null) {
            return workspace.toString();
        }
        throw new AgentRuntimeException(toolName, "workspace not found in context");
    }

    static long getModifyTime(Path filePath) {
        try {
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                return Files.getLastModifiedTime(filePath).toMillis();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to get mtime for file: {}", filePath, e);
        }
        return 0;
    }

    private RipGrepUtil() {
    }

    record ProcessResult(int exitCode, String output) {
    }

    static final class RipGrepTimeoutException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        RipGrepTimeoutException(String message) {
            super(message);
        }
    }
}
