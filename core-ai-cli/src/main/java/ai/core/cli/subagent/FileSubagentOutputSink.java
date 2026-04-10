package ai.core.cli.subagent;

import ai.core.tool.subagent.SubagentOutputSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class FileSubagentOutputSink implements SubagentOutputSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSubagentOutputSink.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final Path filePath;
    private final BufferedWriter writer;
    private final Object lock = new Object();

    public FileSubagentOutputSink(Path baseDir, String taskId) throws IOException {
        Files.createDirectories(baseDir);
        String dateTime = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String fileName = taskId + "-" + dateTime + "-" + uniqueSuffix + ".output";
        this.filePath = baseDir.resolve(fileName);
        this.writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
