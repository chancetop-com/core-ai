package ai.core.memory.memories;

import ai.core.document.Document;
import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.Memory;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class ShortTermMemory extends Memory {
    private final LinkedList<String> memoryBuffer = new LinkedList<>();
    private final int tokenLimit;
    private final LLMProvider llmProvider;
    private String summaryBuffer = "";
    private static final String SUMMARIZATION_PROMPT = "Summarize the following conversation lines concisely, retaining key information to maintain context for future turns:\n";

    public ShortTermMemory(int tokenLimit, LLMProvider llmProvider) {
        this.tokenLimit = tokenLimit;
        this.llmProvider = llmProvider;
    }

    public ShortTermMemory(LLMProvider llmProvider) {
        this(5000, llmProvider); // Default 2000 tokens
    }

    @Override
    public void extractAndSave(List<Message> conversation) {
        // STM is updated dynamically via add(), but we can sync here if needed.
        // For now, we assume add() is called during the turn.
        // If we need to bulk load:
        for (Message msg : conversation) {
            if (!memoryBuffer.contains(msg.content)) { // Simple dedup check
                add(msg.content);
            }
        }
    }

    @Override
    public List<Document> retrieve(String query) {
        // Return Summary + Buffer
        List<Document> context = new LinkedList<>();
        if (summaryBuffer != null && !summaryBuffer.isEmpty()) {
            context.add(new Document("Previous Context Summary: " + summaryBuffer, null, null));
        }
        context.addAll(memoryBuffer.stream()
                .map(content -> new Document(content, null, null))
                .toList());
        return context;
    }

    @Override
    public void add(String text) {
        memoryBuffer.add(text);
        prune();
    }

    private void prune() {
        int currentTokens = calculateTokens();
        if (currentTokens <= tokenLimit) return;

        // Buffer to hold messages to be summarized
        StringBuilder toSummarize = new StringBuilder();
        
        // Remove from head until we are under limit (leaving some buffer for the new summary)
        // Heuristic: Try to free up at least 20% of space
        int targetTokens = (int) (tokenLimit * 0.8);
        
        while (currentTokens > targetTokens && !memoryBuffer.isEmpty()) {
            String msg = memoryBuffer.pollFirst();
            toSummarize.append(msg).append("\n");
            currentTokens = calculateTokens();
        }

        if (toSummarize.length() > 0) {
            updateSummary(toSummarize.toString());
        }
    }

    private int calculateTokens() {
        int tokens = Tokenizer.tokenCount(summaryBuffer);
        for (String msg : memoryBuffer) {
            tokens += Tokenizer.tokenCount(msg);
        }
        return tokens;
    }

    private void updateSummary(String newContent) {
        String prompt;
        if (summaryBuffer.isEmpty()) {
            prompt = SUMMARIZATION_PROMPT + newContent;
        } else {
            prompt = "Update the following summary with the new conversation lines.\n" +
                    "Current Summary:\n" + summaryBuffer + "\n" +
                    "New Lines:\n" + newContent + "\n" +
                    "New Summary:";
        }

        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, prompt)),null,null,null,null);
        // Use a separate call to avoid recursion or complex state
        try {
            var response = llmProvider.completion(request);
            this.summaryBuffer = response.choices.getFirst().message.content;
        } catch (Exception e) {
            // Fallback: just append if LLM fails (to avoid losing info), or log error
            this.summaryBuffer += "\n" + newContent;
        }
    }

    @Override
    public void clear() {
        memoryBuffer.clear();
        summaryBuffer = "";
    }

    @Override
    public List<Document> list() {
        return retrieve(null);
    }
}
