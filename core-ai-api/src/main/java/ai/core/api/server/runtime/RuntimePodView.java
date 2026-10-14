package ai.core.api.server.runtime;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * The latest watermark sample of one replica: every pod writes its own, so the dashboard can show the
 * fleet instead of whichever pod happened to answer the request.
 *
 * @author stephen
 */
public class RuntimePodView {
    @Property(name = "pod")
    public String pod;

    @Property(name = "collected_at")
    public ZonedDateTime collectedAt;

    /** Age of the sample in seconds; a growing value means that pod stopped reporting. */
    @Property(name = "age_seconds")
    public Long ageSeconds;

    @Property(name = "heap_used_percent")
    public Double heapUsedPercent;

    @Property(name = "heap_used")
    public Long heapUsed;

    @Property(name = "heap_max")
    public Long heapMax;

    @Property(name = "sessions_active")
    public Integer sessionsActive;

    @Property(name = "in_flight_turns")
    public Integer inFlightTurns;

    @Property(name = "turns_saturated_total")
    public Long turnsSaturated;

    @Property(name = "turns_rejected_total")
    public Long turnsRejected;

    @Property(name = "runs_active")
    public Integer runsActive;

    @Property(name = "runs_queued")
    public Integer runsQueued;

    @Property(name = "runs_rejected_total")
    public Long runsRejected;

    @Property(name = "sandboxes_live")
    public Integer sandboxesLive;

    @Property(name = "sandboxes_rejected_total")
    public Long sandboxesRejected;

    @Property(name = "span_ingest_depth")
    public Integer spanIngestDepth;

    @Property(name = "span_ingest_dropped_total")
    public Long spanIngestDropped;

    @Property(name = "claims_live")
    public Integer claimsLive;

    @Property(name = "direct_crs_live")
    public Integer directCrsLive;

    @Property(name = "creates_total")
    public Long createsTotal;

    @Property(name = "creates_failed_total")
    public Long createsFailedTotal;
}
