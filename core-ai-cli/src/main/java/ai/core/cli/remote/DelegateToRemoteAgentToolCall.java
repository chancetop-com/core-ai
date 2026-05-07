package ai.core.cli.remote;

import ai.core.a2a.InMemoryRemoteAgentContextStore;
import ai.core.a2a.RemoteAgentContextStore;
import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.A2ARemoteAgentToolCall;
import ai.core.utils.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Delegates a task to a selected remote agent through A2A.
 *
 * @author xander
 */
public class DelegateToRemoteAgentToolCall extends ToolCall {
    public static final String TOOL_NAME = "delegate_to_remote_agent";

    public static Builder builder() {
        return new Builder();
    }

    private static ToolCall defaultDelegate(A2ARemoteAgentConfig config, RemoteAgentContextStore contextStore) {
        var connection = new java.util.concurrent.atomic.AtomicReference<A2ARemoteConnector.Connection>();
        return A2ARemoteAgentToolCall.builder()
                .descriptor(config.toDescriptor())
                .clientFactory(() -> connect(config, connection).client())
                .contextStore(contextStore)
                .build();
    }

    private static A2ARemoteConnector.Connection connect(A2ARemoteAgentConfig config,
                                                          java.util.concurrent.atomic.AtomicReference<A2ARemoteConnector.Connection> connection) {
        var current = connection.get();
        if (current != null) return current;
        var remoteConfig = new RemoteConfig(config.url, config.resolvedApiKey(), config.agentId, config.name);
        var connected = new A2ARemoteConnector().connect(remoteConfig);
        if (connection.compareAndSet(null, connected)) return connected;
        return connection.get();
    }

    private RemoteAgentCatalog catalog;
    private Function<A2ARemoteAgentConfig, ToolCall> delegateFactory;
    private final Map<String, ToolCall> delegates = new ConcurrentHashMap<>();

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var agentId = getStringValue(args, "agent_id");
        var task = getStringValue(args, "task");
        var validation = validate(agentId, task);
        if (validation != null) return validation.withToolName(getName());
        var entry = catalog.find(agentId);
        if (entry == null) {
            return ToolCallResult.failed("Remote agent not found: " + agentId
                    + ". Use search_remote_agents to find available remote agents.").withToolName(getName());
        }
        var result = delegate(entry).execute(JsonUtil.toJson(Map.of("query", task)), context);
        return result.withToolName(getName())
                .withStats("remote_catalog_agent_id", entry.id())
                .withStats("remote_agent_name", entry.name())
                .withStats("remote_server_id", entry.serverId());
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    private ToolCallResult validate(String agentId, String task) {
        if (agentId == null || agentId.isBlank()) return ToolCallResult.failed("Parameter 'agent_id' is required.");
        if (task == null || task.isBlank()) return ToolCallResult.failed("Parameter 'task' is required.");
        return null;
    }

    private ToolCall delegate(RemoteAgentCatalogEntry entry) {
        return delegates.computeIfAbsent(entry.id(), ignored -> delegateFactory.apply(entry.config()));
    }

    public static class Builder extends ToolCall.Builder<Builder, DelegateToRemoteAgentToolCall> {
        private RemoteAgentCatalog catalog;
        private RemoteAgentContextStore contextStore;
        private Function<A2ARemoteAgentConfig, ToolCall> delegateFactory;

        public Builder catalog(RemoteAgentCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        public Builder contextStore(RemoteAgentContextStore contextStore) {
            this.contextStore = contextStore;
            return this;
        }

        Builder delegateFactory(Function<A2ARemoteAgentConfig, ToolCall> delegateFactory) {
            this.delegateFactory = delegateFactory;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public DelegateToRemoteAgentToolCall build() {
            if (catalog == null) throw new IllegalArgumentException("catalog is required");
            var store = contextStore != null ? contextStore : new InMemoryRemoteAgentContextStore();
            name(TOOL_NAME);
            description("""
                    Delegate a task to a selected remote server-side agent. Use search_remote_agents first to choose
                    an agent_id. Remote agent internals, tools, MCP servers, sandbox, and credentials are not exposed.
                    """);
            parameters(List.of(
                    ToolCallParameter.builder()
                            .name("agent_id")
                            .description("Remote agent catalog id returned by search_remote_agents.")
                            .type(ToolCallParameterType.STRING)
                            .classType(String.class)
                            .required(true)
                            .build(),
                    ToolCallParameter.builder()
                            .name("task")
                            .description("Task or instruction to delegate to the remote agent.")
                            .type(ToolCallParameterType.STRING)
                            .classType(String.class)
                            .required(true)
                            .build()
            ));
            var tool = new DelegateToRemoteAgentToolCall();
            super.build(tool);
            tool.catalog = catalog;
            tool.delegateFactory = delegateFactory != null ? delegateFactory : config -> defaultDelegate(config, store);
            return tool;
        }
    }
}
