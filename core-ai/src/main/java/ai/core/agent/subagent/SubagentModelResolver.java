package ai.core.agent.subagent;

import ai.core.llm.LLMProvider;

import java.util.List;

/**
 * Host-provided model resolution for subagents. The core task tool only knows model ids; the host
 * knows which configured provider serves each id.
 *
 * @author stephen
 */
public interface SubagentModelResolver {
    /**
     * @param preferredProvider a hint from a persisted thread; implementations must verify that the
     *                          provider can serve the model (or is a gateway) before using it
     */
    ResolvedModel resolve(String model, String preferredProvider);

    default ResolvedModel resolve(String model) {
        return resolve(model, null);
    }

    List<String> availableModels();

    record ResolvedModel(String model, LLMProvider provider, String providerName) {
    }
}
