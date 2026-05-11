package ai.core.tool.tools;

/**
 * Creates {@link TodoStore} instances for a given session.
 * Implementations are provided by the application layer (CLI, server, etc.)
 * to supply the storage backend with the appropriate base path.
 *
 * @author lim chen
 */
public interface TodoStoreFactory {

    TodoStore create(String sessionId);
}
