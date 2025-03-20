package ai.core.document.textsplitters;

import ai.core.defaultagents.DefaultTextSplitterAgent;
import ai.core.document.TextChunk;
import ai.core.document.TextSplitter;
import ai.core.llm.LLMProvider;

import java.util.List;

/**
 * @author stephen
 */
public class LLMTextSplitter implements TextSplitter {
    private final DefaultTextSplitterAgent agent;

    public LLMTextSplitter(LLMProvider llmProvider) {
        this.agent = new DefaultTextSplitterAgent(llmProvider, "DeepSeek-V3", RecursiveCharacterTextSplitter.DEFAULT_CODE_BLOCK_LINES * RecursiveCharacterTextSplitter.DEFAULT_CODE_LINE_TOKENS);
    }

    public LLMTextSplitter(LLMProvider llmProvider, String model, int length) {
        this.agent = new DefaultTextSplitterAgent(llmProvider, model, length);
    }

    @Override
    public List<TextChunk> split(String text) {
        return agent.split(text);
    }
}
