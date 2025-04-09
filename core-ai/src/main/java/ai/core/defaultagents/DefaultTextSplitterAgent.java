package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.document.TextChunk;
import ai.core.llm.LLMProvider;
import core.framework.util.Strings;

import java.util.Arrays;
import java.util.List;

/**
 * @author stephen
 */
public class DefaultTextSplitterAgent {
    private static final String SPECIAL_SYMBOL_SPLITTER_SUMMARY = "__llm_splitter_summary__";
    private static final String SPECIAL_SYMBOL_SPLITTER_DELIMITER = "__llm_splitter_delimiter__";
    private final Agent agent;

    public DefaultTextSplitterAgent(LLMProvider llmProvider, String model, int length) {
        this.agent = Agent.builder()
                .name("text-splitter-agent")
                .description("This agent is used to split text into chunks with summaries.")
                .systemPrompt(Strings.format("""
                        You are an assistant who helps users split long texts into shorter chunks with summaries.
                        Users will provide you with a long text, and you need to read through the entire text and split it into reasonable segments that every segment is not longer than {}.
                        These segments should be semantically independent, and you must also create a brief summary for each segment, ensuring that all key information is included in the summary.
                        Output requirement:
                        1. A segment of the original text followed by a special symbol {}, then the summary of this text segment, and finally another special symbol {}.
                        2. The segment should be no longer than the specified length.
                        Output example:
                        Original text paragraph1
                        {}
                        Summary of paragraph1
                        {}
                        Original text paragraph2
                        {}
                        Summary of paragraph2
                        """, length,
                        SPECIAL_SYMBOL_SPLITTER_SUMMARY, SPECIAL_SYMBOL_SPLITTER_DELIMITER,
                        SPECIAL_SYMBOL_SPLITTER_SUMMARY,
                        SPECIAL_SYMBOL_SPLITTER_DELIMITER,
                        SPECIAL_SYMBOL_SPLITTER_SUMMARY))
                .promptTemplate("""
                        Original text:
                        {{{text}}}
                        """)
                .model(model)
                .llmProvider(llmProvider).build();
    }

    public List<TextChunk> split(String text) {
        var rsp = agent.run(text, null);
        return Arrays.stream(rsp.split(SPECIAL_SYMBOL_SPLITTER_DELIMITER)).map(v -> {
            var splits = v.split(SPECIAL_SYMBOL_SPLITTER_SUMMARY);
            if (splits.length < 2) {
                return new TextChunk(splits[0], null);
            } else {
                return new TextChunk(splits[0], splits[1]);
            }
        }).toList();
    }
}
