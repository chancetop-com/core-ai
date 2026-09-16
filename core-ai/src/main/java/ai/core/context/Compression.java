package ai.core.context;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.prompt.Prompts;
import ai.core.sandbox.Sandbox;
import ai.core.utils.MessageTokenCounterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * @author xander
 */
public class Compression {
    public static final String COMPRESSION_TOOL_NAME = "memory_compress";
    private static final Logger LOGGER = LoggerFactory.getLogger(Compression.class);

    private static final double DEFAULT_TRIGGER_THRESHOLD = 0.8;
    private static final double DEFAULT_TOOL_RESULT_RATIO = 0.5;
    private static final int FALLBACK_MAX_TOOL_RESULT_TOKENS = 64000;
    private static final int FALLBACK_MAX_CONTEXT_TOKENS = 128000;
    private static final int DEFAULT_KEEP_RECENT_TURNS = 5;
    private static final int DEFAULT_KEEP_TOKENS = 15000;
    private static final int MIN_SUMMARY_TOKENS = 500;
    private static final int MAX_SUMMARY_TOKENS = 4000;
    private final double triggerThreshold;
    private final int keepRecentTurns;
    private final int keepTokens;
    private int maxContextTokens;
    private final double toolResultRatio;
    private int maxToolResultTokens;
    private LLMProvider llmProvider;
    private String summaryModel;
    private String contextModel;
    private Integer contextWindowOverride;
    private BiConsumer<String, Usage> llmUsageSink;
    private volatile String lastFailure;
    private final List<CompressionListener> listeners = new ArrayList<>();

