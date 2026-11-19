package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One daily todo-digest attempt per user and local date. The id is user:date, so the insert decides
 * which replica owns the run and a completed run is never repeated.
 *
 * @author core-ai
 */
@Collection(name = "home_todo_digest_runs")
public class HomeTodoDigestRun {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    /** yyyy-MM-dd in Asia/Shanghai: the day being summarized. */
    @NotNull
    @Field(name = "run_date")
    public String runDate;

    /** RUNNING | COMPLETED | FAILED | SKIPPED */
    @NotNull
    @Field(name = "status")
    public String status;

    @Field(name = "model")
    public String model;

    @Field(name = "prompt_version")
    public String promptVersion;

    @Field(name = "session_count")
    public Integer sessionCount;

    @Field(name = "message_count")
    public Integer messageCount;

    @Field(name = "todo_count")
    public Integer todoCount;

    @Field(name = "skipped_count")
    public Integer skippedCount;

    @Field(name = "error")
    public String error;

    @NotNull
    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;
}
