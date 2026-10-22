package ai.core.agent;

import ai.core.agent.budget.BudgetCall;
import ai.core.agent.internal.AgentHelper;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.Tool;

import java.util.List;
import java.util.function.Function;

/**
 * @author stephen
 */
final class ModelGateway {

    static Choice handLLM(Agent agent, List<Message> messages, List<Tool> tools) {
        var effectiveModel = resolveEffectiveModel(agent);
        // Some explicitly configured multimodal main models do not accept reasoning_effort.
        var reasoningEffort = effectiveModel != null && effectiveModel.equals(agent.multiModalModel) ? null : agent.reasoningEffort;
        var reqTools = AgentHelper.filterRedundantVisionTools(tools, agent.getExecutionContext().isVisionNative(), effectiveModel);
        var req = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(messages, reqTools, agent.llmProvider.config == null ? 0.0 : agent.llmProvider.config.getTemperature(), effectiveModel, agent.getName(), null, null, reasoningEffort));
        req.setSessionId(agent.getExecutionContext().getSessionId());
        agent.lastLLMSpanContext = null;
        return aroundLLM(agent, r -> agent.llmProvider.completionStream(r, AgentHelper.elseDefaultCallback(agent.getStreamingCallback()), sc -> agent.lastLLMSpanContext = sc), req);
    }

    static String resolveEffectiveModel(Agent agent) {
        // Image understanding is delegated through caption_image when the main model is text-only.
        // The main agent loop must never be silently transferred to multiModalModel by message history.
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
        var budget = agent.getExecutionContext().getRunBudget();
        if (budget != null && budget.time() != null && budget.time().remainingMillis() <= 0) {
            var token = agent.getExecutionContext().getCancellationToken();
            if (token != null) token.cancel(CancelReason.BUDGET_LIMITED);
            throw new CancellationException(CancelReason.BUDGET_LIMITED);
        }
        BudgetCall budgetCall = null;
        if (budget != null && budget.needsModelAdmission()) {
            var model = request.model != null ? request.model : agent.getModel();
            budgetCall = budget.admitModel(request, model);
        }
        if (budgetCall != null && !budgetCall.allowed()) {
            var token = agent.getExecutionContext().getCancellationToken();
            if (token != null) token.cancel(CancelReason.BUDGET_LIMITED);
            throw new CancellationException(CancelReason.BUDGET_LIMITED);
        }
        try {
            var resp = func.apply(request);
            // Main-loop cost was previously ignored; addLlmUsage keeps token and estimated cost in one place.
            var model = request.model != null ? request.model : agent.getModel();
            agent.addLlmUsage(model, resp.usage);
            if (budgetCall != null) budget.settleModel(budgetCall, request, resp);
            agent.agentLifecycles.forEach(alc -> alc.afterModel(request, resp, agent.getExecutionContext()));
            if (budgetCall != null && budget.isHardLimitReached()) {
                var token = agent.getExecutionContext().getCancellationToken();
                if (token != null) token.cancel(CancelReason.BUDGET_LIMITED);
            }
            return resp;
        } catch (RuntimeException e) {
            if (budgetCall != null && !budgetCall.settled()) budget.release(budgetCall);
            throw e;
        }
    }

    private ModelGateway() {
    }
}
