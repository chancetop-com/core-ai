package ai.core.server.runtime;

import ai.core.agent.AgentFootprint;
import ai.core.api.server.runtime.RuntimeStatsView;
import ai.core.server.run.AgentRunner;
import ai.core.server.sandbox.SandboxManager;
import ai.core.server.sandbox.agentsandbox.AgentSandbox;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionSnapshot;
import ai.core.server.trace.spi.LocalSpanProcessor;
import core.framework.inject.Inject;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the runtime observation snapshot: what this pod is holding right now (sessions, in-flight
 * turns, runs, sandboxes, heap) against the limits in force. This is the read side the limit
 * calibration depends on — without it the only visible resource signal is the container's memory.
 *
 * @author stephen
 */
public class RuntimeStatsService {
    private static final String[] TURN_BUCKETS = {"1", "2-3", "4-10", "11-20", "21+"};

    private static String turnBucket(int turns) {
        if (turns <= 1) return "1";
        if (turns <= 3) return "2-3";
        if (turns <= 10) return "4-10";
        if (turns <= 20) return "11-20";
        return "21+";
    }

    private static Long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return null;
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return percentileSorted(sorted, percentile);
    }

    private static long percentileSorted(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index);
    }

    @Inject
    RuntimeCapacityGuard guard;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    AgentRunner agentRunner;
    @Inject
    SandboxManager sandboxManager;

    public RuntimeStatsView stats() {
        var limits = guard.limits();
        var memory = JvmMemoryMetrics.snapshot();
        var view = new RuntimeStatsView();
        view.pod = podName();
        view.uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        view.memory = memoryStats(memory);
        view.sessions = sessionStats(limits);
        view.turns = turnStats(limits);
        view.runs = runStats(limits);
        view.sandbox = sandboxStats(limits);
        view.sandboxFleet = sandboxFleet();
        view.spanIngest = spanIngestStats();
        return view;
    }

    private RuntimeStatsView.SpanIngestStats spanIngestStats() {
        var stats = new RuntimeStatsView.SpanIngestStats();
        stats.queueDepth = LocalSpanProcessor.ingestQueueDepth();
        stats.capacity = LocalSpanProcessor.ingestQueueCapacity();
        stats.droppedTotal = LocalSpanProcessor.droppedSpans();
        return stats;
    }

    private RuntimeStatsView.MemoryStats memoryStats(JvmMemoryMetrics.Snapshot memory) {
        var stats = new RuntimeStatsView.MemoryStats();
        stats.heapUsed = memory.heapUsed();
        stats.heapMax = memory.heapMax();
        stats.heapUsedPercent = memory.heapUsedPercent();
        stats.heapBeforeGc = memory.heapBeforeGc();
        stats.heapBeforeGcPercent = memory.heapBeforeGcPercent();
        stats.heapAfterGc = memory.heapAfterGc();
        stats.heapAfterGcPercent = memory.heapAfterGcPercent();
        stats.gcId = memory.gcId();
        stats.gcAgeMs = memory.gcAgeMs();
        stats.containerMemory = memory.containerMemory();
        stats.containerMemoryMax = memory.containerMemoryMax();
        stats.containerMemoryPercent = memory.containerMemoryPercent();
        stats.rss = memory.rss();
        return stats;
    }

    private RuntimeStatsView.SessionStats sessionStats(RuntimeLimits limits) {
        var runtimeStats = sessionManager.runtimeStats;
        var snapshots = runtimeStats.snapshots();
        var aggregate = new SessionAggregate();
        for (var snapshot : snapshots) {
            aggregate.accept(snapshot);
        }
        var stats = new RuntimeStatsView.SessionStats();
        stats.active = snapshots.size();
        stats.max = limits.maxSessionsPerPod;
        stats.estimateVersion = AgentFootprint.ESTIMATE_VERSION;
        stats.createdTotal = runtimeStats.createdTotal();
        stats.closedTotal = runtimeStats.closedTotal();
        stats.idleEvictedTotal = runtimeStats.idleEvictedTotal();
        aggregate.applyTo(stats);
        return stats;
    }

    private RuntimeStatsView.TurnStats turnStats(RuntimeLimits limits) {
        var stats = new RuntimeStatsView.TurnStats();
        stats.inFlight = guard.inFlightTurns();
        stats.max = limits.maxInFlightTurnsPerPod;
        stats.rejectedTotal = guard.rejectedTotal();
        stats.saturatedTotal = guard.saturatedTotal();
        return stats;
    }

    private RuntimeStatsView.RunStats runStats(RuntimeLimits limits) {
        var stats = new RuntimeStatsView.RunStats();
        stats.active = agentRunner.activeRunCount();
        stats.queued = agentRunner.queuedRunCount();
        stats.maxQueue = limits.runQueueCapacity;
        stats.rejectedTotal = agentRunner.rejectedRunTotal();
        return stats;
    }

    private RuntimeStatsView.SandboxStats sandboxStats(RuntimeLimits limits) {
        var stats = new RuntimeStatsView.SandboxStats();
        stats.live = sandboxManager.activeCount();
        stats.max = limits.maxSandboxesPerPod;
        stats.rejectedTotal = sandboxManager.rejectedTotal();
        return stats;
    }

    private RuntimeStatsView.SandboxFleetStats sandboxFleet() {
        Map<String, Integer> byKind = new LinkedHashMap<>();
        Map<String, Integer> byUser = new LinkedHashMap<>();
        for (var entry : sandboxManager.getActiveSandboxes()) {
            var kind = entry.sandbox instanceof AgentSandbox agentSandbox
                    ? agentSandbox.kind().name().toLowerCase(Locale.ROOT) : "other";
            byKind.merge(kind, 1, Integer::sum);
            if (entry.userId != null) {
                byUser.merge(entry.userId, 1, Integer::sum);
            }
        }
        var stats = new RuntimeStatsView.SandboxFleetStats();
        stats.liveByKind = byKind;
        stats.liveByUser = byUser;
        var fleet = sandboxManager.fleet();
        stats.claimsLive = fleet.claims;
        stats.directCrsLive = fleet.directCrs;
        stats.oldestClaimAgeSeconds = fleet.oldestClaimAgeSeconds;
        stats.oldestCrAgeSeconds = fleet.oldestCrAgeSeconds;
        stats.createsTotal = fleet.createsTotal;
        stats.createsFailedTotal = fleet.createsFailedTotal;
        stats.lastCreateMs = fleet.lastCreateMs;
        return stats;
    }

    private String podName() {
        var hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) return hostname;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static final class SessionAggregate {
        private final List<Long> estimatedBytes = new ArrayList<>(16);
        private final List<Long> contextTokens = new ArrayList<>(16);
        private final List<Long> idleSeconds = new ArrayList<>(16);
        private final Map<String, Integer> bucketCounts = new LinkedHashMap<>(TURN_BUCKETS.length);
        private final Map<String, Long> bucketEstimatedBytes = new LinkedHashMap<>(TURN_BUCKETS.length);
        private final Map<String, Long> bucketContextTokens = new LinkedHashMap<>(TURN_BUCKETS.length);
        private final Map<String, List<Long>> bucketEstimatedValues = new LinkedHashMap<>(TURN_BUCKETS.length);
        private long estimatedBytesTotal;
        private long contextTokensTotal;
        private long inlineImageBytesTotal;

        private SessionAggregate() {
            for (var bucket : TURN_BUCKETS) {
                bucketCounts.put(bucket, 0);
            }
        }

        private void accept(SessionSnapshot snapshot) {
            var footprint = snapshot.footprint();
            if (footprint == null) return;
            long bytes = footprint.estimatedBytes();
            long tokens = footprint.contextTokens();
            estimatedBytes.add(bytes);
            contextTokens.add(tokens);
            idleSeconds.add(snapshot.idleMillis() / 1000);
            estimatedBytesTotal += bytes;
            contextTokensTotal += tokens;
            inlineImageBytesTotal += footprint.inlineImageBytes();
            var bucket = turnBucket(footprint.turnCount());
            bucketCounts.merge(bucket, 1, Integer::sum);
            bucketEstimatedBytes.merge(bucket, bytes, Long::sum);
            bucketContextTokens.merge(bucket, tokens, Long::sum);
            bucketEstimatedValues.computeIfAbsent(bucket, key -> new ArrayList<>(4)).add(bytes);
        }

        private void applyTo(RuntimeStatsView.SessionStats stats) {
            stats.estimatedBytesTotal = estimatedBytesTotal;
            stats.estimatedBytesP50 = percentile(estimatedBytes, 0.50);
            stats.estimatedBytesP90 = percentile(estimatedBytes, 0.90);
            stats.estimatedBytesP99 = percentile(estimatedBytes, 0.99);
            stats.estimatedBytesMax = estimatedBytes.isEmpty() ? null : Collections.max(estimatedBytes);
            stats.contextTokensTotal = contextTokensTotal;
            stats.contextTokensP50 = percentile(contextTokens, 0.50);
            stats.contextTokensP90 = percentile(contextTokens, 0.90);
            stats.inlineImageBytesTotal = inlineImageBytesTotal;
            stats.idleSecondsP50 = percentile(idleSeconds, 0.50);
            stats.idleSecondsP90 = percentile(idleSeconds, 0.90);
            stats.turnBucketCounts = bucketCounts;
            stats.turnBucketEstimatedBytes = bucketEstimatedBytes;
            stats.turnBucketContextTokens = bucketContextTokens;
            var bucketP50 = new LinkedHashMap<String, Long>();
            var bucketP90 = new LinkedHashMap<String, Long>();
            for (var entry : bucketEstimatedValues.entrySet()) {
                Collections.sort(entry.getValue());
                bucketP50.put(entry.getKey(), percentileSorted(entry.getValue(), 0.50));
                bucketP90.put(entry.getKey(), percentileSorted(entry.getValue(), 0.90));
            }
            stats.turnBucketEstimatedBytesP50 = bucketP50;
            stats.turnBucketEstimatedBytesP90 = bucketP90;
        }
    }
}
