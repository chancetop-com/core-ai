package ai.core.server.channel.openclaw;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class OcgSandboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgSandboxService.class);
    private static final String CONFIG_PATH = "/root/.openclaw-channel-gateway/ocg.json";
    private static final String TEMP_CONFIG_PATH = "/tmp/ocg.json";
    private static final String GATEWAY_LOG_PATH = "/tmp/ocg.log";
    private static final String TERMINAL_LOG_PATH = "/tmp/ocg-terminal.log";
    private static final String TERMINAL_WORK_DIR = "/root/ocg-work";
    private static final String OPENCLAW_STATE_DIR = "/root/.openclaw";
    private static final String OPENCLAW_CHANNEL_TYPE = "openclaw";
    private static final int DEFAULT_CALLBACK_PORT = 3457;
    private static final int GATEWAY_START_WAIT_SECONDS = 30;
    private static final Set<String> GATEWAY_CONFIG_KEYS = Set.of("agentUrl", "model", "apiKey", "verbose", "async", "callbackHost", "callbackPort", "callbackPublicHost", "callbackPublicPort", "callbackSecret", "callbackTokenTTL", "channels", "plugins");

    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    SandboxService sandboxService;

    public String publicUrl;

    public void startSandbox(String ocgConfigId) {
        synchronized (this) {
            var config = loadConfig(ocgConfigId);
            if (config.sandboxId != null && !config.sandboxId.isBlank()) {
                throw new BadRequestException("OCG sandbox already started: " + ocgConfigId);
            }
            var channel = channelConfigStore.load(config.channelId);
            if (channel == null) throw new BadRequestException("channel not found: " + config.channelId);
            if (!OPENCLAW_CHANNEL_TYPE.equals(channel.channelType)) {
                throw new BadRequestException("channel " + config.channelId + " is not openclaw");
            }

            var sessionId = sandboxSessionId(config.id);
            var sandbox = sandboxService.createSandbox(buildSandboxConfig(), sessionId, "system", true);
            if (sandbox == null) throw new BadRequestException("sandbox is disabled");
            boolean committed = false;
            try {
                sandboxService.ensureSandboxReady(sessionId);
                runCommand(sandbox, "mkdir -p " + TERMINAL_WORK_DIR + " && chmod 777 " + TERMINAL_WORK_DIR, 10);
                var sandboxIp = sandbox.ip();
                if (sandboxIp == null || sandboxIp.isBlank()) throw new BadRequestException("sandbox ip is unavailable");
                uploadRuntimeConfig(sandbox, config, sandboxIp, sandbox.port());
                startGatewayProcess(sandbox);
                waitForGatewayProcess(sandbox, config.id);
                config.sandboxId = sandbox.getId();
                config.sandboxIp = sandboxIp;
                config.updatedAt = ZonedDateTime.now();
                ocgConfigStore.store(config);
                committed = true;
                LOGGER.info("OCG sandbox started, id={}, sandboxId={}, ip={}", config.id, config.sandboxId, config.sandboxIp);
            } finally {
                if (!committed) {
                    sandboxService.releaseSandbox(sessionId);
                }
            }
        }
    }

    public void stopSandbox(String ocgConfigId) {
        synchronized (this) {
            var config = loadConfig(ocgConfigId);
            if (config.sandboxId != null && !config.sandboxId.isBlank()) {
                sandboxService.releaseSandbox(sandboxSessionId(config.id));
            }
            ocgConfigStore.clearSandbox(config.id);
            LOGGER.info("OCG sandbox stopped, id={}", config.id);
        }
    }

    public String getStatus(String ocgConfigId) {
        var config = loadConfig(ocgConfigId);
        if (config.sandboxId == null || config.sandboxId.isBlank()) return "stopped";
        var sandbox = sandboxService.getSandbox(sandboxSessionId(config.id));
        if (sandbox == null) return "error";
        var status = sandbox.getStatus();
        if (status == SandboxStatus.TERMINATED || status == SandboxStatus.ERROR) return "error";
        try {
            runCommand(sandbox, ocgProcessCheckCommand(), 15);
            return "running";
        } catch (RuntimeException e) {
            LOGGER.warn("OCG status check failed, id={}, sandboxId={}: {}", config.id, config.sandboxId, e.getMessage());
            return "error";
        }
    }

    public void restartGateway(String ocgConfigId) {
        synchronized (this) {
            var config = loadConfig(ocgConfigId);
            var sandbox = requireSandbox(ocgConfigId);
            var sandboxIp = sandbox.ip();
            if (sandboxIp == null || sandboxIp.isBlank()) throw new BadRequestException("sandbox ip is unavailable");
            uploadRuntimeConfig(sandbox, config, sandboxIp, sandbox.port());
            stopGatewayProcess(sandbox);
            startGatewayProcess(sandbox);
            waitForGatewayProcess(sandbox, config.id);
            config.sandboxIp = sandboxIp;
            config.updatedAt = ZonedDateTime.now();
            ocgConfigStore.store(config);
            LOGGER.info("OCG gateway restarted, id={}", ocgConfigId);
        }
    }

    public void runTerminalCommand(String ocgConfigId, String command) {
        if (command == null || command.isBlank()) throw new BadRequestException("command is required");
        var sandbox = requireSandbox(ocgConfigId);
        var terminalCommand = "export OCG_CONFIG_PATH=" + CONFIG_PATH + " OPENCLAW_STATE_DIR=" + OPENCLAW_STATE_DIR + " CLAWDBOT_STATE_DIR=" + OPENCLAW_STATE_DIR + "; mkdir -p " + TERMINAL_WORK_DIR + " " + OPENCLAW_STATE_DIR + " && chmod 777 " + TERMINAL_WORK_DIR + "; cd " + TERMINAL_WORK_DIR + " || exit 1; openclaw() { ocg \"$@\"; }; { printf '$ %s\\n' " + shellQuote(command.trim()) + "; " + command.trim() + "; printf '\\n[exit code: %s]\\n' \"$?\"; } > " + TERMINAL_LOG_PATH + " 2>&1 &";
        runCommand(sandbox, terminalCommand, 10);
        LOGGER.info("OCG terminal command started, id={}, command={}", ocgConfigId, command);
    }

    public String logs(String ocgConfigId, String logType, int tail) {
        var sandbox = requireSandbox(ocgConfigId);
        var path = "terminal".equalsIgnoreCase(logType) ? TERMINAL_LOG_PATH : GATEWAY_LOG_PATH;
        var boundedTail = Math.max(1, Math.min(tail, 2_000));
        return runCommand(sandbox, "test -f " + path + " && tail -" + boundedTail + " " + path + " || true", 15);
    }

    public void recoverOnStartup() {
        for (var config : ocgConfigStore.allWithSandbox()) {
            try {
                var attached = sandboxService.attachSandbox(config.sandboxId, buildSandboxConfig(), sandboxSessionId(config.id), "system", true);
                if (attached != null) {
                    recoverGatewayProcess(config, attached);
                    LOGGER.info("OCG sandbox recovered, id={}, sandboxId={}, ip={}", config.id, config.sandboxId, config.sandboxIp);
                } else {
                    ocgConfigStore.clearSandbox(config.id);
                    LOGGER.warn("OCG sandbox lost, id={}, sandboxId={}", config.id, config.sandboxId);
                }
            } catch (RuntimeException e) {
                ocgConfigStore.clearSandbox(config.id);
                LOGGER.warn("OCG sandbox recovery failed, id={}, sandboxId={}", config.id, config.sandboxId, e);
            }
        }
    }

    private void recoverGatewayProcess(OcgConfigView config, Sandbox sandbox) {
        var sandboxIp = sandbox.ip();
        if (sandboxIp == null || sandboxIp.isBlank()) throw new BadRequestException("sandbox ip is unavailable");
        var restarted = false;
        try {
            runCommand(sandbox, ocgProcessCheckCommand(), 15);
            ensureRuntimeConfigExists(sandbox, config, sandboxIp, sandbox.port());
        } catch (RuntimeException e) {
            LOGGER.warn("OCG gateway process check failed, restarting, id={}, sandboxId={}, error={}", config.id, config.sandboxId, truncate(e.getMessage(), 1_000));
            uploadRuntimeConfig(sandbox, config, sandboxIp, sandbox.port());
            stopGatewayProcess(sandbox);
            startGatewayProcess(sandbox);
            waitForGatewayProcess(sandbox, config.id);
            restarted = true;
        }
        if (restarted || !sandboxIp.equals(config.sandboxIp)) {
            config.sandboxIp = sandboxIp;
            config.updatedAt = ZonedDateTime.now();
            ocgConfigStore.store(config);
            LOGGER.info("OCG sandbox recovery state stored, id={}, sandboxId={}, sandboxIp={}, restarted={}", config.id, config.sandboxId, sandboxIp, restarted);
        }
    }

    public void healthCheck() {
        RuntimeException failure = null;
        for (var config : ocgConfigStore.allWithSandbox()) {
            var sessionId = sandboxSessionId(config.id);
            var sandbox = sandboxService.getSandbox(sessionId);
            if (sandbox == null) {
                LOGGER.warn("OCG sandbox is not attached, attempting attach, id={}, sandboxId={}", config.id, config.sandboxId);
                sandbox = sandboxService.attachSandbox(config.sandboxId, buildSandboxConfig(), sessionId, "system", true);
                if (sandbox == null) {
                    var error = new RuntimeException("OCG sandbox attach failed: id=" + config.id + ", sandboxId=" + config.sandboxId);
                    LOGGER.warn("OCG sandbox attach failed, id={}, sandboxId={}", config.id, config.sandboxId);
                    failure = error;
                    continue;
                }
            }
            try {
                sandboxService.renewSandbox(sessionId);
                recoverGatewayProcess(config, sandbox);
            } catch (RuntimeException e) {
                LOGGER.warn("OCG health check failed, id={}, sandboxId={}: {}", config.id, config.sandboxId, truncate(e.getMessage(), 1_000), e);
                failure = e;
            }
        }
        if (failure != null) throw failure;
    }

    @SuppressWarnings("unchecked")
    public String buildRuntimeConfig(OcgConfigView config, String agentUrl, String sandboxHost, int sandboxPort) {
        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JsonUtil.fromJson(Map.class, config.configJson);
        } catch (Exception e) {
            throw new BadRequestException("invalid configJson: " + e.getMessage(), "INVALID_CONFIG_JSON", e);
        }
        var runtimeConfig = new LinkedHashMap<String, Object>();
        if (payload.containsKey("channels")) {
            runtimeConfig.putAll(payload);
        } else {
            runtimeConfig.put("channels", extractChannelConfig(payload));
            payload.forEach((key, value) -> {
                if (GATEWAY_CONFIG_KEYS.contains(key)) runtimeConfig.put(key, value);
            });
        }
        runtimeConfig.put("agentUrl", agentUrl);
        runtimeConfig.remove("apiKey");
        runtimeConfig.put("async", Boolean.TRUE);
        runtimeConfig.put("callbackHost", "127.0.0.1");
        runtimeConfig.put("callbackPort", DEFAULT_CALLBACK_PORT);
        runtimeConfig.put("callbackPublicHost", sandboxHost);
        runtimeConfig.put("callbackPublicPort", sandboxPort);
        if (config.callbackSecret != null && !config.callbackSecret.isBlank()) {
            runtimeConfig.put("callbackSecret", config.callbackSecret);
        } else {
            runtimeConfig.remove("callbackSecret");
        }
        return JsonUtil.toJson(runtimeConfig);
    }

    private Map<String, Object> extractChannelConfig(Map<String, Object> payload) {
        var channels = new LinkedHashMap<String, Object>();
        payload.forEach((key, value) -> {
            if (!GATEWAY_CONFIG_KEYS.contains(key)) channels.put(key, value);
        });
        if (channels.isEmpty()) throw new BadRequestException("configJson must contain channels or at least one channel config");
        return channels;
    }

    public SandboxConfig buildSandboxConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        config.networkEnabled = Boolean.TRUE;
        config.timeoutSeconds = 86_400;
        config.memoryLimitMb = 1024;
        config.cpuLimitMillicores = 500;
        return config;
    }

    private void startGatewayProcess(Sandbox sandbox) {
        runCommand(sandbox, "OPENCLAW_STATE_DIR=" + OPENCLAW_STATE_DIR + " CLAWDBOT_STATE_DIR=" + OPENCLAW_STATE_DIR + " OCG_CONFIG_PATH=" + CONFIG_PATH + " nohup ocg start > " + GATEWAY_LOG_PATH + " 2>&1 &", 10);
    }

    private void waitForGatewayProcess(Sandbox sandbox, String ocgConfigId) {
        try {
            runCommand(sandbox, gatewayWaitCommand(), GATEWAY_START_WAIT_SECONDS + 5);
        } catch (RuntimeException e) {
            logSandboxSnapshot(sandbox, ocgConfigId, "gateway-start-timeout");
            throw e;
        }
    }

    private void ensureRuntimeConfigExists(Sandbox sandbox, OcgConfigView config, String sandboxHost, int sandboxPort) {
        try {
            runCommand(sandbox, "test -s " + CONFIG_PATH, 10);
        } catch (RuntimeException e) {
            LOGGER.warn("OCG runtime config missing, uploading, id={}, path={}, error={}", config.id, CONFIG_PATH, truncate(e.getMessage(), 500));
            uploadRuntimeConfig(sandbox, config, sandboxHost, sandboxPort);
        }
    }

    private void uploadRuntimeConfig(Sandbox sandbox, OcgConfigView config, String sandboxHost, int sandboxPort) {
        runCommand(sandbox, "mkdir -p " + TERMINAL_WORK_DIR + " /root/.openclaw-channel-gateway " + OPENCLAW_STATE_DIR + " && chmod 777 " + TERMINAL_WORK_DIR, 10);
        var ocgJson = buildRuntimeConfig(config, agentUrl(config.channelId, serverUrlFromSandbox()), sandboxHost, sandboxPort);
        var existing = existingRuntimeConfig(sandbox);
        var runtimeConfig = mergeRuntimeConfig(existing, parseJsonMap(ocgJson));
        var runtimeConfigJson = JsonUtil.toJson(runtimeConfig);
        sandbox.uploadFile(TEMP_CONFIG_PATH, runtimeConfigJson.getBytes(StandardCharsets.UTF_8));
        runCommand(sandbox, "cp " + TEMP_CONFIG_PATH + " " + CONFIG_PATH + " && test -s " + CONFIG_PATH + " && ls -l " + TEMP_CONFIG_PATH + " " + CONFIG_PATH, 10);
    }

    private Map<String, Object> existingRuntimeConfig(Sandbox sandbox) {
        var json = runCommand(sandbox, "cat " + CONFIG_PATH + " 2>/dev/null || cat " + TEMP_CONFIG_PATH + " 2>/dev/null || true", 10);
        if (json.isBlank()) return Map.of();
        try {
            return parseJsonMap(json);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to parse existing OCG config, rebuilding from persisted config: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeRuntimeConfig(Map<String, Object> existing, Map<String, Object> desired) {
        if (existing.isEmpty()) return desired;
        var merged = new LinkedHashMap<>(existing);
        var existingChannels = existing.get("channels") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.<String, Object>of();
        var desiredChannels = desired.get("channels") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.<String, Object>of();
        if (!existingChannels.isEmpty() || !desiredChannels.isEmpty()) {
            var channels = new LinkedHashMap<>(existingChannels);
            channels.putAll(desiredChannels);
            merged.put("channels", channels);
        }
        merged.putAll(desired);
        if (!existingChannels.isEmpty() || !desiredChannels.isEmpty()) {
            var channels = new LinkedHashMap<>(existingChannels);
            channels.putAll(desiredChannels);
            merged.put("channels", channels);
        }
        return merged;
    }

    private void stopGatewayProcess(Sandbox sandbox) {
        runCommand(sandbox, "ps -eo pid=,args= | awk '($0 ~ /openclaw-channel-gateway/ && $0 ~ /dist\\/cli.js start/ || $0 ~ /node \\/usr\\/local\\/bin\\/ocg start/) && $0 !~ /awk/ && $0 !~ /bash -c/ {print $1}' | xargs -r kill; true", 10);
    }

    @SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
    private String ocgProcessCheckCommand() {
        return "ps aux | grep [n]ode | grep openclaw-channel-gateway | grep dist/cli.js | grep start || (echo 'OCG gateway process not found'; exit 1)";
    }

    @SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
    private String gatewayWaitCommand() {
        return "i=0; while [ $i -lt " + GATEWAY_START_WAIT_SECONDS + " ]; do if ps aux | grep [n]ode | grep openclaw-channel-gateway | grep dist/cli.js | grep start; then exit 0; fi; if test -f " + GATEWAY_LOG_PATH + " && grep -E 'Error|EADDRINUSE|Unhandled|Cannot|failed|No config found' " + GATEWAY_LOG_PATH + "; then break; fi; i=$((i+1)); sleep 1; done; echo 'OCG gateway process did not become ready within " + GATEWAY_START_WAIT_SECONDS + "s'; echo '--- process ---'; ps aux | grep -E 'openclaw-channel-gateway|ocg|core-ai-sandbox-runtime' | grep -v grep || true; echo '--- files ---'; ls -la " + TEMP_CONFIG_PATH + " " + CONFIG_PATH + " " + GATEWAY_LOG_PATH + " 2>&1 || true; echo '--- gateway log ---'; test -f " + GATEWAY_LOG_PATH + " && tail -120 " + GATEWAY_LOG_PATH + " || true; exit 1";
    }

    private void logSandboxSnapshot(Sandbox sandbox, String ocgConfigId, String stage) {
        try {
            var output = runCommand(sandbox, "echo '--- process ---'; ps aux | grep -E 'openclaw-channel-gateway|ocg|core-ai-sandbox-runtime' | grep -v grep || true; echo '--- files ---'; ls -la " + TEMP_CONFIG_PATH + " " + CONFIG_PATH + " " + GATEWAY_LOG_PATH + " 2>&1 || true; echo '--- openclaw state ---'; find " + OPENCLAW_STATE_DIR + " -maxdepth 4 -type f 2>/dev/null | sort || true; echo '--- gateway log ---'; test -f " + GATEWAY_LOG_PATH + " && tail -80 " + GATEWAY_LOG_PATH + " || true", 15);
            LOGGER.info("OCG sandbox snapshot, id={}, stage={}, output={}", ocgConfigId, stage, truncate(output, 4_000));
        } catch (RuntimeException e) {
            LOGGER.warn("OCG sandbox snapshot failed, id={}, stage={}, error={}", ocgConfigId, stage, truncate(e.getMessage(), 1_000));
        }
    }

    private String runCommand(Sandbox sandbox, String command, int timeoutSeconds) {
        return runCommand(sandbox, command, timeoutSeconds, false);
    }

    private String runCommand(Sandbox sandbox, String command, int timeoutSeconds, boolean runInBackground) {
        var result = sandbox.execute(ShellCommandTool.TOOL_NAME, JsonUtil.toJson(Map.of(
                "command", command,
                "timeout", timeoutSeconds,
                "run_in_background", runInBackground)), null);
        if (!result.isCompleted()) {
            throw new RuntimeException(result.getResult());
        }
        var output = result.getResult();
        if (isCommandFailure(output)) {
            throw new RuntimeException(output);
        }
        return output != null ? output : "";
    }

    private boolean isCommandFailure(String output) {
        if (output == null) return false;
        return output.startsWith("Command exited with code")
                || output.startsWith("Command execution failed")
                || output.startsWith("Command timed out")
                || output.contains("\nexit status: exit status ");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...<truncated>";
    }

    private Sandbox requireSandbox(String ocgConfigId) {
        var config = loadConfig(ocgConfigId);
        if (config.sandboxId == null || config.sandboxId.isBlank()) throw new BadRequestException("OCG sandbox is stopped: " + ocgConfigId);
        var sandbox = sandboxService.getSandbox(sandboxSessionId(config.id));
        if (sandbox == null) throw new BadRequestException("OCG sandbox is not attached: " + ocgConfigId);
        var status = sandbox.getStatus();
        if (status == SandboxStatus.TERMINATED || status == SandboxStatus.ERROR) throw new BadRequestException("OCG sandbox is not ready: " + status);
        return sandbox;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private OcgConfigView loadConfig(String id) {
        var config = ocgConfigStore.load(id);
        if (config == null) throw new NotFoundException("OCG config not found: " + id);
        return config;
    }

    private String agentUrl(String channelId, String baseUrl) {
        return trimTrailingSlash(baseUrl) + "/api/channels/" + channelId + "/v1/chat/completions";
    }

    private String serverUrlFromSandbox() {
        var url = sandboxService.serverUrlFromSandbox();
        if (url != null && !url.isBlank()) return url;
        return publicUrl;
    }

    private String trimTrailingSlash(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
    }

    private String sandboxSessionId(String id) {
        return "ocg-" + id;
    }
}
