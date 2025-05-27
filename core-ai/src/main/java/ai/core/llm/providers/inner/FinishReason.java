package ai.core.llm.providers.inner;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum FinishReason {
    @Property(name = "stop")
    STOP,
    @Property(name = "tool_calls")
    TOOL_CALLS
}
