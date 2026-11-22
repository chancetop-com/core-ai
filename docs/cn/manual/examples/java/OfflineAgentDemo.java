import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import java.util.List;

public class OfflineAgentDemo {
    public static void main(String[] args) {
        var provider = new MockLLMProvider();
        provider.addResponse(CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP,
                Message.of(RoleType.ASSISTANT, "离线演示成功"))),
            new Usage(10, 5, 15)));
        var agent = Agent.builder()
            .name("manual-demo")
            .description("无网络的手册演示")
            .llmProvider(provider)
            .model("mock-model")
            .systemPrompt("请用中文回答")
            .compression(false)
            .maxTurn(3)
            .build();
        var context = ExecutionContext.builder()
            .userId("demo-user")
            .sessionId("demo-session")
            .build();
        System.out.println("结果: " + agent.run("测试", context));
        System.out.println("状态: " + agent.getNodeStatus());
        System.out.println("调用次数: " + provider.getCallCount());
        System.out.println("模拟 token 数: "
            + agent.getCurrentTokenUsage().getTotalTokens());
    }
}
