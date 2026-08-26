package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.tool.ToolCallResult;
import ai.core.tool.github.GitHubTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequireGithubInstallationTokenToolTest {

    private static final String TOKEN = "ghs_installationTokenValue";

    @Test
    void sandboxInjectionConfiguresCredentialsAndHidesToken() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(anyString(), anyString(), any())).thenReturn(ToolCallResult.completed("OK"));
        var context = ExecutionContext.builder().sandbox(sandbox).build();
        var tool = RequireGithubInstallationTokenTool.builder(new FixedTokenProvider()).build();

        var result = tool.execute("{\"repo\":\"owner/repo\"}", context);

        assertTrue(result.isCompleted());
        assertFalse(result.getResult().contains(TOKEN));
        assertEquals("sandbox", result.getStats().get("injection"));
        var captor = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(org.mockito.ArgumentMatchers.eq(ShellCommandTool.TOOL_NAME), captor.capture(), any());
        var script = captor.getValue();
        assertTrue(script.contains("$HOME/.git-credentials"));
        assertTrue(script.contains("$HOME/.config/gh/hosts.yml"));
        assertTrue(script.contains("git config --global credential.helper store"));
    }

    @Test
    void sandboxInjectionFailureFallsBackToInlineToken() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(anyString(), anyString(), any())).thenReturn(ToolCallResult.failed("sandbox error"));
        var context = ExecutionContext.builder().sandbox(sandbox).build();
        var tool = RequireGithubInstallationTokenTool.builder(new FixedTokenProvider()).build();

        var result = tool.execute("{\"repo\":\"owner/repo\"}", context);

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains(TOKEN));
        assertEquals("inline", result.getStats().get("injection"));
    }

    @Test
    void noSandboxUsesInlineToken() {
        var tool = RequireGithubInstallationTokenTool.builder(new FixedTokenProvider()).build();

        var result = tool.execute("{\"repo\":\"owner/repo\"}", ExecutionContext.empty());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains(TOKEN));
        assertEquals("inline", result.getStats().get("injection"));
    }

    @Test
    void executeWithoutContextUsesInlineToken() {
        var tool = RequireGithubInstallationTokenTool.builder(new FixedTokenProvider()).build();

        var result = tool.execute("{\"repo\":\"owner/repo\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains(TOKEN));
    }

    @Test
    void injectionScriptEscapesSingleQuotesInToken() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(anyString(), anyString(), any())).thenReturn(ToolCallResult.completed("OK"));
        var context = ExecutionContext.builder().sandbox(sandbox).build();
        var tool = RequireGithubInstallationTokenTool.builder(new FixedTokenProvider("ghs_ab'cd")).build();

        tool.execute("{\"repo\":\"owner/repo\"}", context);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(org.mockito.ArgumentMatchers.eq(ShellCommandTool.TOOL_NAME), captor.capture(), any());
        var script = ai.core.utils.JsonUtil.toMap(captor.getValue()).get("command").toString();
        assertTrue(script.contains("'ghs_ab'\\''cd'"), "script was: " + script);
    }

    private static final class FixedTokenProvider implements GitHubTokenProvider {
        private final String token;

        private FixedTokenProvider() {
            this(TOKEN);
        }

        private FixedTokenProvider(String token) {
            this.token = token;
        }

        @Override
        public String getInstallationToken(String repo) {
            return token;
        }
    }
}
