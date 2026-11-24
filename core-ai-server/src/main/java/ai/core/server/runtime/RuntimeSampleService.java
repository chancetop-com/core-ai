package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimePodView;
import ai.core.api.server.runtime.RuntimePodsView;
import ai.core.api.server.runtime.RuntimeSeriesView;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Read side of the runtime watermarks: it only ever reads {@code runtime_samples}, which the per-pod
 * collector job writes. Reads are plain range queries on {@code collected_at} and the bucketing happens
 * here, so the shapes the dashboard needs (fleet snapshot, trends) do not require new indexes per view.
 *
 * @author stephen
 */
public class RuntimeSampleService {
    private static final Duration POD_LOOKBACK = Duration.ofMinutes(10);
    private static final int MAX_SAMPLES = 50000;

    @Inject
    RuntimeSampleStore sampleStore;
    @Inject
    RuntimeSettingsService runtimeSettingsService;

    public RuntimePodsView pods(String userId) {
        var view = new RuntimePodsView();
        view.limits = runtimeSettingsService.get(userId);
        var since = ZonedDateTime.now().minus(POD_LOOKBACK);
        var samples = fetch(since, ZonedDateTime.now());
        Map<String, RuntimeSample> newestPerPod = new LinkedHashMap<>();
        for (var sample : samples) {
            var current = newestPerPod.get(sample.pod);
            if (current == null || sample.collectedAt.isAfter(current.collectedAt)) newestPerPod.put(sample.pod, sample);
        }
        var pods = new ArrayList<RuntimePodView>();
        for (var sample : newestPerPod.values()) {
            pods.add(toView(sample));
        }
        pods.sort(Comparator.comparing(pod -> pod.pod));
        view.pods = pods;
        return view;
    }

    public RuntimeSeriesView series(int hours, int bucketMinutes, String userId) {
        runtimeSettingsService.requireAdminUser(userId);
        if (hours < 1 || hours > 24 * 7) throw new BadRequestException("hours must be between 1 and 168");
        if (bucketMinutes < 1 || bucketMinutes > 24 * 60) throw new BadRequestException("bucketMinutes must be between 1 and 1440");
        var now = ZonedDateTime.now();
        var samples = fetch(now.minusHours(hours), now);
        long bucketMillis = bucketMinutes * 60_000L;
        var buckets = new TreeMap<Long, Bucket>();
        for (var sample : samples) {
            buckets.computeIfAbsent(bucketStart(sample, bucketMillis), Bucket::new).accept(sample);
        }
        var view = new RuntimeSeriesView();
        view.bucketMinutes = bucketMinutes;
        // counters are cumulative: each bucket's increment is relative to the previous sample of the
        // same pod, which may be in an earlier bucket (or missing entirely)
        var previousPerPod = new HashMap<String, RuntimeSample>();
        var points = new ArrayList<RuntimeSeriesView.Point>(buckets.size());
        for (var bucket : buckets.values()) {
            points.add(bucket.toPoint(previousPerPod));
        }
        view.points = points;
        return view;
    }

    private List<RuntimeSample> fetch(ZonedDateTime from, ZonedDateTime to) {
        return sampleStore.range(from, to, MAX_SAMPLES);
    }

    private long bucketStart(RuntimeSample sample, long bucketMillis) {
        return sample.collectedAt.toInstant().toEpochMilli() / bucketMillis * bucketMillis;
    }

    private RuntimePodView toView(RuntimeSample sample) {
        var view = new RuntimePodView();
        view.pod = sample.pod;
        view.collectedAt = sample.collectedAt;
        view.ageSeconds = Math.max(0, Duration.between(sample.collectedAt, ZonedDateTime.now()).toSeconds());
        view.heapUsedPercent = sample.heapUsedPercent;
        view.heapUsed = sample.heapUsed;
        view.heapMax = sample.heapMax;
        view.heapBeforeGcPercent = sample.heapBeforeGcPercent;
        view.heapAfterGcPercent = sample.heapAfterGcPercent;
        view.gcAgeMs = sample.gcAgeMs;
        view.containerMemoryPercent = sample.containerMemoryPercent;
        view.sessionsActive = sample.sessionsActive;
        view.sessionEstimateVersion = sample.sessionEstimateVersion;
        view.sessionsEstimatedBytesTotal = sample.sessionsEstimatedBytesTotal;
        view.sessionsEstimatedBytesP50 = sample.sessionsEstimatedBytesP50;
        view.sessionsEstimatedBytesP90 = sample.sessionsEstimatedBytesP90;
        view.sessionsContextTokensP50 = sample.sessionsContextTokensP50;
        view.sessionsInlineImageBytesTotal = sample.sessionsInlineImageBytesTotal;
        view.sessionsIdleSecondsP50 = sample.sessionsIdleSecondsP50;
        view.sessionsCreatedTotal = sample.sessionsCreatedTotal;
        view.sessionsClosedTotal = sample.sessionsClosedTotal;
        view.sessionTurnBucketCounts = sample.sessionTurnBucketCounts;
        view.inFlightTurns = sample.inFlightTurns;
        view.turnsSaturated = sample.turnsSaturated;
        view.turnsRejected = sample.turnsRejected;
        view.runsActive = sample.runsActive;
        view.runsQueued = sample.runsQueued;
        view.runsRejected = sample.runsRejected;
        view.sandboxesLive = sample.sandboxesLive;
        view.sandboxesRejected = sample.sandboxesRejected;
        view.spanIngestDepth = sample.spanIngestDepth;
        view.spanIngestDropped = sample.spanIngestDropped;
        view.claimsLive = sample.claimsLive;
        view.directCrsLive = sample.directCrsLive;
        view.createsTotal = sample.createsTotal;
        view.createsFailedTotal = sample.createsFailedTotal;
        return view;
    }

