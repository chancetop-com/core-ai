package ai.core.tool.subagent;

/**
 * Receives output from a background subagent.
 * Implementations are provided by the application layer (CLI, server, etc.)
 */
public interface SubagentOutputSink {

    void write(String content);

    String getReference();

    void close();
}
