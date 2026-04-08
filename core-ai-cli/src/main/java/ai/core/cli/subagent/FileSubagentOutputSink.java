package ai.core.cli.subagent;

import ai.core.tool.subagent.SubagentOutputSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileSubagentOutputSink implements SubagentOutputSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSubagentOutputSink.class);

    private final Path filePath;
    private final BufferedWriter writer;
    private final Object lock = new Object();

    public FileSubagentOutputSink(Path baseDir, String taskId) throws IOException {
        Files.createDirectories(baseDir);
        this.filePath = baseDir.resolve(taskId + ".output");
        this.writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public void write(String content) {
        synchronized (lock) {
            try {
                writer.write(content);
                writer.flush();
            } catch (IOException e) {
                LOGGER.warn("Failed to write to subagent output sink: {}", filePath, e);
            }
        }
    }

    @Override
    public String getReference() {
        return filePath.toString();
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                writer.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close subagent output sink: {}", filePath, e);
            }
        }
    }
}
