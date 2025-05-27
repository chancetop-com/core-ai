package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class UsageAJAXView {
    @NotNull
    @Property(name = "prompt_tokens")
    public Integer promptTokens;

    @NotNull
    @Property(name = "completion_tokens")
    public Integer completionTokens;

    @NotNull
    @Property(name = "total_tokens")
    public Integer totalTokens;
}
