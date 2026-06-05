package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Persisted node-run status. Distinct from the engine's framework-free NodeFactStatus: FAILED_RETRYABLE here
 * projects to the engine's FAILED (halt-and-wait, out-edges stay PENDING), and WAITING is reserved for the
 * deferred human-input feature (the checkpoint infra already supports it).
 *
 * @author Xander
 */
public enum NodeRunStatus {
    @MongoEnumValue("RUNNING")
    RUNNING,
    @MongoEnumValue("COMPLETED")
    COMPLETED,
    @MongoEnumValue("SKIPPED")
    SKIPPED,
    @MongoEnumValue("FAILED_RETRYABLE")
    FAILED_RETRYABLE,
    @MongoEnumValue("WAITING")
    WAITING
}
