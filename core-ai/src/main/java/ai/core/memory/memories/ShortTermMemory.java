package ai.core.memory.memories;

import ai.core.document.Document;
import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import ai.core.memory.Memory;
import ai.core.memory.MemoryType;
import ai.core.memory.compression.CompressionStrategy;
import ai.core.memory.compression.LLMCompressionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Short-term memory implementation with automatic compression.
 * Maintains recent conversation context within a token limit,
 * automatically compressing older content into a summary when limit is exceeded.
 *
 * @author Xander
 */
public class ShortTermMemory implements Memory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemory.class);
    private static final int DEFAULT_TOKEN_LIMIT = 5000;
    private static final double COMPRESSION_THRESHOLD = 0.8;

    private final Deque<String> recentMessages = new LinkedList<>();
    private final Set<Integer> seenContentHashes = new HashSet<>();
    private final int tokenLimit;
    private final CompressionStrategy compressionStrategy;

    private String compressedSummary = "";

    public ShortTermMemory(int tokenLimit, CompressionStrategy compressionStrategy) {
        this.tokenLimit = tokenLimit;
        this.compressionStrategy = compressionStrategy;
    }

    public ShortTermMemory(int tokenLimit, LLMProvider llmProvider) {
        this(tokenLimit, new LLMCompressionStrategy(llmProvider));
    }

    public ShortTermMemory(LLMProvider llmProvider) {
        this(DEFAULT_TOKEN_LIMIT, llmProvider);
    }

    @Override
    public String getType() {
        return MemoryType.SHORT_TERM.getDisplayName();
    }

    @Override
    public void save(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            if (message.content == null || message.content.isEmpty()) {
                continue;
            }
            int contentHash = message.content.hashCode();
            if (!seenContentHashes.contains(contentHash)) {
                seenContentHashes.add(contentHash);
                addMessage(message.content);
            }
        }
    }

    @Override
    public List<Document> retrieve(String query) {
        List<Document> result = new ArrayList<>();
        if (compressedSummary != null && !compressedSummary.isEmpty()) {
            result.add(new Document("[Previous Context Summary]\n" + compressedSummary, null, null));
        }
        for (String content : recentMessages) {
            result.add(new Document(content, null, null));
        }
        return result;
    }

    @Override
    public void clear() {
        recentMessages.clear();
        seenContentHashes.clear();
        compressedSummary = "";
    }

    @Override
    public boolean isEmpty() {
        return recentMessages.isEmpty() && (compressedSummary == null || compressedSummary.isEmpty());
    }

    private void addMessage(String content) {
        recentMessages.addLast(content);
        compressIfNeeded();
    }

    private void compressIfNeeded() {
        int currentTokens = calculateTotalTokens();
        if (currentTokens <= tokenLimit) {
            return;
        }

        int targetTokens = (int) (tokenLimit * COMPRESSION_THRESHOLD);
        StringBuilder contentToCompress = new StringBuilder();

        while (currentTokens > targetTokens && !recentMessages.isEmpty()) {
            String oldestMessage = recentMessages.pollFirst();
            contentToCompress.append(oldestMessage).append('\n');
            currentTokens = calculateTotalTokens();
        }

        if (!contentToCompress.isEmpty()) {
            updateCompressedSummary(contentToCompress.toString());
        }
    }

    private void updateCompressedSummary(String newContent) {
        try {
            compressedSummary = compressionStrategy.compressIncremental(compressedSummary, newContent);
        } catch (Exception e) {
            LOGGER.warn("Failed to compress memory content, discarding overflow", e);
        }
    }

    private int calculateTotalTokens() {
        int tokens = Tokenizer.tokenCount(compressedSummary);
        for (String message : recentMessages) {
            tokens += Tokenizer.tokenCount(message);
        }
        return tokens;
    }

    // Accessor methods for monitoring and testing

    public int getCurrentTokenCount() {
        return calculateTotalTokens();
    }

    public int getTokenLimit() {
        return tokenLimit;
    }

    public int getRecentMessageCount() {
        return recentMessages.size();
    }

    public String getCompressedSummary() {
        return compressedSummary;
    }

    public boolean hasCompressedSummary() {
        return compressedSummary != null && !compressedSummary.isEmpty();
    }
}
