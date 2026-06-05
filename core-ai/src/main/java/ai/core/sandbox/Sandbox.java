package ai.core.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallResult;

/**
 * @author stephen
 */
public interface Sandbox extends AutoCloseable {

    boolean shouldIntercept(String toolName);

    ToolCallResult execute(String toolName, String arguments, ExecutionContext context);

    SandboxStatus getStatus();

    String getId();

    /** Returns the human-readable hostname of this sandbox (e.g. pod name, container id). */
    default String hostname() {
        return getId();
    }

    default void materializeSkill(String name, String version, byte[] tarBytes) {
        throw new UnsupportedOperationException("materializeSkill not supported by this sandbox");
    }

    default SandboxFile downloadFile(String path) {
        throw new UnsupportedOperationException("downloadFile not supported by this sandbox");
    }

    /** Uploads file content to the sandbox at the specified path. */
    default void uploadFile(String path, byte[] content) {
        throw new UnsupportedOperationException("uploadFile not supported by this sandbox");
    }

    default String ip() {
        return null;
    }

    default String image() {
        return null;
    }

    @Override
    void close();
}
