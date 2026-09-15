package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The global long-running sandbox used for MCP discovery: it outlives sessions, so it is acquired once
 * and re-acquired when its pod disappears behind a lazy handle that still reports READY.
 *
 * @author stephen
 */
class DiscoverySandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoverySandbox.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 5_000;

    private static SandboxConfig createConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = Boolean.FALSE;
        config.timeoutSeconds = 86_400; // 24 hours
        return config;
    }

    private final SandboxManager sandboxManager;
    private LazySandbox sandbox;

    DiscoverySandbox(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    SandboxClient client() {
        synchronized (this) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                if (sandbox == null) {
                    sandbox = new LazySandbox(createConfig(), sandboxManager, null, new LazySandbox.SessionIdentity("discovery", "system"), null);
                    LOGGER.info("discovery sandbox created (attempt {}/{})", attempt + 1, MAX_ATTEMPTS);
                }
                sandbox.ensureReady();
                var ip = sandbox.ip();
                var port = sandbox.port();
                if (ip == null || port == 0) {
                    throw new IllegalStateException("discovery sandbox ip/port not available");
                }
                var client = new SandboxClient(ip, port, SandboxConstants.MCP_STARTUP_TIMEOUT_SECONDS);
                // The runtime's health check tells apart a live pod from a stale READY handle: when the
                // underlying pod is gone the handle still reports READY with an old IP, so the discovery
                // sandbox is closed and acquired again instead of failing for every later caller.
                try {
                    client.waitForReady(HEALTH_CHECK_TIMEOUT_MS);
                    LOGGER.info("discovery sandbox ready: ip={}, port={}", ip, port);
                    return client;
                } catch (Exception e) {
                    LOGGER.warn("discovery sandbox unreachable (attempt {}/{}): ip={}, port={}, error={}",
                            attempt + 1, MAX_ATTEMPTS, ip, port, e.getMessage());
                    reset();
                }
            }
            throw new IllegalStateException("discovery sandbox failed after " + MAX_ATTEMPTS + " attempts");
        }
    }

    void close() {
        synchronized (this) {
            reset();
        }
    }

    private void reset() {
        if (sandbox == null) return;
        try {
            sandbox.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close discovery sandbox: {}", e.getMessage());
        }
        sandbox = null;
    }
}
