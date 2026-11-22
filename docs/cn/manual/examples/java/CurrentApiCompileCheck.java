import ai.core.agent.Agent;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.tool.function.Functions;

/** Compile-only API sample. No main method; never sends model requests. */
public class CurrentApiCompileCheck {
    public static class EchoTools {
        @CoreAiMethod(name = "echo", description = "原样返回文字")
        public String echo(@CoreAiParameter(name = "text", description = "文字") String text) {
            return text;
        }
    }
    public static Agent build(String testBaseUrl, String key, String model) {
        var config = new LLMProviderConfig(model, 0.7, "embedding-model");
        var provider = new LiteLLMProvider(config, testBaseUrl, key);
        return Agent.builder().name("compile-demo")
            .llmProvider(provider).model(model)
            .toolCalls(Functions.from(new EchoTools()))
            .streaming(true).streamingCallback(new StreamingCallback() {
                @Override public void onChunk(String chunk) { System.out.print(chunk); }
                @Override public void onComplete() { System.out.println(); }
                @Override public void onError(Throwable error) { System.err.println(error.getMessage()); }
            }).build();
    }
}
