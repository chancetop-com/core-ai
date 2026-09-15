package ai.core.server.sandboxhub;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.sandboxhub.SandboxHubGroup;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.api.server.sandboxhub.SandboxHubToolSummary;
import ai.core.sandbox.SandboxConstants;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.tools.ToolActivationTool;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The session agent's own tool set, re-projected into what a script inside the sandbox may call.
 * <p>
 * Derived per request from the materialized dispatch map, so the catalog is exactly the agent's
 * current capabilities: anything the agent cannot call never appears, {@code DEFERRED} tools stay
 * callable (the script is not the model and needs no discovery round), and {@code HIDDEN} or
 * runtime-owned tools are listed as not callable instead of silently missing.
 * <p>
 * {@code ref_id} follows the sandbox contract: {@code mcp:{server}:{tool}} for an MCP tool,
 * {@code {app}/{service}/{operation}} for a Service API operation, and the plain name for the
 * singleton kinds (llm_call / agent / builtin). {@code path} is the ref without its kind prefix and
 * {@code name} is the path with every non-alphanumeric character replaced by {@code _}, which is how
 * a script addresses a tool.
 *
 * @author xander
 */
public final class SandboxHubCatalog {
    public static final String KIND_MCP = "mcp";
    public static final String KIND_API = "api";
    public static final String KIND_LLM_CALL = "llm_call";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_BUILTIN = "builtin";
    /** Longest default any kind gets; a caller waiting on an RPC sizes its own timeout from this. */
    public static final int AGENT_TIMEOUT_SECONDS = 300;

    private static final String MCP_PROVIDER_PREFIX = "mcp:";
    private static final String API_PROVIDER_PREFIX = ToolProvider.API_TOOLS + ":";
    private static final String LLM_CALL_PROVIDER_PREFIX = "llm-call:";
    private static final String LLM_CALL_SOURCE_TYPE = "llm-call";
    private static final String API_OPERATION_PREFIX = "api-operation:";
    private static final String API_SERVICE_PREFIX = "api-service:";
    private static final String API_APP_PREFIX = "api-app:";
    private static final char PATH_SEPARATOR_CHAR = '/';
    private static final String PATH_SEPARATOR = String.valueOf(PATH_SEPARATOR_CHAR);
    private static final int DEFAULT_TIMEOUT_SECONDS = (int) (SandboxConstants.DEFAULT_TOOL_TIMEOUT_MS / 1000);

    /**
     * @param context the session's execution context; a context without a tool registry yields an empty catalog
     */
    public static SandboxHubCatalog of(ExecutionContext context) {
        var registry = context.getToolRegistry();
        if (registry == null) return new SandboxHubCatalog(List.of());
        var materialization = registry.materialize(context);
        var providerIndex = materialization.getToolProviderIndex();
        var entries = new ArrayList<Entry>();
        for (var tool : materialization.getDispatchMap().values()) {
            if (excluded(tool)) continue;
            entries.add(entry(tool, providerIndex.get(tool.getName())));
        }
        entries.sort(Comparator.comparing(Entry::kind).thenComparing(Entry::path));
        return new SandboxHubCatalog(entries);
    }

    /** Runtime-owned tools: the script already runs inside the sandbox and calls them through the runtime. */
    private static boolean excluded(ToolCall tool) {
        return SandboxConstants.INTERCEPTED_TOOLS.contains(tool.getName());
    }

    private static Entry entry(ToolCall tool, String providerId) {
        var kind = kindOf(tool, providerId);
        var refId = refIdOf(tool, kind, providerId);
        var path = pathOf(kind, refId);
        var exposure = tool.getExposure().name().toLowerCase(Locale.ROOT);
        var callable = tool.getExposure() != ToolExposure.HIDDEN && !ToolActivationTool.TOOL_NAME.equals(tool.getName());
        return new Entry(scriptName(path), kind, groupOf(kind, refId, providerId), path, refId,
                tool.getDescription(), exposure, callable, tool);
    }

    private static String kindOf(ToolCall tool, String providerId) {
        if (tool.isSubAgent()) return KIND_AGENT;
        if (providerId != null) {
            if (providerId.startsWith(MCP_PROVIDER_PREFIX)) return KIND_MCP;
            if (providerId.startsWith(API_PROVIDER_PREFIX)) return KIND_API;
            if (providerId.startsWith(LLM_CALL_PROVIDER_PREFIX)) return KIND_LLM_CALL;
        }
        return LLM_CALL_SOURCE_TYPE.equals(tool.getSourceType()) ? KIND_LLM_CALL : KIND_BUILTIN;
    }

    private static String refIdOf(ToolCall tool, String kind, String providerId) {
        return switch (kind) {
            case KIND_MCP -> mcpRefId(tool, providerId);
            case KIND_API -> apiRefId(tool, providerId);
            default -> tool.getName();
        };
    }

    private static String mcpRefId(ToolCall tool, String providerId) {
        var server = providerId == null ? "" : providerId.substring(MCP_PROVIDER_PREFIX.length());
        var toolName = localMcpToolName(server, tool.getName());
        return MCP_PROVIDER_PREFIX + server + ":" + toolName;
    }

