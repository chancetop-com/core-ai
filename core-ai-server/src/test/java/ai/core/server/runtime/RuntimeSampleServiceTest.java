package ai.core.server.runtime;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The series read is the only place counters are turned into increments: the input may arrive in any
 * order (and each pod may be missing from a bucket), and an increment that happened between two buckets
 * must still be counted.
 *
 * @author stephen
 */
class RuntimeSampleServiceTest {
    @Test
    void bucketsAreOrderedAndDeltasCrossBucketBoundaries() {
        var hour = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS);
        // deliberately out of order: the service must not depend on row order
        var samples = List.of(
                sample("pod-a", hour.minusHours(1).plusMinutes(50), 90.0, 22),
                sample("pod-b", hour.minusHours(1).plusMinutes(20), 70.0, 1),
                sample("pod-a", hour.minusHours(2).plusMinutes(50), 80.0, 15),
                sample("pod-b", hour.minusHours(2).plusMinutes(10), 60.0, 0));

        var points = service(samples).series(3, 60, "admin").points;

        assertEquals(2, points.size());
        assertTrue(points.get(0).bucketStart.isBefore(points.get(1).bucketStart));
        assertEquals(2, points.get(0).podsReporting);
        assertEquals(80.0, points.get(0).heapPeakPercent, 0.001);
        assertEquals(15L, points.get(0).turnsRejectedDelta);
        assertEquals(8L, points.get(1).turnsRejectedDelta);
        assertEquals(0, points.get(1).counterResets);
    }

    @Test
    void counterGoingBackwardsCountsAsARestart() {
        var hour = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS);
        var samples = List.of(
                sample("pod-a", hour.minusHours(2).plusMinutes(10), 80.0, 100),
                sample("pod-a", hour.minusHours(2).plusMinutes(50), 80.0, 120),
                sample("pod-a", hour.minusHours(1).plusMinutes(10), 70.0, 5));

        var points = service(samples).series(3, 60, "admin").points;

        assertEquals(2, points.size());
        assertEquals(120L, points.get(0).turnsRejectedDelta);
        assertEquals(5L, points.get(1).turnsRejectedDelta);
        assertEquals(1, points.get(1).counterResets);
    }

    @Test
    void aPodMissingFromABucketKeepsItsBaseline() {
        var hour = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS);
        var samples = List.of(
                sample("pod-a", hour.minusHours(3).plusMinutes(10), 80.0, 10),
                sample("pod-a", hour.minusHours(1).plusMinutes(10), 80.0, 40));

        var points = service(samples).series(4, 60, "admin").points;

        assertEquals(2, points.size());
        assertEquals(10L, points.get(0).turnsRejectedDelta);
        assertEquals(30L, points.get(1).turnsRejectedDelta);
        assertEquals(0, points.get(1).counterResets);
    }

    private RuntimeSampleService service(List<RuntimeSample> samples) {
        var service = new RuntimeSampleService();
        service.sampleStore = new ListStore(samples);
        service.runtimeSettingsService = mock(RuntimeSettingsService.class);
        return service;
    }

    private RuntimeSample sample(String pod, ZonedDateTime at, double heapPercent, long turnsRejected) {
        var sample = new RuntimeSample();
        sample.id = pod + at.toInstant().toEpochMilli();
        sample.pod = pod;
        sample.collectedAt = at;
        sample.heapUsedPercent = heapPercent;
        sample.inFlightTurns = 1;
        sample.turnsRejected = turnsRejected;
        return sample;
    }

    private static final class ListStore implements RuntimeSampleStore {
        private final List<RuntimeSample> samples;

        private ListStore(List<RuntimeSample> samples) {
            this.samples = samples;
        }

        @Override
        public void insert(RuntimeSample sample) {
        }

        @Override
        public int bulkInsert(List<RuntimeSample> samples) {
            return 0;
        }

        @Override
        public List<RuntimeSample> range(ZonedDateTime from, ZonedDateTime to, int limit) {
            return samples;
        }
    }
}
