package ai.core.benchmark.inference;

import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.llm.LLMProvider;
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
 * description: base function call
 */
public class BFCLInferenceFCHandle extends BFCLInferenceHandle {
    private final LLMProvider llmProvider;

    public BFCLInferenceFCHandle(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    protected BFCLItemEvalResult invoke(String id, List<Message> messages, List<Tool> tools) {
        var request = CompletionRequest.of(messages, tools, llmProvider.config.getTemperature(), llmProvider.config.getModel(), "eval-req");
        var resp = invokeLLM(request);
        return fromCompletionResponse(resp, id);
    }

    private CompletionResponseWithLatency invokeLLM(CompletionRequest request) {
        long startTime = System.currentTimeMillis();
        var completionResponse = llmProvider.completion(request);
        long endTime = System.currentTimeMillis();
        double latency = (endTime - startTime) / 1000.0;
        return CompletionResponseWithLatency.of(completionResponse, latency);
    }

    private BFCLItemEvalResult fromCompletionResponse(CompletionResponseWithLatency completionResponseWithLatency, String id) {
        List<Map<String, Object>> result = Lists.newArrayList();
        var rawRes = completionResponseWithLatency.response;
        if (rawRes.choices.getFirst().finishReason == FinishReason.TOOL_CALLS) {
            var tools = rawRes.choices.getFirst().message.toolCalls;
            tools.stream().map(tool -> Map.of(tool.function.name, (Object) tool.function.arguments)).forEach(result::add);
        }
        if (result.isEmpty()) {
            result.add(Map.of("output", rawRes.choices.getFirst().message.content));
        }
        return BFCLItemEvalResult.of(id, result, rawRes.usage.getPromptTokens(), rawRes.usage.getCompletionTokens(), completionResponseWithLatency.latency);
    }

    static class CompletionResponseWithLatency {
        static CompletionResponseWithLatency of(CompletionResponse response, Double latency) {
            var cl = new CompletionResponseWithLatency();
            cl.response = response;
            cl.latency = latency;
            return cl;
        }
        CompletionResponse response;
        Double latency;
    }


}
