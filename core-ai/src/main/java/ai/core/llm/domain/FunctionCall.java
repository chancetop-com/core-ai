package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FunctionCall {
    public static FunctionCall of(String id, String type, String functionName, String arguments) {
        FunctionCall functionCall = new FunctionCall();
        functionCall.id = id;
        functionCall.type = type;
        functionCall.function = new Function();
        functionCall.function.name = functionName;
        functionCall.function.arguments = arguments;
        return functionCall;
    }

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
