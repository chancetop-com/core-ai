package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Lightweight read-side counters maintained by the skill hub endpoints
 * (fire-and-forget {@code $inc}); not an audit trail.
 *
 * @author stephen
 */
public class SkillStats {
    @Field(name = "pull_count")
    public Integer pullCount;

    @Field(name = "show_count")
    public Integer showCount;

    @Field(name = "last_pulled_at")
    public ZonedDateTime lastPulledAt;
}
