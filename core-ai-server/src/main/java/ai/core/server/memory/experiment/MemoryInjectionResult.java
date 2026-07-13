package ai.core.server.memory.experiment;

import ai.core.prompt.PromptInject;
import ai.core.server.memory.AgentMemoryPromptInject;

import java.util.List;
import java.util.Map;

/**
 * Result of memory injection for a single run. Carries the formatted prompt
 * and metadata about what was injected.
 *
 * @author stephen
 */
public class MemoryInjectionResult {
    public static MemoryInjectionResult skipped() {
        var result = new MemoryInjectionResult();
        result.injected = false;
        return result;
    }

    public static MemoryInjectionResult injected(String formattedContent, List<String> memoryIds,
                                                  Map<String, Integer> layerBreakdown, int estimatedTokens) {
        var result = new MemoryInjectionResult();
        result.injected = true;
        result.injectedMemoryIds = memoryIds;
        result.layerBreakdown = layerBreakdown;
        result.estimatedTokens = estimatedTokens;
        result.promptInject = new AgentMemoryPromptInject(formattedContent);
        return result;
    }

    /** The formatted prompt section ready to inject, or null if injection was skipped. */
    public PromptInject promptInject;

    /** IDs of all memories that were selected for injection. */
    public List<String> injectedMemoryIds;

    /** Layer → count breakdown. */
    public Map<String, Integer> layerBreakdown;

    /** Approximate token count of the injected memory section. */
    public int estimatedTokens;

    /** Whether injection actually happened (probability check passed). */
    public boolean injected;
}
