package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the span type enum.
 *
 * @author stephen
 */
public enum SpanTypeView {
    @Property(name = "LLM")
    LLM,
    @Property(name = "AGENT")
    AGENT,
    @Property(name = "TOOL")
    TOOL,
    @Property(name = "FLOW")
    FLOW,
    @Property(name = "GROUP")
    GROUP
}
