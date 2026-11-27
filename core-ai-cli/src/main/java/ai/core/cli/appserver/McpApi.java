package ai.core.cli.appserver;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientService;
import ai.core.mcp.client.McpCommandSafety;
import ai.core.mcp.client.McpSecretExpander;
import ai.core.mcp.client.McpServerConfig;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Desktop-managed MCP surface of the app-server engine.
 *
 * The desktop owns definitions, secrets and enablement; this class only applies what the host
 * pushes into the live manager. Every entry point is cheap: connection work happens on the warmup
 * and probe executors, never on the app-server dispatch thread.
 *
 * @author stephen
 */
final class McpApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpApi.class);
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> RESERVED_SECRET_PREFIXES = List.of("CORE_AI_", "MCP_");
    private static final long QUIET_FINALIZE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long BOOT_FINALIZE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static void closeQuietly(McpClientService service) {
        if (service == null) return;
        try {
            service.close();
        } catch (RuntimeException e) {
            LOGGER.debug("failed to close an MCP probe client: {}", e.getMessage());
        }
    }

    private static String configSignature(Map<String, Map<String, Object>> servers) {
        var ordered = new TreeMap<String, String>();
        servers.forEach((name, config) -> ordered.put(name, JsonUtil.toJson(new TreeMap<>(config))));
        return ordered.toString();
    }

    private static String secretSignature(Map<String, Map<String, String>> secrets) {
        var ordered = new TreeMap<String, String>();
        for (var entry : secrets.entrySet()) {
            ordered.put(entry.getKey(), new TreeMap<>(entry.getValue()).toString());
        }
        return ordered.toString();
    }

    private static String digest(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private final EngineBootstrap bootstrap;
    private final Supplier<NotificationSink> sinkSupplier;
    private final Runnable startScheduler;
    private final McpClientManager manager;
    private final ScheduledExecutorService watchdog;
    private final ExecutorService probeExecutor;
    private final Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpClientService> probes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> probeCancelled = new ConcurrentHashMap<>();
    /** Servers the desktop pushed but this engine had to skip; one bad entry never disables the rest. */
    private final Map<String, String> rejectedServers = new ConcurrentHashMap<>();
    private final AtomicBoolean configLoaded = new AtomicBoolean();
    private final AtomicBoolean secretsLoaded = new AtomicBoolean();
    private final AtomicBoolean finalized = new AtomicBoolean();
    private final AtomicBoolean warmupTriggered = new AtomicBoolean();
    private volatile Map<String, Map<String, Object>> rawServers = Map.of();
    private volatile String configSignature;
    private volatile String secretsSignature;
    private volatile long lastRpcNanos;
    private volatile long firstInitializeNanos;

    McpApi(EngineBootstrap bootstrap, Supplier<NotificationSink> sinkSupplier, Runnable startScheduler) {
        this.bootstrap = bootstrap;
        this.sinkSupplier = sinkSupplier;
        this.startScheduler = startScheduler;
        this.manager = bootstrap.result.mcpClientManager;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "mcp-finalize-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.probeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "mcp-probe");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleWithFixedDelay(this::checkFallbackFinalize, 1, 1, TimeUnit.SECONDS);
    }

    Map<String, Function<ObjectNode, JsonNode>> handlers() {
        return Map.of(
                "mcp/config", this::config,
                "mcp/secrets", this::secrets,
                "mcp/finalize", params -> finalizeConfig(),
                "mcp/status", params -> status(),
                "mcp/probe/start", this::probeStart,
                "mcp/probe/cancel", this::probeCancel);
    }

    void noteInitialize() {
        if (firstInitializeNanos == 0) firstInitializeNanos = System.nanoTime();
    }

    private void noteRpc() {
        lastRpcNanos = System.nanoTime();
    }

    private void checkFallbackFinalize() {
        if (finalized.get()) return;
        var now = System.nanoTime();
        var quiet = lastRpcNanos != 0 && now - lastRpcNanos >= QUIET_FINALIZE_NANOS;
        var boot = firstInitializeNanos != 0 && now - firstInitializeNanos >= BOOT_FINALIZE_NANOS;
        if (quiet || boot) {
            LOGGER.info("mcp: fallback finalize (quiet={}, boot={})", quiet, boot);
            finalizeNow();
        }
    }

    private JsonNode config(ObjectNode params) {
        noteRpc();
        requireTrusted();
        var serversNode = params.get("servers");
        if (serversNode == null || !serversNode.isArray()) {
            throw RpcException.invalidParams("servers must be an array");
        }
        var raws = new LinkedHashMap<String, Map<String, Object>>();
        var rejected = new LinkedHashMap<String, String>();
        collectServers(serversNode, raws, rejected);
        var signature = digest(configSignature(raws));
        if (configLoaded.get()) {
            if (!signature.equals(configSignature)) {
                throw RpcException.business("MCP_CONFIG_ALREADY_LOADED",
                        "MCP configuration was already applied for this engine; restart the engine to change it");
            }
            return status();
        }
        if (finalized.get()) {
            throw RpcException.business("MCP_CONFIG_ALREADY_LOADED",
                    "MCP finalization already completed for this engine; restart the engine to change it");
        }
        rawServers = Map.copyOf(raws);
        rejectedServers.clear();
        rejectedServers.putAll(rejected);
        if (!rejected.isEmpty()) {
            LOGGER.warn("mcp: skipped {} invalid server definition(s): {}", rejected.size(), rejected.keySet());
        }
        configSignature = signature;
        configLoaded.set(true);
        return status();
    }

    private void collectServers(JsonNode serversNode, Map<String, Map<String, Object>> raws,
                                Map<String, String> rejected) {
        for (var node : serversNode) {
            var name = node.path("name").asText("").strip();
            if (!SERVER_NAME.matcher(name).matches()) {
                rejected.put(name.isEmpty() ? "(unnamed)" : name, "invalid MCP server name");
                continue;
            }
            var configNode = node.get("config");
            if (configNode == null || !configNode.isObject()) {
                rejected.put(name, "no config object");
                continue;
            }
            @SuppressWarnings("unchecked")
            var raw = (Map<String, Object>) JsonUtil.fromJson(Map.class, configNode.toString());
            try {
                validateDefinition(name, raw);
            } catch (RpcException e) {
                rejected.put(name, e.getMessage());
                continue;
            }
            raws.put(name, raw);
        }
    }

    private void validateDefinition(String name, Map<String, Object> raw) {
        try {
            var expanded = McpSecretExpander.expand(name, raw, Map.of());
            McpCommandSafety.validate(name, expanded);
            McpServerConfig.fromMap(name, expanded);
        } catch (McpSecretExpander.MissingSecretException e) {
            // Placeholders are legal here; mcp/secrets resolves them and re-runs the safety checks.
            validateStructure(name, raw);
        } catch (McpCommandSafety.UnsafeHeaderException e) {
            throw RpcException.business("MCP_HEADER_INVALID", e.getMessage(), e);
        } catch (McpCommandSafety.UnsafeCommandException e) {
            throw RpcException.business("MCP_ARG_UNSAFE", e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("MCP_CONFIG_INVALID",
                    "invalid MCP server '" + name + "': " + e.getMessage(), e);
        }
    }

    private void validateStructure(String name, Map<String, Object> raw) {
        try {
            McpServerConfig.fromMap(name, raw);
        } catch (McpCommandSafety.UnsafeHeaderException e) {
            throw RpcException.business("MCP_HEADER_INVALID", e.getMessage(), e);
        } catch (McpCommandSafety.UnsafeCommandException e) {
            throw RpcException.business("MCP_ARG_UNSAFE", e.getMessage(), e);
        } catch (RuntimeException e) {
            throw RpcException.business("MCP_CONFIG_INVALID",
                    "invalid MCP server '" + name + "': " + e.getMessage(), e);
        }
    }

    private JsonNode secrets(ObjectNode params) {
        noteRpc();
        requireTrusted();
        if (!configLoaded.get()) {
            throw RpcException.business("MCP_CONFIG_NOT_LOADED", "mcp/config must be called before mcp/secrets");
        }
        var secretsNode = params.get("secrets");
        Map<String, Map<String, String>> incoming = new LinkedHashMap<>();
        if (secretsNode != null && secretsNode.isObject()) {
            for (var entry : secretsNode.properties()) {
                var serverName = entry.getKey();
                if (!rawServers.containsKey(serverName)) {
                    LOGGER.warn("mcp: ignoring secrets for unknown server '{}'", serverName);
                    continue;
                }
                if (!entry.getValue().isObject()) {
                    throw RpcException.business("MCP_CONFIG_INVALID",
                            "secrets for '" + serverName + "' must be an object");
                }
                var values = new LinkedHashMap<String, String>();
                for (var value : entry.getValue().properties()) {
                    validateSecret(serverName, value.getKey(), value.getValue());
                    values.put(value.getKey(), value.getValue().asText(""));
                }
                incoming.put(serverName, Map.copyOf(values));
            }
        }
        var signature = digest(secretSignature(incoming));
        if (secretsLoaded.get()) {
            if (!signature.equals(secretsSignature)) {
                throw RpcException.business("MCP_SECRETS_ALREADY_LOADED",
                        "MCP secrets were already applied for this engine; restart the engine to change them");
            }
            applyDefinitions(incoming);
            return status();
        }
        applyDefinitions(incoming);
        secretsSignature = signature;
        secretsLoaded.set(true);
        return status();
    }

    private void validateSecret(String serverName, String name, JsonNode value) {
        if (!SECRET_NAME.matcher(name).matches()) {
            throw RpcException.business("MCP_SECRET_NAME_INVALID",
                    "invalid secret name for MCP server '" + serverName + "': " + name);
        }
        for (var prefix : RESERVED_SECRET_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw RpcException.business("MCP_SECRET_NAME_RESERVED",
                        "reserved secret name for MCP server '" + serverName + "': " + name);
            }
        }
        if (!value.isTextual()) {
            throw RpcException.business("MCP_CONFIG_INVALID",
                    "secret " + name + " of MCP server '" + serverName + "' must be a string");
        }
    }

    private void applyDefinitions(Map<String, Map<String, String>> incoming) {
        for (var entry : rawServers.entrySet()) {
            var serverName = entry.getKey();
            var secrets = incoming.getOrDefault(serverName, Map.of());
            McpServerConfig config;
            try {
                config = McpServerConfig.fromMap(serverName, entry.getValue(), secrets);
            } catch (McpSecretExpander.MissingSecretException | McpCommandSafety.UnsafeHeaderException
                     | McpCommandSafety.UnsafeCommandException e) {
                rejectedServers.put(serverName, e.getMessage());
                continue;
            } catch (RuntimeException e) {
                rejectedServers.put(serverName, "invalid MCP server: " + e.getMessage());
                continue;
            }
            serverConfigs.put(serverName, config);
            if (!manager.hasServer(serverName)) manager.addServer(config);
        }
    }

    private JsonNode finalizeConfig() {
        noteRpc();
        finalizeNow();
        return status();
    }

    private void finalizeNow() {
        if (!finalized.compareAndSet(false, true)) return;
        if (!bootstrap.workspaceTrusted) {
            LOGGER.info("mcp: finalize on a restricted workspace; MCP stays disabled");
            return;
        }
        startScheduler.run();
        if (configLoaded.get() && secretsLoaded.get() && warmupTriggered.compareAndSet(false, true)) {
            manager.warmupAsync();
        }
    }

    private JsonNode status() {
        noteRpc();
        var node = Params.object();
        node.put("trusted", bootstrap.workspaceTrusted);
        node.put("configLoaded", configLoaded.get());
        node.put("secretsLoaded", secretsLoaded.get());
        node.put("finalized", finalized.get());
        var servers = node.putArray("servers");
        for (var entry : new TreeMap<>(manager.getAllStates()).entrySet()) {
            var server = servers.addObject();
            server.put("name", entry.getKey());
            server.put("state", entry.getValue().name());
            var config = serverConfigs.get(entry.getKey());
            if (config != null) server.put("transport", config.getTransportType().name());
            server.put("toolCount", manager.toolSnapshot(entry.getKey()).orElse(List.of()).size());
        }
        var conflicts = node.putArray("conflicts");
        for (var conflict : manager.toolConflicts()) {
            var item = conflicts.addObject();
            item.put("tool", conflict.tool());
            item.put("winner", conflict.winner());
            var skipped = item.putArray("skipped");
            conflict.skipped().forEach(skipped::add);
        }
        var invalid = node.putObject("invalidTools");
        manager.invalidToolNames().forEach(invalid::put);
        var rejected = node.putObject("rejected");
        rejectedServers.forEach(rejected::put);
        return node;
    }

    private JsonNode probeStart(ObjectNode params) {
        noteRpc();
        requireTrusted();
        if (!secretsLoaded.get()) {
            throw RpcException.business("MCP_CONFIG_NOT_LOADED", "save and load the server before testing it");
        }
        var name = Params.requiredText(params, "name");
        var config = serverConfigs.get(name);
        if (config == null) {
            var reason = rejectedServers.get(name);
            throw RpcException.business("MCP_CONFIG_INVALID", reason != null
                    ? "MCP server " + name + " was rejected: " + reason
                    : "unknown MCP server: " + name);
        }
        var probeId = UUID.randomUUID().toString();
        probeCancelled.remove(probeId);
        probeExecutor.submit(() -> runProbe(probeId, name, config));
        return Params.object().put("probeId", probeId);
    }

    private void runProbe(String probeId, String name, McpServerConfig config) {
        var started = System.currentTimeMillis();
        var result = Params.object();
        result.put("probeId", probeId);
        try (var service = new McpClientService(config.copyForProbe())) {
            probes.put(probeId, service);
            var tools = service.listTools();
            if (probeCancelled.containsKey(probeId)) return;
            var toolArray = result.putArray("tools");
            for (var tool : tools) {
                var item = toolArray.addObject();
                item.put("name", tool.name());
                item.put("description", tool.description());
            }
            result.put("ok", true);
        } catch (Exception e) {
            if (probeCancelled.containsKey(probeId)) return;
            result.put("ok", false);
            result.put("error", "MCP probe failed: " + e.getMessage());
        } finally {
            probes.remove(probeId);
            probeCancelled.remove(probeId);
            result.put("server", name);
            result.put("elapsedMs", System.currentTimeMillis() - started);
            sinkSupplier.get().notify("mcp/probe/result", result);
        }
    }

    private JsonNode probeCancel(ObjectNode params) {
        noteRpc();
        var probeId = Params.requiredText(params, "probeId");
        probeCancelled.put(probeId, Boolean.TRUE);
        var service = probes.remove(probeId);
        if (service != null) probeExecutor.submit(() -> closeQuietly(service));
        var node = Params.object();
        node.put("cancelled", service != null);
        return node;
    }

    private void requireTrusted() {
        if (bootstrap.workspaceTrusted) return;
        throw RpcException.business("MCP_RESTRICTED",
                "this workspace is not trusted; MCP servers cannot be loaded",
                "key", bootstrap.workspaceTrust.key());
    }

    void close() {
        watchdog.shutdownNow();
        probeExecutor.shutdownNow();
        for (var service : new ArrayList<>(probes.values())) closeQuietly(service);
        probes.clear();
    }
}
