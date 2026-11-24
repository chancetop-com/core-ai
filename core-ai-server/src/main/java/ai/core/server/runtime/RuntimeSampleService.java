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

    private static void applyRuntime(RuntimePodView view, RuntimeSample sample) {
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
    }

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
        view.oldGenPercent = sample.oldGenPercent;
        view.gcKind = sample.gcKind;
        view.gcReclaimedBytes = sample.gcReclaimedBytes;
        view.sessionsActive = sample.sessionsActive;
        view.sessionEstimateVersion = sample.sessionEstimateVersion;
        view.sessionsEstimatedBytesTotal = sample.sessionsEstimatedBytesTotal;
        view.sessionsEstimatedBytesP50 = sample.sessionsEstimatedBytesP50;
        view.sessionsEstimatedBytesP90 = sample.sessionsEstimatedBytesP90;
        view.sessionsContextTokensP50 = sample.sessionsContextTokensP50;
        view.sessionsInlineImageBytesTotal = sample.sessionsInlineImageBytesTotal;
        view.sessionsEstimatedBytesPeakTotal = sample.sessionsEstimatedBytesPeakTotal;
        view.sessionsEstimatedBytesPeakMax = sample.sessionsEstimatedBytesPeakMax;
        view.sessionsInlineImageBytesPeakTotal = sample.sessionsInlineImageBytesPeakTotal;
        view.sessionsMediaRefBytesTotal = sample.sessionsMediaRefBytesTotal;
        view.sessionsIdleSecondsP50 = sample.sessionsIdleSecondsP50;
        view.sessionsCreatedTotal = sample.sessionsCreatedTotal;
        view.sessionsClosedTotal = sample.sessionsClosedTotal;
        view.sessionTurnBucketCounts = sample.sessionTurnBucketCounts;
        applyRuntime(view, sample);
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
        private final Map<Long, Map<String, RuntimeSample>> minutePerPod = new TreeMap<>();
        private final Map<String, Double> heapSumPerPod = new HashMap<>();
        private final Map<String, Integer> heapSamplesPerPod = new HashMap<>();
        private Double heapPeak;
        private Double oldGenPeak;
        private Double afterGcPeak;
        private Double containerPeak;
        private Integer inFlightPeak;
        private Integer sessionsPeak;
        private Integer runsQueuedPeak;
        private Integer sandboxesPeak;
        private Integer spanDepthPeak;
        private Integer sessionsSumPeak;
        private Integer inFlightSumPeak;
        private Integer runsQueuedSumPeak;
        private Integer sandboxesSumPeak;

        Bucket(long startMillis) {
            this.startMillis = startMillis;
        }

        void accept(RuntimeSample sample) {
            pods.add(sample.pod);
            var current = lastPerPod.get(sample.pod);
            if (current == null || !sample.collectedAt.isBefore(current.collectedAt)) {
                lastPerPod.put(sample.pod, sample);
            }
            if (sample.heapUsedPercent != null) {
                heapSumPerPod.merge(sample.pod, sample.heapUsedPercent, Double::sum);
                heapSamplesPerPod.merge(sample.pod, 1, Integer::sum);
                if (heapPeak == null || sample.heapUsedPercent > heapPeak) heapPeak = sample.heapUsedPercent;
            }
            oldGenPeak = max(oldGenPeak, sample.oldGenPercent);
            afterGcPeak = max(afterGcPeak, sample.heapAfterGcPercent);
            containerPeak = max(containerPeak, sample.containerMemoryPercent);
            inFlightPeak = max(inFlightPeak, sample.inFlightTurns);
            sessionsPeak = max(sessionsPeak, sample.sessionsActive);
            runsQueuedPeak = max(runsQueuedPeak, sample.runsQueued);
            sandboxesPeak = max(sandboxesPeak, sample.sandboxesLive);
            spanDepthPeak = max(spanDepthPeak, sample.spanIngestDepth);
            var minute = sample.collectedAt.toInstant().toEpochMilli() / 60_000;
            var perPod = minutePerPod.computeIfAbsent(minute, key -> new HashMap<>());
            var currentInMinute = perPod.get(sample.pod);
            if (currentInMinute == null || !sample.collectedAt.isBefore(currentInMinute.collectedAt)) {
                perPod.put(sample.pod, sample);
            }
        }

        RuntimeSeriesView.Point toPoint(Map<String, RuntimeSample> previousPerPod) {
            var point = new RuntimeSeriesView.Point();
            point.bucketStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZonedDateTime.now().getZone());
            point.podsReporting = pods.size();
            point.heapPeakPercent = heapPeak;
            point.heapAvgPercent = averagePerPodHeap();
            point.oldGenPeakPercent = oldGenPeak;
            point.heapAfterGcPeakPercent = afterGcPeak;
            point.containerPeakPercent = containerPeak;
            point.inFlightTurnsPeak = inFlightPeak;
            point.sessionsPeak = sessionsPeak;
            point.runsQueuedPeak = runsQueuedPeak;
            point.sandboxesPeak = sandboxesPeak;
            point.spanIngestDepthPeak = spanDepthPeak;
            applyFleetSums();
            point.sessionsSumPeak = sessionsSumPeak;
            point.inFlightTurnsSumPeak = inFlightSumPeak;
            point.runsQueuedSumPeak = runsQueuedSumPeak;
            point.sandboxesSumPeak = sandboxesSumPeak;
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

        /**
         * Fleet-average heap: first average each pod inside the bucket, then average the pods, so a pod
         * that reports more often does not dominate a pod that reports once.
         */
        private Double averagePerPodHeap() {
            double total = 0;
            var reported = 0;
            for (var entry : heapSumPerPod.entrySet()) {
                var count = heapSamplesPerPod.get(entry.getKey());
                if (count == null || count == 0) continue;
                total += entry.getValue() / count;
                reported++;
            }
            return reported == 0 ? null : Math.round(total / reported * 100) / 100.0;
        }

        /**
         * Fleet sums are aligned per minute: each pod contributes its last sample of that minute (carrying
         * the previous minute forward when it did not report), then the bucket keeps the maximum sum.
         * Summing raw rows instead would double-count a pod that reported twice in the same minute.
         */
        private void applyFleetSums() {
            var carried = new HashMap<String, RuntimeSample>();
            for (var minute : minutePerPod.values()) {
                carried.putAll(minute);
                int sessions = 0;
                int inFlight = 0;
                int runsQueued = 0;
                int sandboxes = 0;
                var reported = false;
                for (var sample : carried.values()) {
                    if (sample.sessionsActive != null) {
                        sessions += sample.sessionsActive;
                        reported = true;
                    }
                    if (sample.inFlightTurns != null) inFlight += sample.inFlightTurns;
                    if (sample.runsQueued != null) runsQueued += sample.runsQueued;
                    if (sample.sandboxesLive != null) sandboxes += sample.sandboxesLive;
                }
                if (!reported) continue;
                sessionsSumPeak = max(sessionsSumPeak, sessions);
                inFlightSumPeak = max(inFlightSumPeak, inFlight);
                runsQueuedSumPeak = max(runsQueuedSumPeak, runsQueued);
                sandboxesSumPeak = max(sandboxesSumPeak, sandboxes);
            }
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

        private Double max(Double current, Double candidate) {
            if (candidate == null) return current;
            return current == null || candidate > current ? candidate : current;
        }
    }
}
