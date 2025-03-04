package ai.core.llm.providers.inner;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum ParameterTypeView {
    @Property(name = "string")
    STRING,
    @Property(name = "object")
    OBJECT
}
