package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-session model switches: applies a pick to the live agents and remembers it in the sidecar, so a
 * rebuilt session resumes with its own model instead of the global default.
 *
 * @author stephen
 */
class EngineSessionModels {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineSessionModels.class);

    private static void applyTo(Agent agent, LLMProvider provider, String model) {
        agent.setLlmProvider(provider);
        agent.setModel(model);
        var compression = agent.getCompression();
        if (compression != null) {
            compression.updateModel(provider, model);
        }
    }

    private final LLMProviders providers;
    private final SessionModelStore store;

    EngineSessionModels(LLMProviders providers, Path sessionsDir) {
        this.providers = providers;
        this.store = new SessionModelStore(sessionsDir);
    }

    /** Applies the pick to every target agent; a sessionId makes it that session's remembered model. */
    List<String> applyModel(List<EngineSession> targets, String model, LLMProviderType type, String sessionId) {
        var provider = providers.getProvider(type);
        if (provider == null) {
            throw RpcException.business("MODEL_UNAVAILABLE", "provider is not configured: " + type.getName());
        }
        for (var session : targets) {
            applyTo(session.agent(), provider, model);
        }
        if (sessionId != null) {
            store.set(sessionId, model, type.getName());
        }
        return targets.stream().map(EngineSession::id).toList();
    }

    /** A session rebuilds with the model the user picked for it, when that pick's provider is configured. */
    void applyStored(String sessionId, Agent agent) {
        var stored = store.model(sessionId).orElse(null);
        if (stored == null) {
            return;
        }
        var provider = providers.getProviderTypes().stream()
                .filter(type -> type.getName().equalsIgnoreCase(stored.provider))
                .findFirst()
                .map(providers::getProvider)
                .orElse(null);
        if (provider == null) {
            LOGGER.warn("session {} keeps the default model: stored provider {} is not configured",
                    sessionId, stored.provider);
            return;
        }
        applyTo(agent, provider, stored.model);
    }

    SessionModelStore.Entry stored(String sessionId) {
        return store.model(sessionId).orElse(null);
    }

    void remove(String sessionId) {
        store.remove(sessionId);
    }
}
