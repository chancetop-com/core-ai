package ai.core.tool.tools;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.subagent.SubagentModelResolver;
import ai.core.agent.subagent.SubagentModelUnavailableException;
import ai.core.agent.subagent.SubagentThread;
import ai.core.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the model a subagent run uses, following one deterministic precedence:
 * call-site model, thread model, profile model, per-type config, then the parent model.
 *
 * @author stephen
 */
final class SubagentModelSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentModelSelector.class);
    private static final int MAX_AVAILABLE_MODELS_IN_ERROR = 10;

    static ModelSelection select(String requestedModel, SubagentThread thread, AgentProfile profile,
                                 SubAgentConfig config, ExecutionContext context) {
        var resolver = context.getSubagentModelResolver();
        if (!blank(requestedModel)) {
            return resolveStrictly(requestedModel.trim(), resolver, context);
        }
        if (thread != null && !blank(thread.model())) {
            return resolveThread(thread, config, resolver, context);
        }
        if (profile != null && !blank(profile.model())) {
            return resolveWithFallback(profile.model().trim(), config, resolver, context, "PROFILE");
        }
        if (config != null && !blank(config.model())) {
            return resolveWithFallback(config.model().trim(), config, resolver, context, "CONFIG");
        }
        if (config != null && config.llmProvider() != null) {
            return new ModelSelection(inheritModel(context), config.llmProvider(),
                    providerName(config.llmProvider()), "CONFIG");
        }
        return inherit(context);
    }

    static String error(SubagentModelUnavailableException e, ExecutionContext context) {
        var prefix = e.reason() == SubagentModelUnavailableException.Reason.PROVIDER_UNAVAILABLE
                ? "[SUBAGENT_MODEL_PROVIDER_UNAVAILABLE] "
                : "[SUBAGENT_MODEL_UNKNOWN] ";
        var message = new StringBuilder(prefix).append(e.getMessage());
        appendAvailableModels(message, context.getSubagentModelResolver());
        return message.append(". Omit `model` to inherit your current model.").toString();
    }

    private static void appendAvailableModels(StringBuilder message, SubagentModelResolver resolver) {
        if (resolver == null) return;
        var models = resolver.availableModels();
        if (models == null || models.isEmpty()) return;
        message.append(". Available models: ")
                .append(String.join(", ", models.subList(0, Math.min(MAX_AVAILABLE_MODELS_IN_ERROR, models.size()))));
        if (models.size() > MAX_AVAILABLE_MODELS_IN_ERROR) message.append(", ...");
    }

    static String currentModel(ExecutionContext context) {
        if (context == null) return null;
        if (!blank(context.getModel())) return context.getModel();
        var provider = context.getLlmProvider();
        return provider != null && provider.config != null ? provider.config.getModel() : null;
    }

    static boolean shouldApplyToLiveAgent(ModelSelection selection) {
        return "CALL".equals(selection.source()) || "THREAD".equals(selection.source());
    }

    static String selectionProvider(ModelSelection selection, SubagentThread thread, Agent agent) {
        if (selection != null && !blank(selection.providerName())) return selection.providerName();
        var provider = agent == null ? null : agent.getLLMProvider();
        if (provider != null && !blank(provider.name())) return provider.name();
        return thread.provider();
    }

    static String effectiveModel(Agent agent) {
        if (agent == null) return null;
        if (!blank(agent.getModel())) return agent.getModel();
        var provider = agent.getLLMProvider();
        return provider != null && provider.config != null ? provider.config.getModel() : null;
    }

    static void applySelection(Agent agent, ModelSelection selection) {
        if (selection.provider() != null) {
            agent.setLlmProvider(selection.provider());
        }
        if (!blank(selection.model())) {
            agent.setModel(selection.model());
        }
        var compression = agent.getCompression();
        var effectiveModel = effectiveModel(agent);
        if (compression != null && selection.provider() != null && !blank(effectiveModel)) {
            compression.updateModel(selection.provider(), effectiveModel);
        }
    }

    private static ModelSelection resolveStrictly(String model, SubagentModelResolver resolver, ExecutionContext context) {
        if (resolver == null) {
            return parentSelection(model, context, "CALL");
        }
        var resolved = resolver.resolve(model);
        return new ModelSelection(resolved.model(), resolved.provider(), resolved.providerName(), "CALL");
    }

    /**
     * A thread keeps its own model. A new-format thread (one that recorded a provider) fails when that
     * model is gone; a legacy thread without a provider falls back to the parent provider like before.
     */
    private static ModelSelection resolveThread(SubagentThread thread, SubAgentConfig config,
                                                SubagentModelResolver resolver, ExecutionContext context) {
        var model = thread.model().trim();
        var preferred = thread.provider();
        if (resolver == null) {
            if (config != null && config.llmProvider() != null) {
                return new ModelSelection(model, config.llmProvider(), providerName(config.llmProvider()), "THREAD");
            }
            return parentSelection(model, context, "THREAD");
        }
        try {
            var resolved = resolver.resolve(model, preferred);
            return new ModelSelection(resolved.model(), resolved.provider(), resolved.providerName(), "THREAD");
        } catch (SubagentModelUnavailableException e) {
            if (blank(preferred)) {
                return parentSelection(model, context, "THREAD");
            }
            throw e;
        }
    }

    private static ModelSelection resolveWithFallback(String model, SubAgentConfig config, SubagentModelResolver resolver,
                                                      ExecutionContext context, String source) {
        if (config != null && config.llmProvider() != null) {
            return new ModelSelection(model, config.llmProvider(), providerName(config.llmProvider()), source);
        }
        if (resolver != null) {
            try {
                var resolved = resolver.resolve(model);
                return new ModelSelection(resolved.model(), resolved.provider(), resolved.providerName(), source);
            } catch (SubagentModelUnavailableException e) {
                LOGGER.warn("subagent model '{}' could not be resolved for {}; using the parent provider", model, source);
            }
        }
        return parentSelection(model, context, source);
    }

    private static ModelSelection inherit(ExecutionContext context) {
        var provider = context.getLlmProvider();
        return new ModelSelection(inheritModel(context), provider, providerName(provider), "INHERIT");
    }

    private static String inheritModel(ExecutionContext context) {
        var provider = context.getLlmProvider();
        return !blank(context.getModel()) ? context.getModel()
                : provider != null && provider.config != null ? provider.config.getModel() : null;
    }

    private static ModelSelection parentSelection(String model, ExecutionContext context, String source) {
        var provider = context.getLlmProvider();
        return new ModelSelection(model, provider, providerName(provider), source);
    }

    private static String providerName(LLMProvider provider) {
        return provider == null ? null : provider.name();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private SubagentModelSelector() {
    }

    record ModelSelection(String model, LLMProvider provider, String providerName, String source) {
    }
}
