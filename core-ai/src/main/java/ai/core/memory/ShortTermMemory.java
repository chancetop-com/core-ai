package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Short-term memory summary service for Agent.
 * Manages conversation summary with async summarization support.
 *
 * <p>This class does NOT store messages - Agent manages messages.
 * It only provides summarization capability and summary storage.</p>
 *
 * @author xander
 */
public class ShortTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemory.class);
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 1000;
    private static final double DEFAULT_TRIGGER_RATIO = 0.33;

    private static final String SUMMARIZE_PROMPT = """
        Merge the [History Summary] and [New Conversation] into a concise summary.
        Requirements:
        1. Keep key information from history that is still relevant
        2. Integrate important points from new conversation
        3. Remove redundant and outdated information
        4. Use bullet points, keep within %d words

        [History Summary]
        %s

        [New Conversation]
        %s

        Output summary directly:
        """;

    private static final String COMPRESS_PROMPT = """
        Compress this summary to within %d words, keeping only critical information:
        %s

        Output compressed summary:
        """;

    private final int maxSummaryTokens;
    private final double triggerRatio;
    private final Executor executor;

    private LLMProvider llmProvider;
    private String model;
    private String summary = "";
    private final AtomicReference<CompletableFuture<String>> pendingSummary = new AtomicReference<>(null);
    private final AtomicBoolean asyncTriggered = new AtomicBoolean(false);

    public ShortTermMemory() {
        this(DEFAULT_MAX_SUMMARY_TOKENS, DEFAULT_TRIGGER_RATIO, ForkJoinPool.commonPool());
    }

    public ShortTermMemory(int maxSummaryTokens) {
        this(maxSummaryTokens, DEFAULT_TRIGGER_RATIO, ForkJoinPool.commonPool());
    }

    public ShortTermMemory(int maxSummaryTokens, double triggerRatio, Executor executor) {
        this.maxSummaryTokens = maxSummaryTokens;
        this.triggerRatio = triggerRatio;
        this.executor = executor;
    }

    // ==================== Configuration ====================

    public void setLLMProvider(LLMProvider llmProvider, String model) {
        this.llmProvider = llmProvider;
        this.model = model;
    }

    // ==================== Summary Access ====================

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary != null ? summary : "";
    }

    public void clear() {
        this.summary = "";
        this.pendingSummary.set(null);
        this.asyncTriggered.set(false);
    }

    public int getSummaryTokens() {
        return Tokenizer.tokenCount(summary);
    }

    // ==================== Trigger Check ====================

    /**
     * Check if async summarization should be triggered.
     *
     * @param messageCount current message count
     * @param maxMessages  max messages threshold
     * @param tokenCount   current token count
     * @param maxTokens    max tokens threshold
     * @return true if should trigger
     */
    public boolean shouldTriggerAsync(int messageCount, int maxMessages, int tokenCount, int maxTokens) {
        if (llmProvider == null || asyncTriggered.get()) {
            return false;
        }
        return messageCount >= (int) (maxMessages * triggerRatio)
            || tokenCount >= (int) (maxTokens * triggerRatio);
    }

    // ==================== Async Summarization ====================

    /**
     * Trigger async summarization for given messages.
     *
     * @param messagesToSummarize messages to include in summary
     */
    public void triggerAsync(List<Message> messagesToSummarize) {
        if (llmProvider == null || !asyncTriggered.compareAndSet(false, true)) {
            return;
        }

        String content = formatMessages(messagesToSummarize);
        if (content.isBlank()) {
            asyncTriggered.set(false);
            return;
        }

        LOGGER.info("Triggering async summarization");

        CompletableFuture<String> future = CompletableFuture
            .supplyAsync(() -> doSummarize(summary, content), executor)
            .thenApply(this::ensureWithinLimit)
            .whenComplete((result, error) -> {
                if (error != null) {
                    LOGGER.error("Async summarization failed", error);
                }
            });

        pendingSummary.set(future);
    }

    /**
     * Try to apply async result if ready.
     *
     * @return true if async result was applied
     */
    public boolean tryApplyAsyncResult() {
        CompletableFuture<String> pending = pendingSummary.get();
        if (pending != null && pending.isDone()) {
            try {
                String result = pending.getNow("");
                if (!result.isBlank()) {
                    summary = result;
                    resetAsyncState();
                    return true;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to get async summary", e);
            }
        }
        return false;
    }

    // ==================== Sync Summarization ====================

    /**
     * Summarize messages synchronously.
     *
     * @param messagesToSummarize messages to summarize
     */
    public void summarize(List<Message> messagesToSummarize) {
        if (llmProvider == null) {
            return;
        }

        String content = formatMessages(messagesToSummarize);
        if (content.isBlank()) {
            return;
        }

        LOGGER.info("Performing sync summarization");
        summary = ensureWithinLimit(doSummarize(summary, content));
        resetAsyncState();
    }

    /**
     * Compress summary to fit within target tokens.
     *
     * @param targetTokens target max tokens
     */
    public void compressSummary(int targetTokens) {
        if (llmProvider == null || summary.isBlank()) {
            return;
        }

        int currentTokens = Tokenizer.tokenCount(summary);
        if (currentTokens <= targetTokens) {
            return;
        }

        LOGGER.info("Compressing summary from {} to {} tokens", currentTokens, targetTokens);
        summary = doCompress(summary, targetTokens);
    }

    // ==================== Context Building ====================

    /**
     * Build summary block for injection into system message.
     *
     * @return summary block or empty string
     */
    public String buildSummaryBlock() {
        if (summary.isBlank()) {
            return "";
        }
        return "\n\n[Conversation Memory]\n" + summary;
    }

    // ==================== Internal ====================

    private String doSummarize(String oldSummary, String newContent) {
        String historyPart = (oldSummary == null || oldSummary.isBlank()) ? "(empty)" : oldSummary;
        String prompt = String.format(SUMMARIZE_PROMPT, 300, historyPart, newContent);
        return callLLM(prompt);
    }

    private String doCompress(String content, int targetTokens) {
        int targetWords = (int) (targetTokens * 0.75);
        String prompt = String.format(COMPRESS_PROMPT, targetWords, content);
        return callLLM(prompt);
    }

    private String callLLM(String prompt) {
        var msgs = List.of(Message.of(RoleType.USER, prompt));
        var request = CompletionRequest.of(msgs, null, 0.3, model, "memory-summarizer");
        var response = llmProvider.completion(request);

        if (response.choices != null && !response.choices.isEmpty()) {
            var choice = response.choices.getFirst();
            if (choice.message != null && choice.message.content != null) {
                return choice.message.content.trim();
            }
        }
        return "";
    }

    private String ensureWithinLimit(String newSummary) {
        if (newSummary == null || newSummary.isBlank()) {
            return "";
        }
        int tokens = Tokenizer.tokenCount(newSummary);
        if (tokens <= maxSummaryTokens) {
            return newSummary;
        }
        if (llmProvider != null) {
            LOGGER.info("Summary exceeds limit, compressing");
            return doCompress(newSummary, maxSummaryTokens);
        }
        return newSummary;
    }

    private void resetAsyncState() {
        pendingSummary.set(null);
        asyncTriggered.set(false);
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) continue;
            String role = switch (msg.role) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case TOOL -> "Tool";
                default -> "Unknown";
            };
            sb.append(role).append(": ").append(msg.content != null ? msg.content : "").append('\n');
        }
        return sb.toString();
    }
}
