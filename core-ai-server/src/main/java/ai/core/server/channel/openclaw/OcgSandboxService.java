package ai.core.server.channel.openclaw;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.sandbox.SandboxClient;
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

/**
 * @author stephen
 */
public class OcgSandboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgSandboxService.class);
    private static final String CONFIG_PATH = "/tmp/ocg.json";
    private static final String OPENCLAW_CHANNEL_TYPE = "openclaw";
    private static final int DEFAULT_CALLBACK_PORT = 3457;
    private static final Set<String> GATEWAY_CONFIG_KEYS = Set.of("agentUrl", "model", "apiKey", "verbose", "async", "callbackHost", "callbackPort", "callbackSecret", "callbackTokenTTL", "channels");

    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    SandboxService sandboxService;

    public String publicUrl;

    public synchronized void startSandbox(String ocgConfigId) {
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
        try {
            sandboxService.ensureSandboxReady(sessionId);
            var sandboxIp = sandbox.ip();
            if (sandboxIp == null || sandboxIp.isBlank()) throw new BadRequestException("sandbox ip is unavailable");
            var ocgJson = buildRuntimeConfig(config, agentUrl(config.channelId, serverUrlFromSandbox()), sandboxIp);
            sandbox.uploadFile(CONFIG_PATH, ocgJson.getBytes(StandardCharsets.UTF_8));
            runCommand(sandbox, "OCG_CONFIG_PATH=" + CONFIG_PATH + " nohup ocg start > /tmp/ocg.log 2>&1 &", 10);
            runCommand(sandbox, "sleep 1; " + ocgProcessCheckCommand(), 30);
            config.sandboxId = sandbox.getId();
            config.sandboxIp = sandboxIp;
            config.updatedAt = ZonedDateTime.now();
            ocgConfigStore.store(config);
            LOGGER.info("OCG sandbox started, id={}, sandboxId={}, ip={}", config.id, config.sandboxId, config.sandboxIp);
        } catch (RuntimeException e) {
            sandboxService.releaseSandbox(sessionId);
            throw e;
        }
    }

    public synchronized void stopSandbox(String ocgConfigId) {
        var config = loadConfig(ocgConfigId);
        if (config.sandboxId != null && !config.sandboxId.isBlank()) {
            sandboxService.releaseSandbox(sandboxSessionId(config.id));
        }
        ocgConfigStore.clearSandbox(config.id);
        LOGGER.info("OCG sandbox stopped, id={}", config.id);
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

    public void recoverOnStartup() {
        for (var config : ocgConfigStore.allWithSandbox()) {
            var attached = sandboxService.attachSandbox(config.sandboxId, buildSandboxConfig(), sandboxSessionId(config.id), "system", true);
            if (attached != null) {
                LOGGER.info("OCG sandbox recovered, id={}, sandboxId={}, ip={}", config.id, config.sandboxId, config.sandboxIp);
            } else {
                ocgConfigStore.clearSandbox(config.id);
                LOGGER.warn("OCG sandbox lost, id={}, sandboxId={}", config.id, config.sandboxId);
            }
        }
    }

    public void healthCheck() {
        for (var config : ocgConfigStore.allWithSandbox()) {
            var sessionId = sandboxSessionId(config.id);
            var sandbox = sandboxService.getSandbox(sessionId);
            if (sandbox == null) {
                LOGGER.warn("OCG sandbox is not attached, id={}, sandboxId={}", config.id, config.sandboxId);
                continue;
            }
            try {
                sandboxService.renewSandbox(sessionId);
                runCommand(sandbox, ocgProcessCheckCommand(), 15);
            } catch (RuntimeException e) {
                LOGGER.warn("OCG health check failed, id={}, sandboxId={}: {}", config.id, config.sandboxId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public String buildRuntimeConfig(OcgConfigView config, String agentUrl, String sandboxIp) {
        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JsonUtil.fromJson(Map.class, config.configJson);
        } catch (Exception e) {
            throw new BadRequestException("invalid configJson: " + e.getMessage());
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
        runtimeConfig.put("async", true);
        runtimeConfig.put("callbackHost", sandboxIp);
        runtimeConfig.putIfAbsent("callbackPort", DEFAULT_CALLBACK_PORT);
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
        config.enabled = true;
        config.networkEnabled = true;
        config.timeoutSeconds = 86_400;
        config.memoryLimitMb = 1024;
        config.cpuLimitMillicores = 500;
        return config;
    }

    private String ocgProcessCheckCommand() {
        return "pgrep -af 'node .*openclaw-channel-gateway.*dist/cli.js start' || (test -f /tmp/ocg.log && tail -80 /tmp/ocg.log; exit 1)";
    }

    private void runCommand(Sandbox sandbox, String command, int timeoutSeconds) {
        runCommand(sandbox, command, timeoutSeconds, false);
    }

    private void runCommand(Sandbox sandbox, String command, int timeoutSeconds, boolean runInBackground) {
        var client = new SandboxClient(sandbox.ip(), sandbox.port(), timeoutSeconds);
        var result = client.execute(ShellCommandTool.TOOL_NAME, JsonUtil.toJson(Map.of(
                "command", command,
                "timeout", timeoutSeconds * 1000,
                "run_in_background", runInBackground)), null);
        if (!result.isCompleted()) {
            throw new RuntimeException(result.getResult());
        }
        var output = result.getResult();
        if (output != null && (output.startsWith("Command exited with code") || output.startsWith("Command execution failed") || output.startsWith("Command timed out"))) {
            throw new RuntimeException(output);
        }
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
