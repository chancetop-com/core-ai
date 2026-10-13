package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimeStatsView;
import ai.core.server.run.AgentRunner;
import ai.core.server.sandbox.SandboxManager;
import ai.core.server.sandbox.agentsandbox.AgentSandbox;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.trace.spi.LocalSpanProcessor;
import core.framework.inject.Inject;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
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
        var view = new RuntimeStatsView();
        view.pod = podName();
        view.uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        view.memory = memoryStats();
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

    private RuntimeStatsView.MemoryStats memoryStats() {
        var stats = new RuntimeStatsView.MemoryStats();
        stats.heapUsed = guard.heapUsedBytes();
        stats.heapMax = guard.heapMaxBytes();
        stats.heapUsedPercent = guard.heapUsedPercent();
        return stats;
    }

    private RuntimeStatsView.SessionStats sessionStats(RuntimeLimits limits) {
        var stats = new RuntimeStatsView.SessionStats();
        stats.active = sessionManager.activeSessionCount();
        stats.max = limits.maxSessionsPerPod;
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
}
