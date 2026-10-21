package ai.core.agent.budget;

import ai.core.document.Tokenizer;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import ai.core.utils.MessageTokenCounterUtil;

/**
 * Estimates prompt tokens before a call and completion tokens when the provider
 * omits usage. The estimate is calibrated with the last reported prompt usage;
 * actual settled values are never rewritten.
 *
 * @author stephen
 */
public final class TokenEstimator {
    private static final double MIN_CALIBRATION = 0.8;
    private static final double MAX_CALIBRATION = 2.0;

    private final double safetyFactor;
    private volatile double calibration = 1.0;

    public TokenEstimator(double safetyFactor) {
        this.safetyFactor = safetyFactor > 0 ? safetyFactor : 1.0;
    }

    public long estimatePrompt(CompletionRequest request) {
        if (request == null) return 0;
        long text = request.messages == null ? 0 : MessageTokenCounterUtil.count(request.messages);
        long tools = request.tools == null ? 0 : Tokenizer.tokenCount(JsonUtil.toJson(request.tools));
        return Math.round((text + tools) * safetyFactor * calibration);
    }

    public Usage estimateUsage(CompletionRequest request, CompletionResponse response) {
        long prompt = estimatePrompt(request);
        var choice = response == null || response.choices == null || response.choices.isEmpty()
                ? null : response.choices.getFirst();
        var message = choice == null ? null : choice.message;
        long completion = message == null ? 0
                : Tokenizer.tokenCount(message.content) + Tokenizer.tokenCount(message.reasoningContent);
        return new Usage((int) prompt, (int) completion, (int) (prompt + completion));
    }

    public void calibrate(long estimatedPrompt, long actualPrompt) {
        if (estimatedPrompt <= 0 || actualPrompt <= 0) return;
        var ratio = (double) actualPrompt / estimatedPrompt;
        calibration = Math.max(MIN_CALIBRATION, Math.min(MAX_CALIBRATION, ratio));
    }
}
