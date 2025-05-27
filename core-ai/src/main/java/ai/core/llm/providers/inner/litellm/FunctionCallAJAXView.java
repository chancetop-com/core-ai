package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FunctionCallAJAXView {
    @Property(name = "id")
    public String id;

    @Property(name = "type")
    public String type;

    @Property(name = "function")
    public Function function;

    public static class Function {
        @Property(name = "name")
        public String name;

        @NotNull
        @Property(name = "arguments")
        public String arguments;
    }
}
