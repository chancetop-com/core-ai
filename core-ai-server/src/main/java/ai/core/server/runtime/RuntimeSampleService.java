package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimePodView;
import ai.core.api.server.runtime.RuntimePodsView;
import ai.core.api.server.runtime.RuntimeSeriesView;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    MongoCollection<RuntimeSample> runtimeSampleCollection;
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
        var buckets = new LinkedHashMap<Long, Bucket>();
        for (var sample : samples) {
            var bucket = buckets.computeIfAbsent(bucketStart(sample, bucketMillis), Bucket::new);
            bucket.accept(sample);
        }
        var view = new RuntimeSeriesView();
        view.bucketMinutes = bucketMinutes;
        view.points = buckets.values().stream().map(Bucket::toPoint).toList();
        return view;
    }

    private List<RuntimeSample> fetch(ZonedDateTime from, ZonedDateTime to) {
        var query = new Query();
        query.filter = Filters.and(Filters.gte("collected_at", from), Filters.lte("collected_at", to));
        query.limit = MAX_SAMPLES;
        return runtimeSampleCollection.find(query);
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
        view.sessionsActive = sample.sessionsActive;
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

    /** Accumulates one time bucket: peaks for the gauges, per-pod increments for the counters. */
    private static final class Bucket {
        private final long startMillis;
        private final Map<String, RuntimeSample> firstPerPod = new LinkedHashMap<>();
        private final Map<String, RuntimeSample> lastPerPod = new LinkedHashMap<>();
        private int samples;
        private int podsReporting;
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
            if (firstPerPod.putIfAbsent(sample.pod, sample) == null) podsReporting++;
            lastPerPod.put(sample.pod, sample);
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

        RuntimeSeriesView.Point toPoint() {
            var point = new RuntimeSeriesView.Point();
            point.bucketStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZonedDateTime.now().getZone());
            point.podsReporting = podsReporting;
            point.heapPeakPercent = heapPeak;
            point.heapAvgPercent = samples == 0 ? null : Math.round(heapSum / samples * 100) / 100.0;
            point.inFlightTurnsPeak = inFlightPeak;
            point.sessionsPeak = sessionsPeak;
            point.runsQueuedPeak = runsQueuedPeak;
            point.sandboxesPeak = sandboxesPeak;
            point.spanIngestDepthPeak = spanDepthPeak;
            point.turnsSaturatedDelta = delta(sample -> sample.turnsSaturated);
            point.turnsRejectedDelta = delta(sample -> sample.turnsRejected);
            point.runsRejectedDelta = delta(sample -> sample.runsRejected);
            point.sandboxesRejectedDelta = delta(sample -> sample.sandboxesRejected);
            point.spanIngestDroppedDelta = delta(sample -> sample.spanIngestDropped);
            return point;
        }

        private Long delta(Function<RuntimeSample, Long> counter) {
            long total = 0;
            boolean any = false;
            for (var entry : lastPerPod.entrySet()) {
                var first = firstPerPod.get(entry.getKey());
                var firstValue = counter.apply(first);
                var lastValue = counter.apply(entry.getValue());
                if (firstValue == null || lastValue == null) continue;
                total += Math.max(0, lastValue - firstValue);
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
