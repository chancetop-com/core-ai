package ai.core.server.runtime;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The series must distinguish "max pod" from "fleet sum": a fleet of two pods reporting 10 sessions
 * each is 20 sessions of capacity, not 10, and summing raw rows would double count a pod that reports
 * twice in one minute.
 *
 * @author core-ai
 */
class RuntimeSampleServiceSeriesTest {
    private static List<RuntimeSample> samples() {
        var base = ZonedDateTime.parse("2026-11-24T00:00:00Z");
        return List.of(
                sample("pod-a", base, 10, 80, 70),
                sample("pod-b", base, 5, 60, 60),
                sample("pod-a", base.plusMinutes(1), 12, 80, 70),
                sample("pod-b", base.plusMinutes(1), 6, 60, 60));
    }

    private static RuntimeSample sample(String pod, ZonedDateTime at, int sessions, double heap, double oldGen) {
        var sample = new RuntimeSample();
        sample.pod = pod;
        sample.collectedAt = at;
        sample.sessionsActive = sessions;
        sample.inFlightTurns = 1;
        sample.heapUsedPercent = heap;
        sample.oldGenPercent = oldGen;
        sample.heapAfterGcPercent = oldGen;
        sample.containerMemoryPercent = 50.0;
        sample.runsQueued = 0;
        sample.sandboxesLive = 0;
        return sample;
    }

    @Test
    void separatesMaxPodPeaksFromFleetSums() {
        var service = new RuntimeSampleService();
        service.sampleStore = new RuntimeSampleStore() {
            @Override
            public void insert(RuntimeSample sample) {
            }

            @Override
            public int bulkInsert(List<RuntimeSample> samples) {
                return 0;
            }

            @Override
            public List<RuntimeSample> range(ZonedDateTime from, ZonedDateTime to, int limit) {
                return samples();
            }
        };
        service.runtimeSettingsService = mock(RuntimeSettingsService.class);

        var series = service.series(1, 5, "admin");

        var point = series.points.getFirst();
        assertEquals(2, point.podsReporting);
        assertEquals(12, point.sessionsPeak);
        assertEquals(18, point.sessionsSumPeak);
        assertEquals(70.0, point.oldGenPeakPercent);
        assertEquals(70.0, point.heapAvgPercent);
        assertEquals(2, point.inFlightTurnsSumPeak);
    }
}
