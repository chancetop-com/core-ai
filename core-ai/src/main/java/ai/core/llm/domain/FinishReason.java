package ai.core.llm.domain;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum FinishReason {
    @Property(name = "stop")
    STOP,
    @Property(name = "tool_calls")
    TOOL_CALLS,
    @Property(name = "length")
    LENGTH
}
