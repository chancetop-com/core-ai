package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum RunStatus {
    @MongoEnumValue("PENDING")
    PENDING,
    @MongoEnumValue("RUNNING")
    RUNNING,
    // parked on a HUMAN_INPUT node — not claimable (claim filter only takes PENDING/RUNNING); the resume
    // endpoint flips it back to PENDING. Not terminal: no completed_at.
    @MongoEnumValue("PAUSED")
    PAUSED,
    @MongoEnumValue("COMPLETED")
    COMPLETED,
    @MongoEnumValue("FAILED")
    FAILED,
    @MongoEnumValue("TIMEOUT")
    TIMEOUT,
    @MongoEnumValue("CANCELLED")
    CANCELLED,
    // schedule occurrence consumed without execution (SKIP concurrency policy, or agent missing/unpublished);
    // written by AgentScheduler only, never by workflow child runs
    @MongoEnumValue("SKIPPED")
    SKIPPED
}
