package ai.core.litellm.completion;

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
