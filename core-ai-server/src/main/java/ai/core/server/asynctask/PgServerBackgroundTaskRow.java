package ai.core.server.asynctask;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgServerBackgroundTaskRow {
    @Column(name = "task_id")
    public String taskId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "owner_pod")
    public String ownerPod;

    @Column(name = "status")
    public String status;

    @Column(name = "result")
    public String result;

    @Column(name = "error")
    public String error;

    @Column(name = "output_ref")
    public String outputRef;

    @Column(name = "notified")
    public Boolean notified;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "finished_at")
    public ZonedDateTime finishedAt;
}
