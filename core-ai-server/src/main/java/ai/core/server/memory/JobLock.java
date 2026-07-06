package ai.core.server.memory;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * MongoDB-based distributed lock for scheduled jobs.
 * <p>
 * Used to prevent concurrent execution of the same job across multiple pods.
 * Each job registers a single document with its name as the {@code _id}.
 * A pod acquires the lock by atomically updating the document when
 * {@code lease_until <= now}. The lock is released after execution by setting
 * {@code lease_until = now}, allowing the next pod to claim it immediately.
 * <p>
 * If a pod crashes mid-execution, the lock auto-releases after the lease
 * duration, so another pod can take over on the next tick.
 *
 * @author stephen
 */
@Collection(name = "job_locks")
public class JobLock {
    @Id
    public String id;

    @Field(name = "lease_until")
    public ZonedDateTime leaseUntil;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
