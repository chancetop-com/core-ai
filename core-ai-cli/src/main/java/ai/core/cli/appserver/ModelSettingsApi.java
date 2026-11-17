package ai.core.cli.appserver;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.cli.agent.AgentSessionRunnerHelper;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.config.LlmProviderProfile;
import ai.core.cli.config.ProviderWiring;
import ai.core.cli.hub.model.ModelHubClient;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.ReasoningEffort;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Model list / pick / thinking surface. Model identity is the provider type plus the model id: after
 * merging server models with direct providers, the same model name can exist on both sides, so a pick
 * must say which provider it routes to.
 *
 * @author stephen
 */
class ModelSettingsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelSettingsApi.class);
    private static final long SERVER_MODELS_TTL_MS = 60_000L;

    private static String defaultChatModelFor(String providerName) {
        try {
            return LLMProviders.getProviderDefaultChatModel(LLMProviderType.fromName(providerName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Properties loadAgentProperties(Path file) throws IOException {
        var props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }

    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;
    private volatile List<GatewayAvailableModelView> cachedServerModels;
    private volatile long serverModelsFetchedAt;

    ModelSettingsApi(EngineBootstrap bootstrap, EngineSessionRegistry registry) {
        this.bootstrap = bootstrap;
        this.registry = registry;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("WOC_WRITE_ONLY_COLLECTION_LOCAL")
    ObjectNode list() {
        var serverModels = serverModels();
        if (serverModels != null) {
            var profiles = bootstrap.providerStore.profiles();
            var node = Params.object();
            node.put("source", profiles.isEmpty() ? "server" : "mixed");
            var array = node.putArray("models");
            var seen = new HashSet<String>();
            for (var model : serverModels) {
                putModel(array, seen, new ModelRow(model.modelId, model.displayName, model.providerName,
                        LLMProviderType.LITELLM.getName(), "server", model.reasoningEfforts));
            }
            for (var profile : profiles) {
                putDirectModels(array, seen, profile);
            }
            return node;
        }
        var node = Params.object();
        node.put("source", "local");
        var array = node.putArray("models");
        var seen = new HashSet<String>();
        for (var entry : bootstrap.modelRegistry.getAllEntries()) {
            var profile = bootstrap.providerStore.profile(entry.providerType()).orElse(null);
            putModel(array, seen, new ModelRow(entry.model(), null,
                    profile == null ? entry.providerType().getName() : profile.labelOrType(),
                    entry.providerType().getName(), profile == null ? "local" : "direct",
                    profile == null ? null : profile.reasoningLevels));
        }
        return node;
    }

    private void putDirectModels(ArrayNode array, Set<String> seen, LlmProviderProfile profile) {
        for (var model : profile.models) {
            if (model == null || model.isBlank()) continue;
            putModel(array, seen, new ModelRow(model.trim(), null, profile.labelOrType(), profile.type,
                    "direct", profile.reasoningLevels));
        }
        putModel(array, seen, new ModelRow(profile.effectiveDefaultModel(), null, profile.labelOrType(),
                profile.type, "direct", profile.reasoningLevels));
    }

    private void putModel(ArrayNode array, Set<String> seen, ModelRow row) {
        if (row.model() == null || row.model().isBlank()) return;
        var key = row.providerType() + "\u0000" + row.model();
        if (!seen.add(key)) return;
        var item = array.addObject();
        item.put("model", row.model());
        item.put("providerType", row.providerType());
        item.put("source", row.source());
        if (row.displayName() != null && !row.displayName().isBlank()) item.put("displayName", row.displayName());
        if (row.provider() != null && !row.provider().isBlank()) item.put("provider", row.provider());
        if (row.reasoningEfforts() != null && !row.reasoningEfforts().isEmpty()) {
            var efforts = item.putArray("reasoningEfforts");
            row.reasoningEfforts().forEach(efforts::add);
        }
    }

    ObjectNode get(ObjectNode params) {
        var sessionId = Params.optionalText(params, "sessionId");
        if (sessionId != null) {
            var scoped = sessionModel(sessionId);
            if (scoped != null) return scoped;
        }
        var node = Params.object();
        var type = defaultProviderType();
        if (type != null) {
            node.put("provider", type.getName());
            node.put("source", type == LLMProviderType.LITELLM ? "server" : "direct");
            var model = defaultModelFor(type);
            if (model != null && !model.isBlank()) node.put("model", model.trim());
            return node;
        }
        legacyModelGet(node);
        return node;
    }

    private void legacyModelGet(ObjectNode node) {
        try {
            var file = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
            var props = loadAgentProperties(file);
            var active = props.getProperty("active.provider");
            var prefix = active != null && !active.isBlank() ? active : "llm";
            node.put("provider", prefix);
            var model = props.getProperty(prefix + ".model");
            if (model == null || model.isBlank()) model = defaultChatModelFor(prefix);
            if (model != null && !model.isBlank()) node.put("model", model.trim());
        } catch (IOException e) {
            LOGGER.warn("failed to read active model: {}", e.getMessage());
        }
    }

    private LLMProviderType defaultProviderType() {
        return ProviderWiring.resolveDefaultType(bootstrap.providerStore, bootstrap.result.llmProviders,
                bootstrap.props, loggedIn());
    }

    private String defaultModelFor(LLMProviderType type) {
        if (type == LLMProviderType.LITELLM) {
            var stored = bootstrap.providerStore.serverModel();
            if (stored != null && !stored.isBlank()) return stored;
            return bootstrap.props.property("litellm.model")
                    .orElseGet(() -> bootstrap.props.property("llm.model").orElse(null));
        }
        var profile = bootstrap.providerStore.profile(type).orElse(null);
        if (profile != null) return profile.effectiveDefaultModel();
        return bootstrap.props.property(type.getName() + ".model")
                .orElseGet(() -> LLMProviders.getProviderDefaultChatModel(type));
    }

    private ObjectNode sessionModel(String sessionId) {
        var session = registry.find(sessionId);
        if (session != null) {
            var node = Params.object();
            var agent = session.agent();
            node.put("model", agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel());
            var type = bootstrap.result.llmProviders.getProviderType(agent.getLLMProvider());
            if (type != null) {
                node.put("provider", type.getName());
                node.put("source", type == LLMProviderType.LITELLM ? "server" : "direct");
            }
            return node;
        }
        var stored = registry.storedModel(sessionId);
        if (stored == null) return null;
        var node = Params.object();
        node.put("model", stored.model);
        if (stored.provider != null) {
            node.put("provider", stored.provider);
            node.put("source", "litellm".equalsIgnoreCase(stored.provider) ? "server" : "direct");
        }
        return node;
    }

    ObjectNode set(ObjectNode params) {
        var model = Params.requiredText(params, "model");
        var type = resolveType(model, Params.optionalText(params, "provider"));
        var sessionId = Params.optionalText(params, "sessionId");
        var applied = registry.applyModel(model, type, sessionId);
        if (sessionId == null) persistDefaultModel(type, model);
        var node = Params.object();
        node.put("model", model);
        node.put("provider", type.getName());
        var array = node.putArray("appliedTo");
        applied.forEach(array::add);
        var profile = bootstrap.providerStore.profile(type).orElse(null);
        if (profile != null && !profile.models.isEmpty() && !profile.hasModel(model)) {
            node.put("warn", "model is not in this provider's saved model list");
        }
        return node;
    }

    private LLMProviderType resolveType(String model, String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            LLMProviderType type;
            try {
                type = LLMProviderType.fromName(requestedProvider);
            } catch (IllegalArgumentException e) {
                throw RpcException.business("MODEL_UNKNOWN", "unknown provider: " + requestedProvider, e);
            }
            if (bootstrap.result.llmProviders.getProvider(type) == null) {
                throw RpcException.business("MODEL_UNAVAILABLE", "provider is not configured: " + type.getName());
            }
            return type;
        }
        var type = bootstrap.modelRegistry.getProviderType(model);
        if (type == null && loggedIn()) type = LLMProviderType.LITELLM;
        if (type == null) throw RpcException.business("MODEL_UNKNOWN", "model is not in the registry: " + model);
        return type;
    }

    private void persistDefaultModel(LLMProviderType type, String model) {
        if (type == LLMProviderType.LITELLM) {
            bootstrap.providerStore.setDefaultConnection("server");
            bootstrap.providerStore.setServerModel(model);
            if (bootstrap.result.liteLLMProvider != null) bootstrap.result.liteLLMProvider.config.setModel(model);
        } else {
            bootstrap.providerStore.setDefaultConnection(type.getName());
            bootstrap.providerStore.setDefaultModel(type, model);
        }
        bootstrap.result.llmProviders.setDefaultProvider(type);
    }

    ObjectNode thinkingGet(ObjectNode params) {
        var type = thinkingProviderType(params);
        var prefix = type == null ? "llm" : type.getName();
        var profile = type == null ? null : bootstrap.providerStore.profile(type).orElse(null);
        var effort = AgentSessionRunnerHelper.loadReasoningEffortFromExtraBody(prefix, profile == null);
        var node = Params.object();
        node.put("level", effort == null ? "off" : effort.name().toLowerCase(Locale.ROOT));
        node.put("provider", prefix);
        return node;
    }

    ObjectNode thinkingSet(ObjectNode params) {
        var level = Params.requiredText(params, "level").toLowerCase(Locale.ROOT);
        ReasoningEffort effort = "off".equals(level) ? null : AgentSessionRunnerHelper.parseLevel(level);
        if (effort == null && !"off".equals(level)) {
            throw RpcException.invalidParams("invalid level: " + level + " (use none, low, high, max or off)");
        }
        var type = thinkingProviderType(params);
        var prefix = type == null ? "llm" : type.getName();
        var profile = type == null ? null : bootstrap.providerStore.profile(type).orElse(null);
        var internal = effort == null ? null : effort.name().toLowerCase(Locale.ROOT);
        validateEffort(profile, internal);
        var wire = internal == null ? null : (profile == null ? internal : profile.wireEffort(internal));
        var error = AgentSessionRunnerHelper.persistReasoningEffortToExtraBody(prefix, wire);
        if (error != null) throw RpcException.business("CONFIG_WRITE_FAILED", error);
        var node = Params.object();
        node.put("level", effort == null ? "off" : internal);
        node.put("provider", prefix);
        node.put("restartRequired", true);
        return node;
    }

    private void validateEffort(LlmProviderProfile profile, String internal) {
        if (internal == null || profile == null || profile.reasoningLevels.isEmpty()) return;
        if (profile.reasoningLevels.stream().anyMatch(item -> item.equalsIgnoreCase(internal))) return;
        throw RpcException.invalidParams("level " + internal + " is not supported by " + profile.labelOrType()
                + " (use " + String.join(", ", profile.reasoningLevels) + " or off)");
    }

    private LLMProviderType thinkingProviderType(ObjectNode params) {
        var sessionId = Params.optionalText(params, "sessionId");
        if (sessionId != null) {
            var sessionType = sessionProviderType(sessionId);
            if (sessionType != null) return sessionType;
            var storedType = storedProviderType(sessionId);
            if (storedType != null) return storedType;
        }
        var provider = Params.optionalText(params, "provider");
        if (provider != null && !provider.isBlank()) {
            try {
                var type = LLMProviderType.fromName(provider);
                if (bootstrap.result.llmProviders.getProvider(type) != null) return type;
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return defaultProviderType();
    }

    private LLMProviderType sessionProviderType(String sessionId) {
        var session = registry.find(sessionId);
        if (session == null) return null;
        return bootstrap.result.llmProviders.getProviderType(session.agent().getLLMProvider());
    }

    private LLMProviderType storedProviderType(String sessionId) {
        var stored = registry.storedModel(sessionId);
        if (stored == null || stored.provider == null) return null;
        try {
            var type = LLMProviderType.fromName(stored.provider);
            return bootstrap.result.llmProviders.getProvider(type) != null ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<GatewayAvailableModelView> serverModels() {
        var now = System.currentTimeMillis();
        if (serverModelsFetchedAt != 0 && now - serverModelsFetchedAt < SERVER_MODELS_TTL_MS) {
            return cachedServerModels;
        }
        List<GatewayAvailableModelView> fetched = null;
        try {
            var auth = AuthConfig.load();
            if (auth != null && auth.apiKey() != null && auth.serverUrl() != null) {
                fetched = new ModelHubClient(auth.serverUrl(), auth.apiKey(), auth.insecure()).models().models;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to fetch hub models: {}", e.getMessage());
        }
        cachedServerModels = fetched;
        serverModelsFetchedAt = now;
        return fetched;
    }

    private boolean loggedIn() {
        var auth = AuthConfig.load();
        return auth != null && auth.apiKey() != null;
    }

    private record ModelRow(String model, String displayName, String provider, String providerType,
                            String source, List<String> reasoningEfforts) {
    }
}
