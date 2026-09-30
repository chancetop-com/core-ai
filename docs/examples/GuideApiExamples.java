import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.context.CompressionConfig;
import ai.core.flow.Flow;
import ai.core.llm.LLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.memory.InMemoryStore;
import ai.core.memory.Memory;
import ai.core.memory.MemoryConfig;
import ai.core.rag.RagConfig;
import ai.core.skill.FilesystemSkillProvider;
import ai.core.skill.SkillRegistry;
import ai.core.tool.function.Functions;
import ai.core.vectorstore.VectorStore;

/**
 * Compile-only guide fragments; no main method.
 * @author xander
 */
public class GuideApiExamples {
    public static class EchoTools {
        @CoreAiMethod(name = "echo", description = "Return the input text")
        public String echo(
            @CoreAiParameter(name = "text", description = "Input text")
            String text) {
            return text;
        }
    }

    public static void basic(LLMProvider provider, String model) {
        var agent = Agent.builder()
            .name("guide-agent")
            .llmProvider(provider)
            .model(model)
            .systemPrompt("Answer clearly.")
            .compression(false)
            .maxTurn(3)
            .build();
        var context = ExecutionContext.builder()
            .userId("demo-user")
            .sessionId("demo-session")
            .build();
        String result = agent.run("Hello", context);
    }

    public static void stream(LLMProvider provider, String model) {
        var agent = Agent.builder()
            .name("streaming-agent")
            .llmProvider(provider)
            .model(model)
            .streaming(true)
            .streamingCallback(new StreamingCallback() {
                @Override
                public void onChunk(String chunk) {
                    System.out.print(chunk);
                }

                @Override
                public void onComplete() {
                    System.out.println();
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println(error.getMessage());
                }
            }).build();
    }

    public static void tools(LLMProvider provider, String model) {
        var agent = Agent.builder()
            .name("echo-agent")
            .llmProvider(provider)
            .model(model)
            .toolCalls(Functions.from(new EchoTools()))
            .build();
    }

    public static void memory(LLMProvider provider, String model) {
        var memory = Memory.builder()
            .llmProvider(provider)
            .memoryStore(new InMemoryStore())
            .build();
        var memoryConfig = MemoryConfig.builder()
            .autoRecall(true)
            .maxRecallRecords(5)
            .build();
        var agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(provider)
            .model(model)
            .unifiedMemory(memory, memoryConfig)
            .build();
    }

    public static void compression(LLMProvider provider, String model) {
        var compression = new CompressionConfig(
            true, 0.75, 4, 2000, null, null);
        var agent = Agent.builder()
            .name("context-agent")
            .llmProvider(provider)
            .model(model)
            .compression(compression)
            .build();
    }

    public static void rag(LLMProvider provider, String model, VectorStore vectorStore) {
        var rag = RagConfig.builder()
            .useRag(true)
            .topK(5)
            .threshold(0.0)
            .vectorStore(vectorStore)
            .llmProvider(provider)
            .enableQueryRewriting(false)
            .build();
        var agent = Agent.builder()
            .name("rag-agent")
            .llmProvider(provider)
            .model(model)
            .ragConfig(rag)
            .build();
    }

    public static void skills(LLMProvider provider, String model, String userSkillsPath) {
        var registry = new SkillRegistry();
        registry.addProvider(new FilesystemSkillProvider(
            "project", "./.core-ai/skills", 0));
        registry.addProvider(new FilesystemSkillProvider(
            "user", userSkillsPath, 1));
        var agent = Agent.builder()
            .name("skill-agent")
            .llmProvider(provider)
            .model(model)
            .skillRegistry(registry)
            .build();
    }

    public static void flow(Flow flow, String startNodeId, String input, ExecutionContext context) {
        flow.validate();
        String result = flow.run(startNodeId, input, context);
    }
}
