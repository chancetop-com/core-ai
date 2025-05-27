package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum FinishReasonAJAXVIEW {
    @Property(name = "stop")
    STOP,
    @Property(name = "tool_calls")
    TOOL_CALLS
}
