package ai.core.server.runtime;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One pod's runtime watermark sample, written every minute by {@link RuntimeStatsJob}. The collector
 * only writes and the dashboard only reads, so the watermarks can be tracked over time and per replica
 * instead of being visible only in the instant someone opens a page.
 *
 * @author stephen
 */
@Collection(name = "runtime_samples")
public class RuntimeSample {
    @Id
    public String id;

    @Field(name = "pod")
    public String pod;

    @Field(name = "collected_at")
    public ZonedDateTime collectedAt;

    @Field(name = "heap_used_percent")
    public Double heapUsedPercent;

    @Field(name = "heap_used")
    public Long heapUsed;

    @Field(name = "heap_max")
    public Long heapMax;

    @Field(name = "sessions_active")
    public Integer sessionsActive;

    @Field(name = "in_flight_turns")
    public Integer inFlightTurns;

    @Field(name = "turns_saturated_total")
    public Long turnsSaturated;

    @Field(name = "turns_rejected_total")
    public Long turnsRejected;

    @Field(name = "runs_active")
    public Integer runsActive;

    @Field(name = "runs_queued")
    public Integer runsQueued;

    @Field(name = "runs_rejected_total")
    public Long runsRejected;

    @Field(name = "sandboxes_live")
    public Integer sandboxesLive;

    @Field(name = "sandboxes_rejected_total")
    public Long sandboxesRejected;

    @Field(name = "span_ingest_depth")
    public Integer spanIngestDepth;

    @Field(name = "span_ingest_dropped_total")
    public Long spanIngestDropped;

    @Field(name = "claims_live")
    public Integer claimsLive;

    @Field(name = "direct_crs_live")
    public Integer directCrsLive;

    @Field(name = "creates_total")
    public Long createsTotal;

    @Field(name = "creates_failed_total")
    public Long createsFailedTotal;
}
