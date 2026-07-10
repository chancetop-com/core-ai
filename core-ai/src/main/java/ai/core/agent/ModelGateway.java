package ai.core.agent;

import ai.core.agent.internal.AgentHelper;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.Tool;

import java.util.List;
import java.util.function.Function;

/**
 * @author stephen
 */
final class ModelGateway {

    static Choice handLLM(Agent agent, List<Message> messages, List<Tool> tools) {
        var effectiveModel = resolveEffectiveModel(agent, messages);
        var req = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(messages, tools, agent.llmProvider.config == null ? 0 : agent.llmProvider.config.getTemperature(), effectiveModel, agent.getName(), null, null, agent.reasoningEffort));
        agent.lastLLMSpanContext = null;
        return aroundLLM(agent, r -> agent.llmProvider.completionStream(r, AgentHelper.elseDefaultCallback(agent.getStreamingCallback()), sc -> agent.lastLLMSpanContext = sc), req);
    }

    static String resolveEffectiveModel(Agent agent, List<Message> messages) {
        if (agent.multiModalModel == null) return agent.model;
        for (var message : messages) {
            if (message.content == null) continue;
            for (var content : message.content) {
                if (content.type == Content.ContentType.IMAGE_URL || content.type == Content.ContentType.FILE) {
                    return agent.multiModalModel;
                }
            }
        }
        return agent.model;
    }

    static Choice aroundLLM(Agent agent, Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
        agent.agentLifecycles.forEach(alc -> alc.beforeModel(request, agent.getExecutionContext()));
        var resp = callLLM(agent, func, request);

        for (var lifecycle : agent.agentLifecycles) {
            var retryMessages = lifecycle.onModelResponse(request, resp, agent.getExecutionContext());
            if (retryMessages != null && !retryMessages.isEmpty()) {
                retryMessages.forEach(agent::addMessage);
                resp = callLLM(agent, func, request);
                break;
            }
        }

        return resp.choices.getFirst();
    }

    static CompletionResponse callLLM(Agent agent, Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
        var resp = func.apply(request);
        agent.addTokenCost(resp.usage);
        agent.agentLifecycles.forEach(alc -> alc.afterModel(request, resp, agent.getExecutionContext()));
        return resp;
    }

    private ModelGateway() {
    }
}