    /**
     * Accumulates one time bucket: peaks for the gauges, per-pod increments for the counters. The
     * counter baseline is the pod's last sample before this bucket, not the first sample inside it, so
     * an increment between two buckets is not lost; a value lower than its baseline means the process
     * restarted, in which case the current value is the increment and the pod is counted as reset.
     */
    private static final class Bucket {
        private final long startMillis;
        private final Set<String> pods = new HashSet<>();
        private final Map<String, RuntimeSample> lastPerPod = new HashMap<>();
        private final Set<String> resetPods = new HashSet<>();
        private int samples;
        private double heapSum;
        private Double heapPeak;
        private Integer inFlightPeak;
        private Integer sessionsPeak;
        private Integer runsQueuedPeak;
        private Integer sandboxesPeak;
        private Integer spanDepthPeak;

        Bucket(long startMillis) {
            this.startMillis = startMillis;
        }

        void accept(RuntimeSample sample) {
            pods.add(sample.pod);
            var current = lastPerPod.get(sample.pod);
            if (current == null || !sample.collectedAt.isBefore(current.collectedAt)) {
                lastPerPod.put(sample.pod, sample);
            }
            samples++;
            if (sample.heapUsedPercent != null) {
                heapSum += sample.heapUsedPercent;
                if (heapPeak == null || sample.heapUsedPercent > heapPeak) heapPeak = sample.heapUsedPercent;
            }
            inFlightPeak = max(inFlightPeak, sample.inFlightTurns);
            sessionsPeak = max(sessionsPeak, sample.sessionsActive);
            runsQueuedPeak = max(runsQueuedPeak, sample.runsQueued);
            sandboxesPeak = max(sandboxesPeak, sample.sandboxesLive);
            spanDepthPeak = max(spanDepthPeak, sample.spanIngestDepth);
        }

        RuntimeSeriesView.Point toPoint(Map<String, RuntimeSample> previousPerPod) {
            var point = new RuntimeSeriesView.Point();
            point.bucketStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZonedDateTime.now().getZone());
            point.podsReporting = pods.size();
            point.heapPeakPercent = heapPeak;
            point.heapAvgPercent = samples == 0 ? null : Math.round(heapSum / samples * 100) / 100.0;
            point.inFlightTurnsPeak = inFlightPeak;
            point.sessionsPeak = sessionsPeak;
            point.runsQueuedPeak = runsQueuedPeak;
            point.sandboxesPeak = sandboxesPeak;
            point.spanIngestDepthPeak = spanDepthPeak;
            point.turnsSaturatedDelta = delta(previousPerPod, sample -> sample.turnsSaturated);
            point.turnsRejectedDelta = delta(previousPerPod, sample -> sample.turnsRejected);
            point.runsRejectedDelta = delta(previousPerPod, sample -> sample.runsRejected);
            point.sandboxesRejectedDelta = delta(previousPerPod, sample -> sample.sandboxesRejected);
            point.spanIngestDroppedDelta = delta(previousPerPod, sample -> sample.spanIngestDropped);
            point.counterResets = resetPods.size();
            // carry this bucket's last sample forward as the next bucket's baseline
            lastPerPod.forEach(previousPerPod::put);
            return point;
        }

        private Long delta(Map<String, RuntimeSample> previousPerPod, Function<RuntimeSample, Long> counter) {
            long total = 0;
            boolean any = false;
            for (var entry : lastPerPod.entrySet()) {
                var lastValue = counter.apply(entry.getValue());
                if (lastValue == null) continue;
                var previous = previousPerPod.get(entry.getKey());
                var previousValue = previous == null ? null : counter.apply(previous);
                if (previousValue == null) {
                    // first observation of this pod: its counter already includes everything since start
                    total += lastValue;
                } else if (lastValue >= previousValue) {
                    total += lastValue - previousValue;
                } else {
                    // the counter went backwards: a container restart, the current value is the increment
                    total += lastValue;
                    resetPods.add(entry.getKey());
                }
                any = true;
            }
            return any ? total : null;
        }

        private Integer max(Integer current, Integer candidate) {
            if (candidate == null) return current;
            return current == null || candidate > current ? candidate : current;
        }
    }
}
