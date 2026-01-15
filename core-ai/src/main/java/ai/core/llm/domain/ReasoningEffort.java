package ai.core.llm.domain;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum ReasoningEffort {
    @Property(name = "low")
    LOW,
    @Property(name = "medium")
    MEDIUM,
    @Property(name = "high")
    HIGH
}
