package ai.core.llm.providers.inner;

/**
 * @author stephen
 */
public class FunctionCall {

    public static FunctionCall of(String id, String type, Function function) {
        var functionCall = new FunctionCall();
        functionCall.id = id;
        functionCall.type = type;
        functionCall.function = function;
        return functionCall;
    }

    public String id;
    public String type;
    public Function function;
}
