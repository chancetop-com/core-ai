package ai.core.tool.subagent;

public interface SubagentOutputSinkFactory {

    SubagentOutputSink create(String taskId);
}
