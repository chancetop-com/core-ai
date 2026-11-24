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

        /** Retained pressure, max pod: the highest Old Gen share any replica reported in the bucket. */
        @Property(name = "old_gen_peak_percent")
        public Double oldGenPeakPercent;

        @Property(name = "heap_after_gc_peak_percent")
        public Double heapAfterGcPeakPercent;

        @Property(name = "container_peak_percent")
        public Double containerPeakPercent;

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

        /** Fleet sum: per-minute values summed over replicas, then the bucket maximum. */
        @Property(name = "sessions_sum_peak")
        public Integer sessionsSumPeak;

        @Property(name = "in_flight_turns_sum_peak")
        public Integer inFlightTurnsSumPeak;

        @Property(name = "runs_queued_sum_peak")
        public Integer runsQueuedSumPeak;

        @Property(name = "sandboxes_sum_peak")
        public Integer sandboxesSumPeak;

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

        /** Pods whose counters went backwards inside the bucket, i.e. a container restart. */
        @Property(name = "counter_resets")
        public Integer counterResets;
    }
}
