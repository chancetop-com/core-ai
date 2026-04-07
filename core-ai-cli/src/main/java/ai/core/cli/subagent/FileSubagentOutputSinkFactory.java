package ai.core.cli.subagent;

import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;

import java.nio.file.Path;

public class FileSubagentOutputSinkFactory implements SubagentOutputSinkFactory {
    private static final Path DEFAULT_BASE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "core-ai-tasks");

    private final Path baseDir;

    public FileSubagentOutputSinkFactory() {
        this(null);
    }

    public FileSubagentOutputSinkFactory(Path baseDir) {
        this.baseDir = baseDir != null ? baseDir : DEFAULT_BASE_DIR;
    }

    @Override
    public SubagentOutputSink create(String taskId) {
        try {
            return new FileSubagentOutputSink(baseDir, taskId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create subagent output sink for taskId: " + taskId, e);
        }
    }
}
