package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class MemoryTriggerServiceTest {

    @TempDir
    Path workspace;

    /**
     * The app-server engine serves several live sessions in one process. An extraction keeps its
     * cursor per agent; a global cursor made one session skip (or re-read) another session's history.
     */
    @Test
    void extractionCursorIsScopedToTheCallingAgent() {
        var service = MemoryTriggerService.getInstance(workspace);
        var first = agentWithHistory("first");
        var second = agentWithHistory("second");

        advanceCursor(service.buildMemoryTools(first), 3);

        assertEquals(3, service.extractionCursor(first));
        assertEquals(-1, service.extractionCursor(second), "another agent's cursor must not move");
    }

    /**
     * The activity report must carry the agent the run was forked from, so the desktop shows the
     * Memory chip in the session that asked — not in whichever session was built last.
     */
    @Test
    void activityReportsTheCallingAgent() {
        var service = MemoryTriggerService.getInstance(workspace);
        var caller = agentWithHistory("caller");
        var other = agentWithHistory("other");
        var reported = new CopyOnWriteArrayList<Agent>();
        service.addActivityListener((agent, report) -> {
            if (report.trigger() == MemoryExtractionReport.Trigger.EXPLICIT) reported.add(agent);
        });

        service.runExplicitMemoryExtraction(caller, "remember this");

        assertEquals(2, reported.size(), "started + completed");
        assertSame(caller, reported.get(0));
        assertSame(caller, reported.get(1));
        assertEquals(-1, service.extractionCursor(other), "the other agent's cursor must not move");
    }

    private Agent agentWithHistory(String name) {
        var agent = Agent.builder().name(name).llmProvider(new StubLLMProvider()).build();
        agent.restoreHistory(List.of(
                Message.of(RoleType.USER, "one"),
                Message.of(RoleType.ASSISTANT, "two"),
                Message.of(RoleType.USER, "three"),
                Message.of(RoleType.ASSISTANT, "four")));
        return agent;
    }

    private void advanceCursor(List<ToolCall> tools, int cursor) {
        var advance = tools.stream()
                .filter(tool -> ExtractionCursorTool.ADVANCE_TOOL_NAME.equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        var result = advance.execute("{\"cursor\":" + cursor + "}");
        assertFalse(result.isFailed(), result.getResult());
    }

    private static final class StubLLMProvider extends LLMProvider {
        private StubLLMProvider() {
            super(new LLMProviderConfig("stub", 0.0, "stub-embedding"));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return null;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return null;
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            return null;
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            return null;
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            return null;
        }

        @Override
        public String name() {
            return "stub";
        }
    }
}
