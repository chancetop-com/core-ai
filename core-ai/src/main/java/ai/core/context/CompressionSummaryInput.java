package ai.core.context;

import ai.core.llm.domain.Message;
import ai.core.utils.MessageTokenCounterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a summary request inside the window after a learned limit lowers it: a rejected request may
 * be larger than the summary model can take, and the recovery compression must not overflow itself.
 *
 * @author stephen
 */
final class CompressionSummaryInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressionSummaryInput.class);
    private static final double INPUT_RATIO = 0.9;
    private static final int MIN_INPUT_TOKENS = 2000;

    static List<Message> fit(List<Message> messages, int maxContextTokens, int maxSummaryTokens) {
        int budget = Math.max(MIN_INPUT_TOKENS, (int) (maxContextTokens * INPUT_RATIO) - maxSummaryTokens);
        if (MessageTokenCounterUtil.count(messages) <= budget) {
            return messages;
        }

        var kept = new ArrayList<Message>();
        int tokens = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            int messageTokens = MessageTokenCounterUtil.count(message);
            if (!kept.isEmpty() && tokens + messageTokens > budget) {
                break;
            }
            kept.add(0, message);
            tokens += messageTokens;
        }
        LOGGER.warn("summary input truncated to the most recent {} messages (~{} tokens) to fit the context window", kept.size(), tokens);
        return kept;
    }

    private CompressionSummaryInput() {
    }
}
