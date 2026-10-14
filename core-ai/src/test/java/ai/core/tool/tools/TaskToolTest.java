package ai.core.tool.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The task tool spawns a local subagent; the tag rides on every tool event so clients can list
 * subagent launches separately from ordinary tool calls.
 *
 * @author stephen
 */
class TaskToolTest {
    @Test
    void taskIsTaggedAsSubagent() {
        var tool = TaskTool.builder().build();

        assertEquals("subagent", tool.getSourceType());
    }
}
