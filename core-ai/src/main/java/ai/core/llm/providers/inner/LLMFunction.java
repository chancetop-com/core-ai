package ai.core.llm.providers.inner;

/**
 * @author stephen
 */
public class LLMFunction {
    public static LLMFunction of(String name, String arguments) {
        var function = new LLMFunction();
        function.name = name;
        function.arguments = arguments;
        return function;
    }

    public String name;
    public String arguments;

    public static class FunctionCall {
        public static FunctionCall of(String id, String type, LLMFunction function) {
            var functionCall = new FunctionCall();
            functionCall.id = id;
            functionCall.type = type;
            functionCall.function = function;
            return functionCall;
        }

        public String id;
        public String type;
        public LLMFunction function;
    }
}
