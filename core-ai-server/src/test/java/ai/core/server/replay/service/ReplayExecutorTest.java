package ai.core.server.replay.service;

import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySample;
import ai.core.server.replay.domain.ReplaySampleStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ai.core.server.replay.domain.ReplaySampleStatus.CANCELLED;
import static ai.core.server.replay.domain.ReplaySampleStatus.COMPLETED;
import static ai.core.server.replay.domain.ReplaySampleStatus.ERROR;
import static ai.core.server.replay.domain.ReplaySampleStatus.RUNNING;

/**
 * Replay executor core logic: run param overrides on the decoded request and
 * sample-to-run status aggregation.
 *
 * @author stephen
 */
class ReplayExecutorTest {
    private static List<ReplaySample> samples(ReplaySampleStatus... statuses) {
        return java.util.stream.IntStream.range(0, statuses.length)
                .mapToObj(index -> {
                    var sample = new ReplaySample();
                    sample.index = index;
                    sample.status = statuses[index];
                    return sample;
                })
                .toList();
    }

    private final ReplayExecutor executor = new ReplayExecutor();

    @Test
    void buildRequestAppliesRunParamOverrides() {
        var run = new ReplayRun();
        run.request = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":0.9}";
        run.model = "gpt-5.1";
        run.temperature = 0.3;
        run.reasoningEffort = "high";

        var request = executor.buildRequest(run);

        assertEquals("gpt-5.1", request.model);
        assertEquals(0.3D, request.temperature);
        assertEquals(ai.core.llm.domain.ReasoningEffort.HIGH, request.reasoningEffort);
    }

    @Test
    void buildRequestKeepsRequestValuesWhenRunParamsAbsent() {
        var run = new ReplayRun();
        run.request = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":0.9}";

        var request = executor.buildRequest(run);

        assertEquals(0.9D, request.temperature);
        assertNull(request.reasoningEffort);
    }

    @Test
    void buildRequestIgnoresUnknownReasoningEffort() {
        var run = new ReplayRun();
        run.request = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        run.reasoningEffort = "turbo";

        var request = executor.buildRequest(run);

        assertNull(request.reasoningEffort);
    }

    @Test
    void aggregateAllCompleted() {
        assertEquals(ReplayRunStatus.COMPLETED, executor.aggregate(samples(COMPLETED, COMPLETED)));
    }

    @Test
    void aggregateAllCancelled() {
        assertEquals(ReplayRunStatus.CANCELLED, executor.aggregate(samples(CANCELLED, CANCELLED)));
    }

    @Test
    void aggregateMixedCompletedAndFailedIsPartial() {
        assertEquals(ReplayRunStatus.PARTIAL, executor.aggregate(samples(COMPLETED, ERROR)));
    }

    @Test
    void aggregateAllFailedIsError() {
        assertEquals(ReplayRunStatus.ERROR, executor.aggregate(samples(ERROR, ERROR)));
    }

    @Test
    void aggregateStillRunningIsRunning() {
        assertEquals(ReplayRunStatus.RUNNING, executor.aggregate(samples(RUNNING, COMPLETED)));
    }

    @Test
    void aggregateCancelledAndFailedIsError() {
        assertEquals(ReplayRunStatus.ERROR, executor.aggregate(samples(CANCELLED, ERROR)));
    }
}
