package ai.core.cli.task;

import ai.core.tool.tools.TodoStore;
import ai.core.tool.tools.TodoStoreFactory;

import java.nio.file.Path;

/**
 * File-based {@link TodoStoreFactory} that stores tasks under a configurable base directory.
 *
 * @author lim chen
 */
public class FileTodoStoreFactory implements TodoStoreFactory {
    private final Path baseDir;

    public FileTodoStoreFactory(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public TodoStore create(String sessionId) {
        return new FileTodoStore(baseDir, sessionId);
    }
}