    // a provider that namespaces its tools prefixes them with the server slug; the ref keeps the raw tool name only
    private static String localMcpToolName(String server, String toolName) {
        var prefix = scriptName(server) + "_";
        return toolName.startsWith(prefix) ? toolName.substring(prefix.length()) : toolName;
    }

    private static String apiRefId(ToolCall tool, String providerId) {
        var ref = providerId == null ? "" : providerId.substring(API_PROVIDER_PREFIX.length());
        if (ref.startsWith(API_OPERATION_PREFIX)) {
            return ref.substring(API_OPERATION_PREFIX.length()).replace(':', '/');
        }
        if (ref.startsWith(API_SERVICE_PREFIX)) {
            return ref.substring(API_SERVICE_PREFIX.length()).replace(':', '/');
        }
        if (ref.startsWith(API_APP_PREFIX)) {
            return ref.substring(API_APP_PREFIX.length());
        }
        var namespace = tool.getNamespace();
        return namespace == null || namespace.isBlank() ? tool.getName() : namespace + PATH_SEPARATOR + tool.getName();
    }

    private static String groupOf(String kind, String refId, String providerId) {
        return switch (kind) {
            case KIND_MCP -> providerId == null ? null : providerId.substring(MCP_PROVIDER_PREFIX.length());
            case KIND_API -> {
                var separator = refId.indexOf(PATH_SEPARATOR_CHAR);
                yield separator < 0 ? refId : refId.substring(0, separator);
            }
            default -> null;
        };
    }

    private static String pathOf(String kind, String refId) {
        if (KIND_MCP.equals(kind)) {
            var parts = refId.split(":", 3);
            return parts.length < 3 ? refId : parts[1] + PATH_SEPARATOR + parts[2];
        }
        return refId;
    }

    private static String scriptName(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** Catalog headers: one entry per named group (MCP server, API app) plus one per singleton kind. */
    private static SandboxHubGroup group(Entry entry) {
        var group = new SandboxHubGroup();
        group.kind = entry.kind();
        group.group = entry.group();
        group.path = entry.group();
        group.count = 0;
        return group;
    }

    public static SandboxHubToolSummary summary(SandboxHubToolDetail detail) {
        var summary = new SandboxHubToolSummary();
        summary.name = detail.name;
        summary.kind = detail.kind;
        summary.group = detail.group;
        summary.path = detail.path;
        summary.refId = detail.refId;
        summary.description = detail.description;
        summary.exposure = detail.exposure;
        return summary;
    }

    public static SandboxHubToolDetail detail(Entry entry, int defaultTimeoutSeconds) {
        var detail = new SandboxHubToolDetail();
        detail.name = entry.name();
        detail.kind = entry.kind();
        detail.group = entry.group();
        detail.path = entry.path();
        detail.refId = entry.refId();
        detail.description = entry.description();
        detail.exposure = entry.exposure();
        detail.callable = entry.callable();
        detail.timeoutSeconds = defaultTimeoutSeconds;
        detail.inputSchema = JsonUtil.toJson(entry.tool().toJsonSchema());
        return detail;
    }

    /** Sub-agents run a whole turn of their own, so they get the long default rather than the tool default. */
    public static int defaultTimeoutSeconds(Entry entry) {
        return KIND_AGENT.equals(entry.kind()) ? AGENT_TIMEOUT_SECONDS : DEFAULT_TIMEOUT_SECONDS;
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private SandboxHubCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var index = new LinkedHashMap<String, Entry>();
        for (var entry : entries) {
            index.putIfAbsent(entry.name(), entry);
        }
        this.byName = Map.copyOf(index);
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * The catalog in the form that travels between replicas: plain DTOs, so a pod that does not own
     * the session can still answer reads after asking the owner for it.
     */
    public List<SandboxHubToolDetail> details() {
        var details = new ArrayList<SandboxHubToolDetail>(entries.size());
        for (var entry : entries) {
            details.add(detail(entry, defaultTimeoutSeconds(entry)));
        }
        return details;
    }

    public int size() {
        return entries.size();
    }

    /** Lookup by script-facing name, then by the underlying tool name for callers that kept the agent-side spelling. */
    public Entry find(String name) {
        if (name == null) return null;
        var entry = byName.get(name);
        if (entry != null) return entry;
        for (var candidate : entries) {
            if (candidate.tool().getName().equals(name)) return candidate;
        }
        return null;
    }

    public List<SandboxHubGroup> groups() {
        var counts = new LinkedHashMap<String, SandboxHubGroup>();
        for (var entry : entries) {
            var key = entry.kind() + "\u0000" + entry.group();
            var group = counts.computeIfAbsent(key, ignored -> group(entry));
            group.count = group.count + 1;
        }
        return List.copyOf(counts.values());
    }

    /** One callable capability, carrying the tool instance so callers can invoke it without a second lookup. */
    public record Entry(String name, String kind, String group, String path, String refId, String description,
                        String exposure, boolean callable, ToolCall tool) {
    }
}
