package ai.core.api.server.runtime;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Per-pod runtime snapshot plus the cross-pod sandbox fleet view (the fleet numbers are read from
 * the sandbox manager's own tracking, so a pod only reports what it can still see).
 *
 * @author stephen
 */
public class RuntimeStatsView {
    @Property(name = "pod")
    public String pod;

    @Property(name = "uptime_seconds")
    public Long uptimeSeconds;

    @Property(name = "memory")
    public MemoryStats memory;

    @Property(name = "sessions")
    public SessionStats sessions;

    @Property(name = "turns")
    public TurnStats turns;

    @Property(name = "runs")
    public RunStats runs;

    @Property(name = "sandbox")
    public SandboxStats sandbox;

    @Property(name = "sandbox_fleet")
    public SandboxFleetStats sandboxFleet;

    @Property(name = "span_ingest")
    public SpanIngestStats spanIngest;

    public static class MemoryStats {
        @Property(name = "heap_used")
        public Long heapUsed;

        @Property(name = "heap_max")
        public Long heapMax;

        @Property(name = "heap_used_percent")
        public Double heapUsedPercent;
    }

    public static class SessionStats {
        @Property(name = "active")
        public Integer active;

        @Property(name = "max")
        public Integer max;
    }

    public static class TurnStats {
        @Property(name = "in_flight")
        public Integer inFlight;

        @Property(name = "max")
        public Integer max;

        @Property(name = "rejected_total")
        public Long rejectedTotal;

        @Property(name = "saturated_total")
        public Long saturatedTotal;
    }

    public static class RunStats {
        @Property(name = "active")
        public Integer active;

        @Property(name = "queued")
        public Integer queued;

        @Property(name = "max_queue")
        public Integer maxQueue;

        @Property(name = "rejected_total")
        public Long rejectedTotal;
    }

    public static class SandboxStats {
        @Property(name = "live")
        public Integer live;

        @Property(name = "max")
        public Integer max;

        @Property(name = "rejected_total")
        public Long rejectedTotal;
    }

    public static class SandboxFleetStats {
        @Property(name = "live_by_kind")
        public Map<String, Integer> liveByKind;

        @Property(name = "live_by_user")
        public Map<String, Integer> liveByUser;

        @Property(name = "claims_live")
        public Integer claimsLive;

        @Property(name = "direct_crs_live")
        public Integer directCrsLive;

        @Property(name = "oldest_claim_age_seconds")
        public Long oldestClaimAgeSeconds;

        @Property(name = "oldest_cr_age_seconds")
        public Long oldestCrAgeSeconds;

        @Property(name = "creates_total")
        public Long createsTotal;

        @Property(name = "creates_failed_total")
        public Long createsFailedTotal;

        @Property(name = "last_create_ms")
        public Long lastCreateMs;
    }

    public static class SpanIngestStats {
        @Property(name = "queue_depth")
        public Integer queueDepth;

        @Property(name = "capacity")
        public Integer capacity;

        @Property(name = "dropped_total")
        public Long droppedTotal;
    }
}
