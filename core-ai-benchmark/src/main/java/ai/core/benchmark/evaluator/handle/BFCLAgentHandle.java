package ai.core.benchmark.evaluator.handle;

import ai.core.agent.Agent;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemAgentResult;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.Tool;
import core.framework.util.Lists;

import java.util.List;
import java.util.Map;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
public abstract class BFCLAgentHandle implements AgentHandle<BFCLItem, BFCLItemAgentResult> {
    @Override
    public BFCLItemAgentResult handle(Agent agent, BFCLItem item) {
        var tools = completeTools(item);
        var messages = completeMessages(agent, item);
        var request = CompletionRequest.of(messages, tools, agent.getTemperature(), agent.getModel(), agent.getName());
        var resp = invokeLLM(agent, request);
        return fromCompletionResponse(resp, item.id, item.category);
    }

    protected abstract List<Tool> completeTools(BFCLItem item);

    protected abstract List<Message> completeMessages(Agent agent, BFCLItem item);

    protected CompletionResponseWithLatency invokeLLM(Agent agent, CompletionRequest request) {
        long startTime = System.currentTimeMillis();
        var completionResponse = agent.getLLMProvider().completion(request);
        long endTime = System.currentTimeMillis();
        double latency = (endTime - startTime) / 1000.0;
        return CompletionResponseWithLatency.of(completionResponse, latency);
    }

    protected BFCLItemAgentResult fromCompletionResponse(CompletionResponseWithLatency completionResponseWithLatency, String id, String category) {
        List<Map<String, Object>> result = Lists.newArrayList();
        var rawRes = completionResponseWithLatency.response;
        if (rawRes.choices.getFirst().finishReason == FinishReason.TOOL_CALLS) {
            var tools = rawRes.choices.getFirst().message.toolCalls;
            tools.stream().map(tool -> Map.of(tool.function.name,(Object) tool.function.arguments)).forEach(result::add);
        }
        return BFCLItemAgentResult.of(id,category,result,rawRes.usage.getPromptTokens(),rawRes.usage.getCompletionTokens(),completionResponseWithLatency.latency);
    }

    public static class CompletionResponseWithLatency {
        public CompletionResponse response;
        public Double latency;

        public static CompletionResponseWithLatency of(CompletionResponse response, Double latency) {
            var cl = new CompletionResponseWithLatency();
            cl.response = response;
            cl.latency = latency;
            return cl;
        }
    }
}
