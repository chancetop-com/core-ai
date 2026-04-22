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

    default void materializeSkill(String name, String version, byte[] tarBytes) {
        throw new UnsupportedOperationException("materializeSkill not supported by this sandbox");
    }

    @Override
    void close();
}
