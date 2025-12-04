package ai.core.agent.slidingwindow;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.MessageTokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * @author stephen
 */
public class SlidingWindowService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlidingWindowService.class);

    private final SlidingWindowConfig config;
    private final LLMProvider llmProvider;
    private final String model;

    public SlidingWindowService(SlidingWindowConfig config, LLMProvider llmProvider, String model) {
        this.config = config;
        this.llmProvider = llmProvider;
        this.model = model;
    }

    public boolean shouldSlide(List<Message> messages) {
        // tool call message must pair with tool response message
        if (hasPendingToolCalls(messages)) {
            return false;
        }

        var safeCutPoints = findSafeCutPoints(messages);

        if (config.getMaxTurns() != null && safeCutPoints.size() > config.getMaxTurns()) {
            return true;
        }

        if (config.isAutoTokenProtection()) {
            var totalTokens = calculateTotalTokens(messages);
            var maxTokens = getMaxTokens();
            return totalTokens > maxTokens * config.getTriggerThreshold();
        }

        return false;
    }

    public List<Message> slide(List<Message> messages) {
        var result = new ArrayList<Message>();

        var systemMessage = extractSystemMessage(messages);
        if (systemMessage != null) {
            result.add(systemMessage);
        }

        var safeCutPoints = findSafeCutPoints(messages);
        if (safeCutPoints.isEmpty()) {
            return new ArrayList<>(messages);
        }

        var turnsToKeep = calculateTurnsToKeep(messages, safeCutPoints);
        var cutPointIndex = Math.max(0, safeCutPoints.size() - turnsToKeep);
        var cutMessageIndex = safeCutPoints.get(cutPointIndex);

        LOGGER.info("Sliding window: keeping {} turns, cutting at message index {}", turnsToKeep, cutMessageIndex);

        for (int i = cutMessageIndex; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg.role != RoleType.SYSTEM) {
                result.add(msg);
            }
        }

        return result;
    }

    private List<Integer> findSafeCutPoints(List<Message> messages) {
        var safeCutPoints = new ArrayList<Integer>();
        var pendingToolCalls = new HashSet<>();

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);

            if (msg.role == RoleType.ASSISTANT && msg.toolCalls != null) {
                for (FunctionCall call : msg.toolCalls) {
                    if (call.id != null) {
                        pendingToolCalls.add(call.id);
                    }
                }
            }

            if (msg.role == RoleType.TOOL && msg.toolCallId != null) {
                pendingToolCalls.remove(msg.toolCallId);
            }

            if (msg.role == RoleType.USER && pendingToolCalls.isEmpty()) {
                safeCutPoints.add(i);
            }
        }

        return safeCutPoints;
    }

    private boolean hasPendingToolCalls(List<Message> messages) {
        var pendingToolCalls = new HashSet<>();

        for (var msg : messages) {
            if (msg.role == RoleType.ASSISTANT && msg.toolCalls != null) {
                for (FunctionCall call : msg.toolCalls) {
                    if (call.id != null) {
                        pendingToolCalls.add(call.id);
                    }
                }
            }
            if (msg.role == RoleType.TOOL && msg.toolCallId != null) {
                pendingToolCalls.remove(msg.toolCallId);
            }
        }

        return !pendingToolCalls.isEmpty();
    }

    private int calculateTurnsToKeep(List<Message> messages, List<Integer> safeCutPoints) {
        if (config.getMaxTurns() != null) {
            return config.getMaxTurns();
        }

        var maxTokens = (int) (getMaxTokens() * config.getTargetThreshold());
        var systemMessage = extractSystemMessage(messages);
        var systemTokens = systemMessage != null ? MessageTokenCounter.count(systemMessage) : 0;
        var availableTokens = maxTokens - systemTokens;

        for (var keepTurns = safeCutPoints.size(); keepTurns >= 1; keepTurns--) {
            var cutPointIndex = Math.max(0, safeCutPoints.size() - keepTurns);
            var cutMessageIndex = safeCutPoints.get(cutPointIndex);

            var tokens = calculateTokensFromIndex(messages, cutMessageIndex);
            if (tokens <= availableTokens) {
                return keepTurns;
            }
        }

        return 1;
    }

    private int calculateTokensFromIndex(List<Message> messages, int fromIndex) {
        return MessageTokenCounter.countFrom(messages, fromIndex, true);
    }

    private int calculateTotalTokens(List<Message> messages) {
        return MessageTokenCounter.countFrom(messages, 0, true);
    }

    private Message extractSystemMessage(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.role == RoleType.SYSTEM)
                .findFirst()
                .orElse(null);
    }

    private int getMaxTokens() {
        return model != null ? llmProvider.maxTokens(model) : llmProvider.maxTokens();
    }
}
