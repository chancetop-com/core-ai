package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpServerConfig;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.sandbox.SandboxClient;
import ai.core.server.sandbox.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class McpServerConnectionManagerTest {
    private final ApplicationMcpManager applicationManager = mock(ApplicationMcpManager.class);
    private final McpClientManager mcpManager = mock(McpClientManager.class);
    private final SandboxService sandboxService = mock(SandboxService.class);
    private final SandboxClient discoveryClient = mock(SandboxClient.class);
    private final Sandbox sandbox = mock(Sandbox.class);
    private final McpServerConnectionManager connectionManager = new McpServerConnectionManager(sandboxService, applicationManager);

    @BeforeEach
    void setUp() {
        when(applicationManager.getOrCreate()).thenReturn(mcpManager);
        when(sandboxService.getDiscoverySandboxClient()).thenReturn(discoveryClient);
        when(discoveryClient.getBaseUrl()).thenReturn("https://discovery.example.test");
        when(sandbox.getMcpEndpoint()).thenReturn("https://session.example.test");
    }

    @Test
    void disabledHttpServerDoesNotRegisterWithApplicationManager() {
        var entry = entry(false, Map.of("url", "https://mcp.example.test", "transport", "streamable_http"));

        connectionManager.registerMcpServer(entry);

        verifyNoInteractions(applicationManager, mcpManager, sandboxService, discoveryClient, sandbox);
    }

    @Test
    void disabledSandboxServerDoesNotStartDiscoveryProcess() {
        var entry = entry(false, sandboxConfig());

        assertFalse(connectionManager.ensureRegisteredOnDiscovery(entry));

        verifyNoInteractions(applicationManager, mcpManager, sandboxService, discoveryClient, sandbox);
    }

    @Test
    void disabledSandboxServerDoesNotStartSessionProcess() {
        var entry = entry(false, sandboxConfig());

        connectionManager.registerOnSession(entry, mcpManager, sandbox, "session-id", false);
        connectionManager.registerOnSession(entry, mcpManager, sandbox, "session-id", true);

        verifyNoInteractions(applicationManager, mcpManager, sandboxService, discoveryClient, sandbox);
    }

    @Test
    void enabledHttpServerRegistersItsConfiguration() {
        var entry = entry(true, Map.of("url", "https://mcp.example.test", "transport", "streamable_http"));

        connectionManager.registerMcpServer(entry);

        var configuration = ArgumentCaptor.forClass(McpServerConfig.class);
        verify(mcpManager).addServer(configuration.capture());
        assertEquals(entry.id, configuration.getValue().getName());
        verifyNoInteractions(sandboxService, discoveryClient, sandbox);
    }

    @Test
    void enabledSandboxServerStartsDiscoveryProcess() {
        var entry = entry(true, sandboxConfig());

        assertTrue(connectionManager.ensureRegisteredOnDiscovery(entry));

        verify(discoveryClient).startMcpServer(entry.id, "python", List.of("server.py"), Map.of());
        verify(mcpManager).addServer(any(McpServerConfig.class));
        verifyNoInteractions(sandbox);
    }

    @Test
    void blockingSessionStartRegistersTransportAfterTheProcessIsReady() {
        var entry = entry(true, sandboxConfig());

        connectionManager.registerOnSession(entry, mcpManager, sandbox, "session-id", false);

        verify(sandbox).startMcpServer(entry.id, "python", List.of("server.py"), Map.of(), SandboxConstants.SESSION_MCP_STARTUP_TIMEOUT_SECONDS);
        verify(sandboxService).recordSessionMcpServer("session-id", entry.id);
        verify(mcpManager).addServer(any(McpServerConfig.class));
        verifyNoInteractions(applicationManager, discoveryClient);
    }

    @Test
    void backgroundSessionStartDoesNotBlockTheCaller() throws Exception {
        var entry = entry(true, sandboxConfig());
        var release = new CountDownLatch(1);
        when(sandbox.startMcpServer(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenAnswer(invocation -> {
                    release.await(5, TimeUnit.SECONDS);
                    return entry.id;
                });

        long begin = System.nanoTime();
        connectionManager.registerOnSession(entry, mcpManager, sandbox, "session-id", true);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 1000, "session registration must not wait for the sandbox start, took " + elapsedMs + "ms");
        release.countDown();
        verify(sandbox, timeout(2000)).startMcpServer(entry.id, "python", List.of("server.py"), Map.of(), SandboxConstants.MCP_STARTUP_TIMEOUT_SECONDS);
        verify(mcpManager, timeout(2000)).addServer(any(McpServerConfig.class));
        verify(sandboxService, timeout(2000)).recordSessionMcpServer("session-id", entry.id);
    }

    @Test
    void backgroundSessionStartRegistersNothingWhenTheSandboxStartFails() {
        var entry = entry(true, sandboxConfig());
        when(sandbox.startMcpServer(anyString(), anyString(), anyList(), anyMap(), anyInt()))
                .thenThrow(new RuntimeException("timeout"));

        connectionManager.registerOnSession(entry, mcpManager, sandbox, "session-id", true);

        verify(sandbox, timeout(2000)).startMcpServer(anyString(), anyString(), anyList(), anyMap(), anyInt());
        verify(mcpManager, never()).addServer(any(McpServerConfig.class));
        verifyNoInteractions(sandboxService);
    }

    private Map<String, String> sandboxConfig() {
        return Map.of("transport", "sandbox_hosted", "command", "python", "args", "[\"server.py\"]");
    }

    private ToolRegistryEntry entry(boolean enabled, Map<String, String> config) {
        var entry = new ToolRegistryEntry();
        entry.id = "server-id";
        entry.name = "test-mcp";
        entry.type = ToolType.MCP;
        entry.enabled = enabled;
        entry.config = config;
        return entry;
    }
}
