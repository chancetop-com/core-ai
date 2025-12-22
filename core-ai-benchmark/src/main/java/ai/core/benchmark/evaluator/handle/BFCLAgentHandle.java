package ai.core.benchmark.evaluator.handle;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.benchmark.domain.BFCLItem;
import ai.core.benchmark.domain.BFCLItemAgentResult;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.Tool;

import java.util.List;

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
        var rawRes = completionResponseWithLatency.response;
        if (rawRes.choices.getFirst().finishReason == FinishReason.TOOL_CALLS) {
            return null;
        }
        return null;
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
