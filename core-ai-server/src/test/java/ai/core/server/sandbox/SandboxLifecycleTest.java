package ai.core.server.sandbox;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.file.FileService;
import ai.core.server.run.SubmitArtifactsTool;
import ai.core.tool.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sandbox-enabled session must tell the agent that its configured capabilities are reachable from
 * inside the sandbox through the sandbox hub — including how to call, read and recover from a hub call —
 * and must keep receiving the artifact delivery instruction, each exactly once.
 *
 * @author xander
 */
class SandboxLifecycleTest {
    private static final String HUB_MARKER = "# Session capabilities from inside the sandbox";

    private static ExecutionContext context(String userId) {
        return ExecutionContext.builder().sessionId("s1").userId(userId).sandbox(mock(Sandbox.class)).build();
    }

    private static ToolCall artifactTool() {
        return SubmitArtifactsTool.create("u1", mock(FileService.class), mock(ArtifactSink.class),
                mock(PublicUrlConfiguration.class));
    }

    private static int occurrences(String text, String needle) {
        var count = 0;
        var index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private SandboxLifecycle lifecycle;
    private Agent agent;
    private List<ToolCall> tools;

    @BeforeEach
    void setUp() {
        lifecycle = new SandboxLifecycle(mock(FileService.class), mock(ArtifactSink.class),
                mock(PublicUrlConfiguration.class));
        agent = mock(Agent.class);
        tools = new ArrayList<>();
        var prompt = new AtomicReference<>("base prompt");
        when(agent.getSystemPrompt()).thenAnswer(invocation -> prompt.get());
        doAnswer(invocation -> {
            prompt.set(invocation.getArgument(0));
            return null;
        }).when(agent).setSystemPrompt(anyString());
        when(agent.getToolCalls()).thenAnswer(invocation -> List.copyOf(tools));
        doAnswer(invocation -> {
            tools.addAll(invocation.getArgument(0));
            return null;
        }).when(agent).addTools(any());
    }

    @Test
    void sessionWithoutSandboxIsLeftUntouched() {
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"),
                ExecutionContext.builder().userId("u1").build());

        verify(agent, never()).setSystemPrompt(anyString());
        assertTrue(tools.isEmpty());
    }

    @Test
    void sandboxSessionGetsHubInstructionsAndArtifactTools() {
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context("u1"));

        var prompt = agent.getSystemPrompt();
        assertTrue(prompt.contains(HUB_MARKER));
        assertTrue(prompt.contains("core_ai_sandbox"));
        assertTrue(prompt.contains("core-ai-sandbox catalog"));
        assertTrue(prompt.contains("Platform artifact delivery"));

        assertEquals(List.of(SubmitArtifactsTool.TOOL_NAME),
                tools.stream().map(ToolCall::getName).toList());
    }

    @Test
    void hubInstructionsTeachTheSdkCallResultAndErrorContract() {
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context("u1"));

        var prompt = agent.getSystemPrompt();
        assertTrue(prompt.contains("input_schema"), "arguments come from the tool's schema");
        assertTrue(prompt.contains("ToolError"), "a failed call must be known to raise");
        assertTrue(prompt.contains(".data"), "a script must know how to read a result");
        assertTrue(prompt.contains("wait=False"), "long calls must be pollable instead of blocking");
        assertTrue(prompt.contains("s.files.publish"), "script output must be publishable as an artifact");
        assertTrue(prompt.contains("6 timeout"), "bash skills branch on the CLI exit code");
    }

    @Test
    void hubInstructionsStayWithinPromptBudget() {
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context("u1"));

        assertTrue(agent.getSystemPrompt().length() < 6000,
                "hub instructions are appended to every sandbox session; keep them a reference, not a handbook");
    }

    @Test
    void hubInstructionsAreAppendedOnlyOnce() {
        var context = context("u1");

        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context);
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context);

        assertEquals(1, occurrences(agent.getSystemPrompt(), HUB_MARKER));
    }

    @Test
    void artifactToolIsNotAddedTwice() {
        tools.add(artifactTool());

        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context("u1"));
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"), context("u1"));

        assertEquals(1, tools.size());
        assertEquals(1, occurrences(agent.getSystemPrompt(), HUB_MARKER));
    }

    @Test
    void hubInstructionsStillAddedWithoutUserId() {
        lifecycle.beforeAgentRun(agent, new AtomicReference<>("q"),
                ExecutionContext.builder().sessionId("s1").sandbox(mock(Sandbox.class)).build());

        assertTrue(agent.getSystemPrompt().contains(HUB_MARKER));
        assertTrue(tools.isEmpty());
    }
}
