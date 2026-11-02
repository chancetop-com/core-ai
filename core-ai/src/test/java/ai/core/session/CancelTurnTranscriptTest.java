package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.session.transcript.TranscriptMetadata;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.session.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ESC cancels a turn by interrupting the thread that runs it. The transcript writes that close the
 * turn happen on that same thread, so they must survive the interrupt and must never be able to
 * swallow the turn's terminal event.
 *
 * @author stephen
 */
class CancelTurnTranscriptTest {

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30));
    }

    private static Agent agent(String sessionId, LLMProvider provider) {
        var agent = Agent.builder().llmProvider(provider).build();
        agent.setExecutionContext(ExecutionContext.builder().sessionId(sessionId).build());
        return agent;
    }

    @TempDir
    Path dir;

    private TranscriptRecorder recorder(Agent agent, String sessionId) {
        var store = TranscriptStore.open(dir, sessionId);
        var metadata = new TranscriptMetadata(dir.toString(), "test", "cli", agent::getModel, () -> "off");
        var recorder = new TranscriptRecorder(store, metadata, () -> 0, record -> {
        });
        recorder.sessionStart(agent.getMessages());
        return recorder;
    }

    @Test
    void cancelledTurnStillEmitsItsTerminalEventAndKeepsTheTranscriptWritable() throws InterruptedException {
        var agent = agent("cancel-transcript", new CancelTurnPersistenceTest.BlockingLLMProvider());
        var recorder = recorder(agent, "cancel-transcript");
        var session = new InProcessAgentSession("cancel-transcript", agent, true,
                new InMemoryToolPermissionStore(), false, recorder);
        session.onEvent(recorder);

        var running = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onStatusChange(StatusChangeEvent event) {
                if (event.status == SessionStatus.RUNNING) running.countDown();
            }

            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                if (Boolean.TRUE.equals(event.cancelled)) cancelled.countDown();
            }
        });

        session.sendMessage("hello");
        assertTrue(running.await(10, TimeUnit.SECONDS), "turn should start");
        session.cancelTurn();
        assertTrue(cancelled.await(10, TimeUnit.SECONDS),
                "a cancelled turn must still dispatch its terminal event");

        // the same recorder and channel must still work for the next turn
        recorder.beginTurn(List.of(), "user", "mock-model", null);
        recorder.completeTurn(TurnCompleteEvent.of("cancel-transcript", "next", null));
        assertTrue(recorder.store().records().stream()
                        .anyMatch(record -> "next".equals(record.text("output"))),
                "the transcript must be writable after a cancellation interrupt");

        session.close();
    }

    @Test
    void transcriptFailureCannotSwallowTheTerminalEvent() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("done"));
        var agent = agent("broken-transcript", provider);
        var recorder = new TranscriptRecorder(TranscriptStore.open(dir, "broken-transcript"),
                new TranscriptMetadata(dir.toString(), "test", "cli", agent::getModel, () -> "off"),
                () -> 0, record -> {
        }) {
            @Override
            public void completeTurn(TurnCompleteEvent event) {
                throw new IllegalStateException("disk is full");
            }
        };
        var session = new InProcessAgentSession("broken-transcript", agent, true,
                new InMemoryToolPermissionStore(), false, recorder);

        var completed = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                completed.countDown();
            }
        });

        session.sendMessage("hello");
        assertTrue(completed.await(10, TimeUnit.SECONDS),
                "a transcript write failure must not swallow the terminal event");

        session.close();
    }
}