    public Compression(LLMProvider llmProvider, String agentModel) {
        this(DEFAULT_TRIGGER_THRESHOLD, DEFAULT_KEEP_RECENT_TURNS, DEFAULT_KEEP_TOKENS, llmProvider, agentModel, agentModel);
    }
    public Compression(double triggerThreshold, int keepRecentTurns, LLMProvider llmProvider, String agentModel, String summaryModel) {
        this(triggerThreshold, keepRecentTurns, DEFAULT_KEEP_TOKENS, llmProvider, agentModel, summaryModel);
    }
    public Compression(double triggerThreshold, int keepRecentTurns, int keepMinTokens, LLMProvider llmProvider, String agentModel, String summaryModel) {
        this.triggerThreshold = triggerThreshold;
        this.keepRecentTurns = keepRecentTurns;
        this.keepTokens = keepMinTokens;
        this.toolResultRatio = DEFAULT_TOOL_RESULT_RATIO;
        this.llmProvider = llmProvider;
        this.summaryModel = summaryModel;
        this.contextModel = agentModel;
        this.maxContextTokens = resolveContextWindow();
        this.maxToolResultTokens = calculateMaxToolResultTokens();
    }
    public Compression(CompressionConfig config, LLMProvider llmProvider, String agentModel) {
        this(config != null && config.triggerThreshold() != null ? config.triggerThreshold() : DEFAULT_TRIGGER_THRESHOLD,
            config != null && config.keepRecentTurns() != null ? config.keepRecentTurns() : DEFAULT_KEEP_RECENT_TURNS,
            config != null && config.keepMinTokens() != null ? config.keepMinTokens() : DEFAULT_KEEP_TOKENS,
            llmProvider, agentModel, config != null && config.summaryModel() != null ? config.summaryModel() : agentModel);
        this.contextWindowOverride = config != null ? config.contextWindowTokens() : null;
        if (contextWindowOverride != null) {
            this.maxContextTokens = contextWindowOverride;
            this.maxToolResultTokens = calculateMaxToolResultTokens();
        }
    }
    private int resolveContextWindow() {
        if (contextWindowOverride != null) {
            return contextWindowOverride;
        }
        var modelInfo = contextModel != null ? LLMModelContextRegistry.getInstance().getModelInfo(contextModel) : null;
        return modelInfo != null ? modelInfo.contextWindow() : FALLBACK_MAX_CONTEXT_TOKENS;
    }
    private int calculateMaxToolResultTokens() {
        if (maxContextTokens <= 0) {
            return FALLBACK_MAX_TOOL_RESULT_TOKENS;
        }
        return (int) (maxContextTokens * toolResultRatio);
    }
    public void addListener(CompressionListener listener) {
        this.listeners.add(listener);
    }
    /**
     * Reports every summarization LLM call to the owning agent so its usage and cost stay complete.
     */
    public void onLlmUsage(BiConsumer<String, Usage> sink) {
        this.llmUsageSink = sink;
    }
    /**
     * The last summarization failure, so callers can surface it instead of reporting a silent no-op.
     */
    public String getLastFailure() {
        return lastFailure;
    }
    /**
     * Repoints compression at a provider/model selected after the agent was built.
     */
    public void updateModel(LLMProvider llmProvider, String agentModel) {
        if (llmProvider != null) this.llmProvider = llmProvider;
        if (agentModel == null || agentModel.isBlank()) return;
        this.summaryModel = agentModel;
        this.contextModel = agentModel;
        this.contextWindowOverride = null;
        this.maxContextTokens = resolveContextWindow();
        this.maxToolResultTokens = calculateMaxToolResultTokens();
    }
    public boolean shouldCompress(int currentTokens) {
        if (llmProvider == null) {
            return false;
        }
        return currentTokens >= (int) (maxContextTokens * triggerThreshold);
    }
    public List<Message> compress(List<Message> messages) {
        if (messages == null || messages.isEmpty() || llmProvider == null) {
            return messages;
        }

        int currentTokens = MessageTokenCounterUtil.count(messages);
        if (!shouldCompress(currentTokens)) {
            return messages;
        }

        LOGGER.debug("Compressing messages: currentTokens={}, threshold={}", currentTokens, (int) (maxContextTokens * triggerThreshold));
        return doCompress(messages, false);
    }
    public List<Message> forceCompress(List<Message> messages) {
        if (messages == null || messages.isEmpty() || llmProvider == null) {
            return messages;
        }
        return doCompress(messages, true);
    }
    private List<Message> doCompress(List<Message> messages, boolean force) {
        lastFailure = null;
        var systemMsg = extractSystemMessage(messages);
        var conversationMsgs = extractConversationMessages(messages);
        if (conversationMsgs.size() <= 2) {
            return skip(messages, "not enough conversation to summarize");
        }
        int lastUserIndex = findLastUserIndex(conversationMsgs);
        if (lastUserIndex < 0) {
            return skip(messages, "no user message to anchor the kept window");
        }
        int keepFromIndex = force
                ? calculateForceKeepFromIndex(conversationMsgs)
                : calculateKeepFromIndex(conversationMsgs, lastUserIndex);
        keepFromIndex = ToolCallPruning.alignToToolSegmentStart(conversationMsgs, keepFromIndex);
        if (keepFromIndex <= 0) {
            return skip(messages, "nothing before the kept window to summarize");
        }
        List<Message> toCompress = new ArrayList<>(conversationMsgs.subList(0, keepFromIndex));
        List<Message> toKeep = new ArrayList<>(conversationMsgs.subList(keepFromIndex, conversationMsgs.size()));
        if (toCompress.isEmpty()) {
            return skip(messages, "nothing before the kept window to summarize");
        }
        Message preservedUserMsg = findPreservedUserMessage(toCompress, toKeep);
        if (preservedUserMsg != null) {
            toCompress.remove(preservedUserMsg);
        }
        int overhead = 2 + (preservedUserMsg != null ? 1 : 0) + (systemMsg != null ? 1 : 0);
        if (toCompress.size() <= overhead) {
            return skip(messages, "too few messages to summarize");
        }
        return applySummary(messages, systemMsg, preservedUserMsg, toKeep, toCompress);
    }
    private List<Message> applySummary(List<Message> messages, Message systemMsg, Message preservedUserMsg,
                                       List<Message> toKeep, List<Message> toCompress) {
        notifyStarted(messages.size(), toCompress.size());
        var summary = summarize(toCompress);
        if (summary.isBlank()) {
            if (lastFailure == null) lastFailure = "summarization returned an empty result";
            LOGGER.warn("Summarization returned empty result, keeping original messages");
            return messages;
        }
        var result = buildCompressedResult(systemMsg, summary, preservedUserMsg, toKeep);
        if (result.size() >= messages.size()) {
            lastFailure = "compression would not shrink the conversation";
            LOGGER.debug("Compression did not reduce message count, keeping original");
            return messages;
        }
        notifyCompleted(messages.size(), result.size());
        LOGGER.debug("Compression complete: {} -> {} messages", messages.size(), result.size());
        return result;
    }
    private List<Message> skip(List<Message> messages, String reason) {
        lastFailure = reason;
        LOGGER.debug("Compression skipped: {}", reason);
        return messages;
    }
    private Message findPreservedUserMessage(List<Message> toCompress, List<Message> toKeep) {
        boolean hasUserInKeep = toKeep.stream().anyMatch(m -> m.role == RoleType.USER);
        if (hasUserInKeep) {
            return null;
        }
        return toCompress.stream()
            .filter(m -> m.role == RoleType.USER)
            .reduce((first, second) -> second)
            .orElse(null);
    }
    private int calculateForceKeepFromIndex(List<Message> conversationMsgs) {
        int accumulatedTokens = 0;
        int keepFromIndex = conversationMsgs.size() - 1;
        for (int i = conversationMsgs.size() - 1; i >= 0; i--) {
            accumulatedTokens += MessageTokenCounterUtil.count(conversationMsgs.get(i));
            if (accumulatedTokens > keepTokens) {
                keepFromIndex = adjustToToolSegmentBoundary(conversationMsgs, i);
                break;
            }
        }
        if (keepFromIndex <= 0) {
            keepFromIndex = Math.max(1, conversationMsgs.size() / 2);
        }
        return keepFromIndex;
    }
    private int adjustToToolSegmentBoundary(List<Message> msgs, int index) {
        for (int i = index; i < msgs.size(); i++) {
            Message msg = msgs.get(i);
            if (msg.role == RoleType.ASSISTANT && (msg.toolCalls == null || msg.toolCalls.isEmpty())) {
                return i;
            }
            if (msg.role == RoleType.USER) {
                return i;
            }
        }
        return index + 1;
    }
    private List<Message> buildCompressedResult(Message systemMsg, String summary, Message preservedUserMsg, List<Message> toKeep) {
        var toolCallId = COMPRESSION_TOOL_NAME + "_" + System.currentTimeMillis();
        var compressCall = FunctionCall.of(toolCallId, "function", COMPRESSION_TOOL_NAME, "{}");
        var toolCallMsg = Message.of(RoleType.ASSISTANT, null, null, null, List.of(compressCall), "");
        var toolResultMsg = Message.of(RoleType.TOOL, summary, COMPRESSION_TOOL_NAME, toolCallId, null);

        var result = new ArrayList<Message>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        result.add(toolCallMsg);
        result.add(toolResultMsg);
        if (preservedUserMsg != null) {
            result.add(preservedUserMsg);
        }
        result.addAll(toKeep);
        return ToolCallPruning.dropOrphanToolMessages(result);
    }
    public double getTriggerThreshold() {
        return triggerThreshold;
    }
    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }
    private Message extractSystemMessage(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst()
            .orElse(null);
    }
    private List<Message> extractConversationMessages(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.role != RoleType.SYSTEM)
            .toList();
    }
    private int calculateKeepFromIndex(List<Message> conversationMsgs, int lastUserIndex) {
        var keepFromIndex = findKeepFromIndexByTurnsAndTokens(conversationMsgs, lastUserIndex);
        var tokensFromKeep = MessageTokenCounterUtil.countFrom(conversationMsgs, keepFromIndex);
        var threshold = (int) (maxContextTokens * triggerThreshold);
        if (tokensFromKeep >= threshold) {
            return Math.max(keepFromIndex, conversationMsgs.size() - 1);
        }
        return keepFromIndex;
    }
    private int findLastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == RoleType.USER) {
                return i;
            }
        }
        return -1;
    }
    private int findKeepFromIndexByTurnsAndTokens(List<Message> conversationMsgs, int lastUserIndex) {
        int turnCount = 0;
        int accumulatedTokens = 0;
        int indexByTurns = lastUserIndex;
        int indexByTokens = 0;
        boolean tokenBudgetExceeded = false;
        boolean pendingTokenSplit = false;

        for (int i = conversationMsgs.size() - 1; i >= 0; i--) {
            Message msg = conversationMsgs.get(i);
            boolean isAssistantWithTools = msg.role == RoleType.ASSISTANT
                && msg.toolCalls != null && !msg.toolCalls.isEmpty();

            if (!tokenBudgetExceeded) {
                accumulatedTokens += MessageTokenCounterUtil.count(msg);
            }
            if (!tokenBudgetExceeded && accumulatedTokens > keepTokens) {
                pendingTokenSplit = msg.role == RoleType.TOOL;
                tokenBudgetExceeded = !pendingTokenSplit;
                indexByTokens = pendingTokenSplit ? indexByTokens : i;
            }

            if (pendingTokenSplit && isAssistantWithTools) {
                indexByTokens = i;
                tokenBudgetExceeded = true;
                pendingTokenSplit = false;
            }

            if (i < lastUserIndex && turnCount < keepRecentTurns) {
                indexByTurns = i;
                turnCount += msg.role == RoleType.USER ? 1 : 0;
            }
        }

        return Math.max(indexByTurns, indexByTokens);
    }
    private String summarize(List<Message> messagesToSummarize) {
        String content = formatMessages(messagesToSummarize);
        if (content.isBlank()) {
            lastFailure = "the summarized segment contained no text";
            return "";
        }
        int targetTokens = Math.min(MAX_SUMMARY_TOKENS, Math.max(MIN_SUMMARY_TOKENS, maxContextTokens / 10));
        String prompt = String.format(Prompts.COMPRESSION_PROMPT, (int) (targetTokens * 0.75), content);
        String summary = callLLM(prompt);
        if (summary.isBlank()) return "";
        return Prompts.COMPRESSION_SUMMARY_PREFIX + summary + Prompts.COMPRESSION_SUMMARY_SUFFIX;
    }
    private String callLLM(String prompt) {
        try {
            var msgs = List.of(Message.of(RoleType.USER, prompt));
            var request = CompletionRequest.of(msgs, null, 0.3, summaryModel, "memory-compressor");
            var response = llmProvider.completion(request);
            reportUsage(response);

            if (response != null && response.choices != null && !response.choices.isEmpty()) {
                var choice = response.choices.getFirst();
                if (choice.message != null && choice.message.content != null) {
                    return choice.message.content.trim();
                }
            }
        } catch (Exception e) {
            lastFailure = "summarization call failed: " + e.getMessage();
            LOGGER.error("Failed to call LLM for compression", e);
        }
        return "";
    }
    private void reportUsage(CompletionResponse response) {
        if (llmUsageSink == null || response == null || response.usage == null) {
            return;
        }
        try {
            llmUsageSink.accept(summaryModel, response.usage);
        } catch (Exception e) {
            LOGGER.warn("Failed to report compression usage", e);
        }
    }
    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder(1024);
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) {
                continue;
            }

            if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                String toolNames = msg.toolCalls.stream()
                    .map(tc -> tc.function != null ? tc.function.name : "unknown")
                    .collect(java.util.stream.Collectors.joining(", "));
                sb.append("Assistant: [Called tools: ").append(toolNames).append("]\n");
            }

            String joined = msg.getJoinedTextContent();
            String content = joined != null ? joined : "";
            if (!content.isBlank()) {
                String role = switch (msg.role) {
                    case USER -> "User";
                    case ASSISTANT -> "Assistant";
                    case TOOL -> "Tool";
                    default -> "Unknown";
                };
                sb.append(role).append(": ").append(content).append('\n');
            }
        }
        return sb.toString();
    }
    public String compressToolResult(String toolName, String result, String sessionId) {
        return compressToolResult(toolName, result, sessionId, null);
    }
    public String compressToolResult(String toolName, String result, String sessionId, Sandbox sandbox) {
        return ToolResultSpill.compress(toolName, result, sessionId, maxToolResultTokens, sandbox);
    }
    public boolean shouldCompressToolResult(String result) {
        return ToolResultSpill.exceedsLimit(result, maxToolResultTokens);
    }
    public int getMaxContextTokens() {
        return maxContextTokens;
    }
    public int getMaxToolResultTokens() {
        return maxToolResultTokens;
    }
    private void notifyStarted(int beforeCount, int compressingCount) {
        for (CompressionListener l : listeners) {
            l.onCompression(beforeCount, compressingCount, false);
        }
    }

    private void notifyCompleted(int beforeCount, int afterCount) {
        for (CompressionListener l : listeners) {
            l.onCompression(beforeCount, afterCount, true);
        }
    }
}
