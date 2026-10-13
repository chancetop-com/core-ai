package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

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
    @Property(name = "index")
    public Integer index;
    // provider-specific metadata attached to a tool call, echoed back verbatim in subsequent requests.
    // Google's OpenAI-compatible endpoints require the thought_signature of a functionCall part
    // (extra_content.google.thought_signature) to be returned exactly as received, or Gemini 3
    // models reject the request with 400 "Function call is missing a thought_signature".
    @Property(name = "extra_content")
    public Map<String, Object> extraContent;

    public static class Function {
        @Property(name = "name")
        public String name;

        @NotNull
        @Property(name = "arguments")
        public String arguments;

        private transient StringBuilder argumentsBuilder;

        public void appendArguments(String chunk) {
            if (argumentsBuilder == null) {
                argumentsBuilder = new StringBuilder(arguments != null ? arguments : "");
            }
            argumentsBuilder.append(chunk);
        }

        public void finalizeStreamingFields() {
            if (argumentsBuilder != null) {
                arguments = argumentsBuilder.toString();
                argumentsBuilder = null;
            }
        }
    }
}
