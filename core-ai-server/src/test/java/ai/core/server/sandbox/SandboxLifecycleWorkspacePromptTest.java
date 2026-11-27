package ai.core.server.sandbox;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxLifecycleWorkspacePromptTest {

    @Test
    void injectsWorkspaceNotesPromptOnlyForPersistentWorkspaceSessions() {
        var lifecycle = new SandboxLifecycle(null, null, null, null);
        var llm = Mockito.mock(LLMProvider.class);
        var wsAgent = Agent.builder().name("ws-agent").systemPrompt("base").llmProvider(llm).build();
        var wsSandbox = new WorkspaceSandbox(new SandboxConfig(),
                new WorkspaceSandbox.SessionRef("s1", "u1", "ws-1"), null, null, null, null);
        var wsCtx = ExecutionContext.builder().sessionId("s1").sandbox(wsSandbox).build();

        lifecycle.beforeAgentRun(wsAgent, new AtomicReference<>("hi"), wsCtx);
        lifecycle.beforeAgentRun(wsAgent, new AtomicReference<>("hi again"), wsCtx);

        var wsAppendix = wsAgent.getSystemPromptAppendix();
        assertNotNull(wsAppendix);
        assertTrue(wsAppendix.contains("/workspace/AGENTS.md"));
        assertTrue(wsAppendix.contains("/workspace/.core-ai/NOTES.md"));
        assertEqualsOneOccurrence(wsAppendix, "# Persistent workspace");

        var regularAgent = Agent.builder().name("ephemeral-agent").systemPrompt("base").llmProvider(llm).build();
        var regularCtx = ExecutionContext.builder().sessionId("s2").sandbox(Mockito.mock(Sandbox.class)).build();
        lifecycle.beforeAgentRun(regularAgent, new AtomicReference<>("hi"), regularCtx);

        var regularAppendix = regularAgent.getSystemPromptAppendix();
        assertNotNull(regularAppendix);
        assertFalse(regularAppendix.contains("# Persistent workspace"));
        assertFalse(regularAppendix.contains("/workspace/.core-ai/NOTES.md"));
    }

    private void assertEqualsOneOccurrence(String text, String marker) {
        var first = text.indexOf(marker);
        assertTrue(first >= 0);
        assertTrue(text.indexOf(marker, first + marker.length()) < 0);
    }
}
