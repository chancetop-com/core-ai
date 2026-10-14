package ai.core.api.server.runtime;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Bucketed runtime history for the dashboard charts. Gauges are the peak across the pods that reported
 * inside the bucket; the counters are the increments observed during it, so "nothing was refused" and
 * "40 were refused" are distinguishable after the fact.
 *
 * @author stephen
 */
public class RuntimeSeriesView {
    @Property(name = "bucket_minutes")
    public Integer bucketMinutes;

    @Property(name = "points")
    public List<Point> points;

    public static class Point {
        @Property(name = "bucket_start")
        public ZonedDateTime bucketStart;

        @Property(name = "pods_reporting")
        public Integer podsReporting;

        @Property(name = "heap_peak_percent")
        public Double heapPeakPercent;

        @Property(name = "heap_avg_percent")
        public Double heapAvgPercent;

        @Property(name = "in_flight_turns_peak")
        public Integer inFlightTurnsPeak;

        @Property(name = "sessions_peak")
        public Integer sessionsPeak;

        @Property(name = "runs_queued_peak")
        public Integer runsQueuedPeak;

        @Property(name = "sandboxes_peak")
        public Integer sandboxesPeak;

        @Property(name = "span_ingest_depth_peak")
        public Integer spanIngestDepthPeak;

        /** Increments inside the bucket, summed over pods. */
        @Property(name = "turns_saturated_delta")
        public Long turnsSaturatedDelta;

        @Property(name = "turns_rejected_delta")
        public Long turnsRejectedDelta;

        @Property(name = "runs_rejected_delta")
        public Long runsRejectedDelta;

        @Property(name = "sandboxes_rejected_delta")
        public Long sandboxesRejectedDelta;

        @Property(name = "span_ingest_dropped_delta")
        public Long spanIngestDroppedDelta;
    }
}
