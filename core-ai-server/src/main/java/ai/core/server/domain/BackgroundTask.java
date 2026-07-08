package ai.core.server.domain;

import ai.core.server.task.TaskStatus;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Persistent record of a background maintenance task execution.
 * {@code _id} = {@code {type}:{date}} (e.g. "TRACE_DAILY_MAINTENANCE:2026-06-25"),
 * guaranteeing at most one run per type per day and providing natural sort order.
 *
 * @author cyril
 */
@Collection(name = "background_tasks")
public class BackgroundTask {
    @Id
    public String id;

    @Field(name = "type")
    public String type;

    @Field(name = "status")
    public TaskStatus status;

    @Field(name = "status_text")
    public String statusText;

    @Field(name = "claimed_by")
    public String claimedBy;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;

    @Field(name = "retry_count")
    public Integer retryCount;

    @Field(name = "logs")
    public List<String> logs;
}
