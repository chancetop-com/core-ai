package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * Token usage of a hub call that ran an LLM (an {@code llm_call} definition or a sub-agent run).
 * The hub reports it back so the script can show/aggregate cost without querying the server again;
 * the same numbers are already folded into the session's own usage accounting.
 *
 * @author stephen
 */
public class SandboxHubLlmUsage {
    @Property(name = "model")
    public String model;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cost")
    public Double cost;
}
