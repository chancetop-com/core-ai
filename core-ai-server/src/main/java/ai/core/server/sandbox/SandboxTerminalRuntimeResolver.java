package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;

/**
 * Resolves a sandbox terminal runtime address without mutating any lifecycle state:
 * never calls reattachOrCreateSandbox/beginEpoch, never writes sessionSandboxes or Redis,
 * never calls provider.acquire/release, never closes an attached sandbox. On the owner pod
 * (session already holds a live, non-pending sandbox with the bound id) it reuses that
 * sandbox's address with no provider call at all; otherwise it attaches transiently.
 * <p>
 * Extracted from {@link SandboxService} (transient path only; the durable sandbox
 * lifecycle stays on SandboxService itself) so terminal-resolution concerns don't
 * grow the main service file.
 *
 * @author Xander
 */
public class SandboxTerminalRuntimeResolver {
    /** Pure comparison of the durable binding against the requested sandbox id. Package-private for tests. */
    static TerminalRuntimeStatus decideTerminalRuntime(String boundSandboxId, String requestedSandboxId) {
        if (boundSandboxId == null) return TerminalRuntimeStatus.MISSING;
        if (!boundSandboxId.equals(requestedSandboxId)) return TerminalRuntimeStatus.REPLACED;
        return TerminalRuntimeStatus.CURRENT;
    }

    private final SandboxService sandboxService;
    private final SandboxManager sandboxManager;
    private final SandboxConfig defaultConfig;

    public SandboxTerminalRuntimeResolver(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
        this.sandboxManager = sandboxService.sandboxManager();
        this.defaultConfig = sandboxService.getDefaultConfig();
    }

    public TerminalRuntime resolveTerminalRuntime(String sessionId, String requestedSandboxId, String userId) {
        var boundSandboxId = sandboxService.getSandboxId(sessionId);
        var status = decideTerminalRuntime(boundSandboxId, requestedSandboxId);
        if (status != TerminalRuntimeStatus.CURRENT) return TerminalRuntime.of(status);

        var local = sandboxService.sessionSandbox(sessionId);
        if (local != null && !"pending".equals(local.getId()) && local.getId().equals(boundSandboxId)) {
            return new TerminalRuntime(TerminalRuntimeStatus.CURRENT, local.ip(), local.port());
        }

        var attached = sandboxManager.attachTransient(requestedSandboxId, defaultConfig, sessionId, userId);
        return attached
                .map(sandbox -> new TerminalRuntime(TerminalRuntimeStatus.CURRENT, sandbox.ip(), sandbox.port()))
                .orElseGet(() -> TerminalRuntime.of(TerminalRuntimeStatus.MISSING));
    }

    public enum TerminalRuntimeStatus { CURRENT, REPLACED, MISSING }

    public record TerminalRuntime(TerminalRuntimeStatus status, String ip, int port) {
        private static TerminalRuntime of(TerminalRuntimeStatus status) {
            return new TerminalRuntime(status, null, 0);
        }
    }
}
